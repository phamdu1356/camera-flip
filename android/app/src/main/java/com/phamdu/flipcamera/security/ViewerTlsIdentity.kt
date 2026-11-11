package com.phamdu.flipcamera.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

data class ViewerTlsMaterial(
    val sslContext: SSLContext,
    val certificate: X509Certificate,
    val sha256Fingerprint: String,
)

/** Keeps the signing key non-exportable and persists a browser-usable SAN certificate per install. */
class ViewerTlsIdentity(
    context: Context,
    private val serverNames: Set<String> = ViewerEndpointDiscovery.advertisedHosts(context),
) {
    private val appContext = context.applicationContext

    fun loadOrCreate(): ViewerTlsMaterial = synchronized(KEY_PAIR_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ensureKeyPair(keyStore)
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("Android Keystore TLS private key is missing")
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: error("Android Keystore TLS public key is missing")
        val requiredNames = serverNames.map(::normalizeName).toSet()
        val certificateFile = AtomicFile(File(appContext.filesDir, CERTIFICATE_FILE))
        val certificate = readCertificate(certificateFile)
            ?.takeIf { it.matches(publicKey, requiredNames) }
            ?: createAndPersistCertificate(certificateFile, publicKey, privateKey, requiredNames)

        val keyManager = DeviceKeyManager(privateKey, certificate)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), null, SecureRandom())
        }
        ViewerTlsMaterial(sslContext, certificate, fingerprint(certificate))
    }

    private fun ensureKeyPair(keyStore: KeyStore) {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            val keyInfo = privateKey?.let {
                java.security.KeyFactory.getInstance(it.algorithm, ANDROID_KEYSTORE)
                    .getKeySpec(it, KeyInfo::class.java)
            }
            if (KeyProperties.DIGEST_NONE in (keyInfo?.digests ?: emptyArray())) return

            // Development builds before the HTTPS server allowed SHA-256 only. TLS signs a
            // pre-hashed handshake transcript, so rotate that local key once to authorize NONE.
            keyStore.deleteEntry(KEY_ALIAS)
        }

        val now = System.currentTimeMillis()
        val serial = ByteArray(16).also(SecureRandom()::nextBytes).let { BigInteger(1, it) }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=Flip Camera Device"))
            .setCertificateSerialNumber(serial)
            .setCertificateNotBefore(Date(now - CERTIFICATE_CLOCK_SKEW_MS))
            .setCertificateNotAfter(Date(now + CERTIFICATE_VALIDITY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun readCertificate(file: AtomicFile): X509Certificate? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            file.openRead().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            }
        }.getOrNull()
    }

    private fun createAndPersistCertificate(
        file: AtomicFile,
        publicKey: java.security.PublicKey,
        privateKey: PrivateKey,
        names: Set<String>,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Flip Camera Device")
        val serial = ByteArray(16).also(SecureRandom()::nextBytes).let { BigInteger(1, it) }
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            Date(now - CERTIFICATE_CLOCK_SKEW_MS),
            Date(now + CERTIFICATE_VALIDITY_MS),
            subject,
            publicKey,
        )
        val subjectAltNames = names.map { name ->
            val type = if (name.contains(':') || name.matches(Regex("[0-9.]+"))) {
                GeneralName.iPAddress
            } else {
                GeneralName.dNSName
            }
            GeneralName(type, name)
        }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(subjectAltNames.toTypedArray()))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        val generated = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)),
        ).apply {
            check(publicKey.encoded.contentEquals(this.publicKey.encoded))
            verify(publicKey)
            checkValidity()
        }
        val output = file.startWrite()
        try {
            output.write(generated.encoded)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
        return generated
    }

    private fun X509Certificate.matches(publicKey: java.security.PublicKey, names: Set<String>): Boolean =
        runCatching {
            checkValidity()
            verify(publicKey)
            if (!this.publicKey.encoded.contentEquals(publicKey.encoded)) return false
            val subjectAltNames = subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                if (entry.size < 2 || entry[0] !is Int) return@mapNotNull null
                val value = when (val sanValue = entry[1]) {
                    is String -> sanValue
                    is ByteArray -> InetAddress.getByAddress(sanValue).hostAddress
                    else -> return@mapNotNull null
                }
                value?.let(::normalizeName)
            }.toSet()
            names.all(subjectAltNames::contains)
        }.getOrDefault(false)

    private fun normalizeName(name: String): String = name.trim().trimEnd('.').lowercase()

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { byte -> "%02X".format(byte) }

    private class DeviceKeyManager(
        private val privateKey: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String? = null

        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
            if (supports(keyType)) arrayOf(KEY_ALIAS) else null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String? = KEY_ALIAS.takeIf { supports(keyType) }

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            engine: SSLEngine?,
        ): String? = null

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            engine: SSLEngine?,
        ): String? = KEY_ALIAS.takeIf { supports(keyType) }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == KEY_ALIAS) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == KEY_ALIAS) privateKey else null

        private fun supports(keyType: String?): Boolean =
            keyType.equals("EC", ignoreCase = true) || keyType.equals("ECDSA", ignoreCase = true)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "flip_camera_viewer_tls_v1"
        private const val CERTIFICATE_FILE = "viewer-tls-certificate.der"
        private const val CERTIFICATE_CLOCK_SKEW_MS = 60_000L
        private const val CERTIFICATE_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
        private val KEY_PAIR_LOCK = Any()
    }
}

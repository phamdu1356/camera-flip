package com.phamdu.flipcamera.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

@RunWith(AndroidJUnit4::class)
class ViewerTlsIdentityTest {
    @Test
    fun androidKeystoreIdentityIsPersistentAndCanCompleteTlsHandshake() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = ViewerTlsIdentity(context)
        val first = identity.loadOrCreate()
        val second = ViewerTlsIdentity(context).loadOrCreate()
        assertEquals(first.sha256Fingerprint, second.sha256Fingerprint)
        assertTrue(first.certificate.publicKey.algorithm.equals("EC", ignoreCase = true))
        assertNotNull(first.certificate.encoded)
        assertTrue(first.certificate.subjectAlternativeNames.any { it[0] == 7 && it[1] == "127.0.0.1" })
        val ipSans = first.certificate.subjectAlternativeNames.orEmpty()
            .filter { it.size >= 2 && it[0] == 7 }
            .mapNotNull { entry ->
                when (val value = entry[1]) {
                    is String -> runCatching { InetAddress.getByName(value).address }.getOrNull()
                    is ByteArray -> value
                    else -> null
                }
            }
        ViewerEndpointDiscovery.snapshot(context).lanAddresses.forEach { address ->
            val expectedBytes = InetAddress.getByName(address).address
            assertTrue(
                "The TLS SAN must cover the discovered LAN address $address",
                ipSans.any { candidate -> candidate.contentEquals(expectedBytes) },
            )
        }
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val signingKey = keyStore.getKey("flip_camera_viewer_tls_v1", null)
        assertNotNull(signingKey)
        assertNull("Private key material must not be exportable", signingKey.encoded)

        val server = first.sslContext.serverSocketFactory
            .createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val serverProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
        assertTrue("TLS 1.2 or later must be available", serverProtocols.isNotEmpty())
        server.enabledProtocols = serverProtocols.toTypedArray()
        val serverFailure = AtomicReference<Throwable?>()
        try {
            val serverThread = Thread {
                try {
                    (server.accept() as SSLSocket).use { accepted ->
                        accepted.soTimeout = 5_000
                        accepted.startHandshake()
                        accepted.getOutputStream().write("ok".toByteArray(Charsets.UTF_8))
                        accepted.getOutputStream().flush()
                    }
                } catch (failure: Throwable) {
                    serverFailure.set(failure)
                }
            }.apply { start() }
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(FingerprintTrustManager(first.certificate, first.sha256Fingerprint)), null)
            }
            val advertisedHost = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val transport = Socket(InetAddress.getByName("127.0.0.1"), server.localPort)
            (clientContext.socketFactory.createSocket(transport, advertisedHost, server.localPort, true) as SSLSocket).use { client ->
                client.enabledProtocols = serverProtocols.toTypedArray()
                client.soTimeout = 5_000
                client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                client.startHandshake()
                assertEquals(first.certificate.subjectX500Principal, client.session.peerPrincipal)
                assertEquals("ok", String(client.getInputStream().readBytes(), Charsets.UTF_8))
            }
            serverThread.join(5_000L)
            assertTrue("TLS server thread should finish", !serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("TLS server handshake failed", it) }
        } finally {
            server.close()
        }
    }

    private class FingerprintTrustManager(
        private val expected: X509Certificate,
        private val fingerprint: String,
    ) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expected)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || chain.first().encoded.contentEquals(expected.encoded).not()) {
                throw CertificateException("Unexpected TLS certificate")
            }
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(chain.first().encoded)
                .joinToString(":") { byte -> "%02X".format(byte) }
            if (actual != fingerprint) throw CertificateException("Certificate fingerprint mismatch")
        }
    }
}

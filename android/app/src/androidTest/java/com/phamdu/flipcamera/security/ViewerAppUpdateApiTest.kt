package com.phamdu.flipcamera.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** HTTPS thật trên Flip3: phục vụ viewer-app.apk nhúng, có phiên mới đọc được, SHA-256 khớp byte tải về. */
@RunWith(AndroidJUnit4::class)
class ViewerAppUpdateApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Response(val status: String, val headers: List<String>, val body: ByteArray)

    private fun withServer(block: (SSLContext, String, Int, String, String) -> Unit) {
        val pairing = ViewerPairingManager()
        val server = ViewerHttpsServer.forSnapshot(
            context,
            0,
            ViewerEndpointDiscovery.snapshot(
                context,
                context.getSystemService(android.net.ConnectivityManager::class.java).allNetworks.asList(),
            ),
        ) { ViewerAppUpdateApi(pairing, context).install(this) }
        try {
            val port = kotlinx.coroutines.runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinTrustManager(identity.certificate)), null) }
            val code = pairing.createPairingCode()
            val waiting = pairing.submitPairingCode(code.digits, "192.168.1.9", "Test updater") as PairingSubmission.Waiting
            pairing.approve(waiting.requestId)
            val token = (pairing.poll(waiting.requestId) as PairingPoll.Approved).sessionToken
            block(ssl, host, port, authority, token)
        } finally {
            server.close()
        }
    }

    private fun get(ssl: SSLContext, host: String, port: Int, authority: String, path: String, cookie: String?): Response {
        val raw = Socket(InetAddress.getByName("127.0.0.1"), port)
        val client = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        client.soTimeout = 15_000
        client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        client.startHandshake()
        client.use {
            val cookieLine = if (cookie != null) "Cookie: $VIEWER_SESSION_COOKIE=$cookie\r\n" else ""
            it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $authority\r\n${cookieLine}Connection: close\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val input = it.inputStream
            val status = line(input)
            val headers = generateSequence { line(input).takeIf { l -> l.isNotEmpty() } }.toList()
            return Response(status, headers, input.readBytes())
        }
    }

    @Test
    fun servesVersionAndApkOnlyToAuthenticatedViewer() = withServer { ssl, host, port, authority, token ->
        assertTrue(get(ssl, host, port, authority, "/api/viewer-app/version", null).status.contains(" 401"))
        assertTrue(get(ssl, host, port, authority, "/api/viewer-app.apk", null).status.contains(" 401"))

        val version = get(ssl, host, port, authority, "/api/viewer-app/version", token)
        assertTrue(version.status, version.status.contains(" 200"))
        val meta = JSONObject(version.body.toString(Charsets.UTF_8))
        assertEquals("com.phamdu.flipviewer.debug", meta.getString("packageName"))

        val apk = get(ssl, host, port, authority, "/api/viewer-app.apk", token)
        assertTrue(apk.status, apk.status.contains(" 200"))
        assertTrue(apk.headers.toString(), apk.headers.any { it.equals("Content-Type: application/vnd.android.package-archive", true) })
        assertEquals(meta.getLong("size"), apk.body.size.toLong())
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.body).joinToString("") { "%02x".format(it) }
        assertEquals(meta.getString("sha256"), sha)
        // APK hợp lệ: bắt đầu bằng chữ ký ZIP.
        assertEquals('P'.code.toByte(), apk.body[0]); assertEquals('K'.code.toByte(), apk.body[1])
        android.util.Log.i("ViewerAppUpdateApiTest", "versionCode=${meta.getInt("versionCode")} size=${apk.body.size} sha=$sha")
    }

    private fun line(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val v = input.read()
            if (v < 0 || v == '\n'.code) break
            if (v != '\r'.code) bytes.write(v)
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private class PinTrustManager(private val expected: X509Certificate) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expected)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !chain.first().encoded.contentEquals(expected.encoded)) throw CertificateException("Unexpected TLS certificate")
        }
    }
}

package com.phamdu.flipcamera.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phamdu.flipcamera.viewer.ViewerAudioHub
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** HTTPS thật trên Flip3: pairing trong tiến trình, micro thật. Không đụng dữ liệu người dùng. */
@RunWith(AndroidJUnit4::class)
class ViewerAudioApiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun withServer(audioOn: Boolean, block: (SSLContext, String, Int, String, ViewerPairingManager, ViewerAudioHub) -> Unit) {
        val pairing = ViewerPairingManager()
        val hub = ViewerAudioHub { audioOn }
        val server = ViewerHttpsServer.forSnapshot(
            context,
            0,
            // Máy đang đi qua VPN nên activeNetwork không phải Wi-Fi; dùng đúng mạng Wi-Fi/LAN như viewer thật
            // để SAN chứng chỉ không đổi (không làm mất pairing của browser).
            ViewerEndpointDiscovery.snapshot(
                context,
                context.getSystemService(android.net.ConnectivityManager::class.java).allNetworks.asList(),
            ),
        ) { ViewerAudioApi(pairing, hub).install(this) }
        try {
            val port = kotlinx.coroutines.runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinTrustManager(identity.certificate)), null) }
            val code = pairing.createPairingCode()
            val waiting = pairing.submitPairingCode(code.digits, "192.168.1.9", "Test viewer") as PairingSubmission.Waiting
            pairing.approve(waiting.requestId)
            val token = (pairing.poll(waiting.requestId) as PairingPoll.Approved).sessionToken
            block(ssl, host, port, "$authority|$token", pairing, hub)
        } finally {
            server.close()
        }
    }

    private fun open(ssl: SSLContext, host: String, port: Int, authority: String, cookie: String?): SSLSocket {
        val raw = Socket(InetAddress.getByName("127.0.0.1"), port)
        val client = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        client.soTimeout = 8_000
        client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        client.startHandshake()
        val cookieLine = if (cookie != null) "Cookie: $VIEWER_SESSION_COOKIE=$cookie\r\n" else ""
        client.getOutputStream().write("GET /api/live-audio HTTP/1.1\r\nHost: $authority\r\n${cookieLine}Connection: close\r\n\r\n".toByteArray())
        client.getOutputStream().flush()
        return client
    }

    @Test
    fun authenticatedListenerGetsLivePcmStream() = withServer(true) { ssl, host, port, at, _, hub ->
        val (authority, token) = at.split("|")
        open(ssl, host, port, authority, token).use { client ->
            val input = client.inputStream
            val status = line(input)
            val headers = generateSequence { line(input).takeIf { it.isNotEmpty() } }.toList()
            var total = 0
            val buffer = ByteArray(8192)
            val start = System.currentTimeMillis()
            var firstByteMs = -1L
            while (total < 32_000 && System.currentTimeMillis() - start < 6_000) {
                val n = input.read(buffer)
                if (n < 0) break
                if (firstByteMs < 0) firstByteMs = System.currentTimeMillis() - start
                total += n
            }
            android.util.Log.i("ViewerAudioApiTest", "status=$status headers=$headers total=$total firstByteMs=$firstByteMs")
            assertTrue(status, status.startsWith("HTTP/1.1 200"))
            assertTrue(headers.toString(), headers.any { it.equals("X-Audio-Format: pcm_s16le;rate=16000;channels=1", true) })
            assertTrue("chỉ nhận $total byte", total >= 32_000)
        }
        Thread.sleep(1_500)
        assertEquals("mic phải đóng khi người nghe rời", 0, hub.subscriberCount)
    }

    @Test
    fun audioOffReturns503AndNoCookieReturns401() {
        withServer(false) { ssl, host, port, at, _, _ ->
            val (authority, token) = at.split("|")
            open(ssl, host, port, authority, token).use { assertTrue(line(it.inputStream).contains(" 503")) }
            open(ssl, host, port, authority, null).use { assertTrue(line(it.inputStream).contains(" 401")) }
        }
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

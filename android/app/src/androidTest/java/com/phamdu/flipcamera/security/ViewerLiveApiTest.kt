package com.phamdu.flipcamera.security

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phamdu.flipcamera.viewer.ViewerFrameHub
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerLiveApiTest {
    @Test
    fun stalledClientDoesNotBlockFramePublisherAndTimesOut() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val frameHub = ViewerFrameHub()
        val liveApi = ViewerLiveApi(pairing, frameHub, maxStreams = 3)
        val server = ViewerHttpsServer.forCurrentLan(context, 0) { liveApi.install(this) }
        val executor = Executors.newSingleThreadExecutor()
        val clientContext: SSLContext
        var slowClient: SSLSocket? = null
        var sessionToken: String? = null
        try {
            val port = server.start()
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(PinTrustManager(identity.certificate)), null)
            }
            val token = approvedSessionToken(pairing)
            sessionToken = token
            val pendingClient = executor.submit<SSLSocket> {
                openLiveStream(clientContext, host, port, authority, token, receiveBufferSize = 1_024)
            }
            awaitCondition { liveApi.activeStreamCount == 1 && frameHub.subscriberCount == 1 }
            frameHub.publishJpeg(
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte()),
                System.currentTimeMillis(),
            )
            slowClient = pendingClient.get(10, TimeUnit.SECONDS)

            // Never read the multipart body. Continue publishing often enough that the
            // no-frame watchdog cannot hide a socket-level write timeout.
            val largeFrame = ByteArray(256 * 1024).apply {
                this[0] = 0xff.toByte()
                this[1] = 0xd8.toByte()
                this[lastIndex - 1] = 0xff.toByte()
                this[lastIndex] = 0xd9.toByte()
            }
            var slowestPublishMs = 0L
            val publishingStarted = SystemClock.elapsedRealtime()
            repeat(180) {
                val publishStarted = SystemClock.elapsedRealtime()
                frameHub.publishJpeg(largeFrame, System.currentTimeMillis())
                slowestPublishMs = maxOf(slowestPublishMs, SystemClock.elapsedRealtime() - publishStarted)
                delay(100L)
            }
            val publishingDuration = SystemClock.elapsedRealtime() - publishingStarted
            assertTrue("Frame publishing blocked behind a slow viewer: $slowestPublishMs ms", slowestPublishMs < 500L)
            assertTrue("Slow-viewer exercise exceeded its 30-second bound: $publishingDuration ms", publishingDuration < 30_000L)
            awaitCondition(timeoutMs = 8_000L) {
                liveApi.activeStreamCount == 0 && frameHub.subscriberCount == 0
            }
        } finally {
            runCatching { slowClient?.close() }
            sessionToken?.let(pairing::logout)
            executor.shutdownNow()
            frameHub.clear()
            server.close()
        }
    }

    @Test
    fun threeStreamsAreAllowedFourthIsRejectedAndRevokedStreamsClose() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val frameHub = ViewerFrameHub()
        val liveApi = ViewerLiveApi(pairing, frameHub, maxStreams = 3)
        val server = ViewerHttpsServer.forCurrentLan(context, 0) { liveApi.install(this) }
        val executor = Executors.newFixedThreadPool(3)
        val openSockets = CopyOnWriteArrayList<SSLSocket>()
        val pendingClients = mutableListOf<java.util.concurrent.Future<SSLSocket>>()
        try {
            val port = server.start()
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(PinTrustManager(identity.certificate)), null)
            }
            val tokens = (1..3).map { approvedSessionToken(pairing) }

            tokens.forEach { token ->
                pendingClients += executor.submit<SSLSocket> {
                    openLiveStream(clientContext, host, port, authority, token).also(openSockets::add)
                }
            }
            awaitCondition {
                liveApi.activeStreamCount == 3 && frameHub.subscriberCount == 3
            }

            val fourthStatus = requestLiveStatus(clientContext, host, port, authority, tokens.first())
            assertTrue("Expected the fourth stream to be rejected: $fourthStatus", fourthStatus.startsWith("HTTP/1.1 429"))

            frameHub.publishJpeg(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte()), System.currentTimeMillis())
            pendingClients.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(3, liveApi.activeStreamCount)

            tokens.forEach(pairing::logout)
            frameHub.publishJpeg(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x02, 0xff.toByte(), 0xd9.toByte()), System.currentTimeMillis())
            awaitCondition { liveApi.activeStreamCount == 0 && frameHub.subscriberCount == 0 }
        } finally {
            openSockets.forEach { runCatching { it.close() } }
            pendingClients.forEach { it.cancel(true) }
            executor.shutdownNow()
            frameHub.clear()
            server.close()
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 8_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10L)
        }
    }

    private fun openLiveStream(
        clientContext: SSLContext,
        host: String,
        port: Int,
        authority: String,
        token: String,
        receiveBufferSize: Int = 0,
    ): SSLSocket {
        val raw = Socket(InetAddress.getByName("127.0.0.1"), port)
        val client = clientContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        client.soTimeout = 10_000
        if (receiveBufferSize > 0) client.receiveBufferSize = receiveBufferSize
        client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        client.startHandshake()
        client.getOutputStream().write(
            "GET /api/live HTTP/1.1\r\nHost: $authority\r\nCookie: $VIEWER_SESSION_COOKIE=$token\r\nConnection: keep-alive\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        client.getOutputStream().flush()
        val input = client.inputStream
        val status = readLine(input)
        check(status.startsWith("HTTP/1.1 200")) { "Unexpected live response: $status" }
        while (readLine(input).isNotEmpty()) Unit
        check(input.read() >= 0) { "Live stream closed before first frame" }
        return client
    }

    private fun requestLiveStatus(
        clientContext: SSLContext,
        host: String,
        port: Int,
        authority: String,
        token: String,
    ): String {
        val raw = Socket(InetAddress.getByName("127.0.0.1"), port)
        (clientContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket).use { client ->
            client.soTimeout = 5_000
            client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            client.startHandshake()
            client.getOutputStream().write(
                "GET /api/live HTTP/1.1\r\nHost: $authority\r\nCookie: $VIEWER_SESSION_COOKIE=$token\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII),
            )
            client.getOutputStream().flush()
            return readLine(client.inputStream)
        }
    }

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun approvedSessionToken(pairing: ViewerPairingManager): String {
        val code = pairing.createPairingCode()
        val waiting = pairing.submitPairingCode(code.digits, "192.168.1.9", "Test viewer")
            as PairingSubmission.Waiting
        pairing.approve(waiting.requestId)
        return (pairing.poll(waiting.requestId) as PairingPoll.Approved).sessionToken
    }

    private class PinTrustManager(private val expected: X509Certificate) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expected)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !chain.first().encoded.contentEquals(expected.encoded)) {
                throw CertificateException("Unexpected TLS certificate")
            }
        }
    }
}

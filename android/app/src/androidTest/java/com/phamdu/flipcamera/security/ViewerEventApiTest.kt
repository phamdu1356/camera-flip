package com.phamdu.flipcamera.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerEventApiTest {
    @Test
    fun eventsRequireSessionBoundQueryAndReturnOnlyOpaqueMediaReferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val eventId = UUID.randomUUID().toString()
        val segmentId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val source = FakeEventSource(
            listOf(
                ViewerEventRecord(
                    id = eventId,
                    kind = "MOTION",
                    startUtc = now - 5_000L,
                    endUtc = now - 1_000L,
                    peakUtc = now - 3_000L,
                    motionScore = 0.7,
                    segmentId = segmentId,
                    segmentStartUtc = now - 20_000L,
                    segmentDurationMs = 60_000L,
                    thumbnailAvailable = true,
                ),
                ViewerEventRecord(
                    id = UUID.randomUUID().toString(),
                    kind = "<img src=x onerror=alert(1)>",
                    startUtc = now - 4_000L,
                    endUtc = now - 2_000L,
                    peakUtc = null,
                    motionScore = null,
                    segmentId = null,
                    segmentStartUtc = null,
                    segmentDurationMs = null,
                    thumbnailAvailable = false,
                ),
            ),
        )
        val server = ViewerHttpsServer.forCurrentLan(context, 0) {
            ViewerApi(pairing).install(this)
            ViewerEventApi(pairing, source).install(this)
        }
        try {
            val port = runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.first()
            val authority = formatAuthority(host, port)
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(PinTrustManager(identity.certificate)), null)
            }

            val unauthorized = request(
                clientContext, host, port, "/api/events?from=${now - 60_000L}&to=$now&limit=10", authority, null,
            )
            assertTrue(unauthorized.status.startsWith("HTTP/1.1 401"))
            assertEquals(0, source.listCalls)
            val unauthorizedThumbnail = request(
                clientContext,
                host,
                port,
                "/api/events/$eventId/thumbnail",
                authority,
                null,
            )
            assertTrue(unauthorizedThumbnail.status.startsWith("HTTP/1.1 401"))
            assertEquals(0, source.thumbnailCalls)

            val token = approvedSessionToken(pairing)
            val invalidLimit = request(
                clientContext,
                host,
                port,
                "/api/events?from=${now - 60_000L}&to=$now&limit=201",
                authority,
                token,
            )
            assertTrue(invalidLimit.status.startsWith("HTTP/1.1 400"))
            assertEquals(0, source.listCalls)

            val invalidTime = request(
                clientContext,
                host,
                port,
                "/api/events?from=not-a-time&to=$now",
                authority,
                token,
            )
            assertTrue(invalidTime.status.startsWith("HTTP/1.1 400"))
            val oversizedWindow = request(
                clientContext,
                host,
                port,
                "/api/events?from=${now - 32L * 24L * 60L * 60L * 1_000L}&to=$now",
                authority,
                token,
            )
            assertTrue(oversizedWindow.status.startsWith("HTTP/1.1 400"))
            assertEquals(0, source.listCalls)

            val invalidEventId = request(
                clientContext,
                host,
                port,
                "/api/events/not-a-uuid/thumbnail",
                authority,
                token,
            )
            assertTrue(invalidEventId.status.startsWith("HTTP/1.1 400"))
            assertEquals(0, source.thumbnailCalls)
            val encodedTraversal = request(
                clientContext,
                host,
                port,
                "/api/events/%2e%2e%2fsecret/thumbnail",
                authority,
                token,
            )
            assertFalse(encodedTraversal.status.startsWith("HTTP/1.1 2"))
            assertEquals(0, source.thumbnailCalls)

            val response = request(
                clientContext,
                host,
                port,
                "/api/events?from=${now - 60_000L}&to=$now&limit=10",
                authority,
                token,
            )
            assertTrue(response.status.startsWith("HTTP/1.1 200"))
            val eventList = JSONObject(response.body).getJSONArray("events")
            assertEquals(1, eventList.length())
            assertFalse(response.body.contains("onerror"))
            val event = eventList.getJSONObject(0)
            assertEquals("MOTION", event.getString("kind"))
            assertEquals(segmentId, event.getString("segmentId"))
            assertEquals("/api/segments/$segmentId/video", event.getString("videoUrl"))
            assertEquals("/api/events/$eventId/thumbnail", event.getString("thumbnailUrl"))
            assertFalse(response.body.contains("/data/user/"))
            assertEquals("private, no-store", response.headers["cache-control"])
            assertEquals("nosniff", response.headers["x-content-type-options"])

            // FakeEventSource deliberately supplies end == from; API must enforce [from,to).
            val endsAtFrom = request(
                clientContext, host, port,
                "/api/events?from=${now - 1_000L}&to=$now&limit=10", authority, token,
            )
            assertTrue(endsAtFrom.status.startsWith("HTTP/1.1 200"))
            assertEquals(0, JSONObject(endsAtFrom.body).getJSONArray("events").length())
            val crossesBothBoundaries = request(
                clientContext, host, port,
                "/api/events?from=${now - 3_000L}&to=${now - 2_000L}&limit=10", authority, token,
            )
            assertTrue(crossesBothBoundaries.status.startsWith("HTTP/1.1 200"))
            assertEquals(eventId, JSONObject(crossesBothBoundaries.body).getJSONArray("events").getJSONObject(0).getString("id"))
            val startsAtTo = request(
                clientContext, host, port,
                "/api/events?from=${now - 60_000L}&to=${now - 5_000L}&limit=10", authority, token,
            )
            assertTrue(startsAtTo.status.startsWith("HTTP/1.1 200"))
            assertEquals(0, JSONObject(startsAtTo.body).getJSONArray("events").length())

            val thumbnail = request(
                clientContext,
                host,
                port,
                "/api/events/$eventId/thumbnail",
                authority,
                token,
            )
            assertTrue(thumbnail.status.startsWith("HTTP/1.1 200"))
            assertEquals("image/jpeg", thumbnail.headers["content-type"]?.substringBefore(';'))
            assertEquals("4", thumbnail.headers["content-length"])
            assertTrue(thumbnail.rawBody.contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())))
        } finally {
            server.close()
        }
    }

    private class FakeEventSource(private val events: List<ViewerEventRecord>) : ViewerEventSource {
        var listCalls = 0
        var thumbnailCalls = 0

        override suspend fun listEvents(fromUtc: Long, toUtc: Long, limit: Int): List<ViewerEventRecord> {
            listCalls += 1
            return events.filter { it.endUtc >= fromUtc && it.startUtc < toUtc }.take(limit)
        }

        override suspend fun openThumbnail(eventId: String): ViewerEventThumbnail? {
            thumbnailCalls += 1
            if (events.none { it.id == eventId }) return null
            return ViewerEventThumbnail(ByteArrayInputStream(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())), 4)
        }
    }

    private data class RawResponse(
        val status: String,
        val headers: Map<String, String>,
        val rawBody: ByteArray,
    ) {
        val body: String get() = rawBody.toString(Charsets.UTF_8)
    }

    private fun request(
        clientContext: SSLContext,
        peerHost: String,
        port: Int,
        path: String,
        hostHeader: String,
        token: String?,
    ): RawResponse {
        val raw = Socket(InetAddress.getByName("127.0.0.1"), port)
        (clientContext.socketFactory.createSocket(raw, peerHost, port, true) as SSLSocket).use { client ->
            client.soTimeout = 5_000
            client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            client.startHandshake()
            val request = buildString {
                append("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n")
                if (token != null) append("Cookie: $VIEWER_SESSION_COOKIE=$token\r\n")
                append("\r\n")
            }
            client.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()
            val responseBytes = client.getInputStream().readBytes()
            val separator = byteArrayOf(13, 10, 13, 10)
            val headerEnd = responseBytes.indexOfSequence(separator)
            require(headerEnd >= 0) { "HTTP response has no header terminator" }
            val headerText = responseBytes.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            return RawResponse(
                status = lines.first(),
                headers = headers,
                rawBody = responseBytes.copyOfRange(headerEnd + separator.size, responseBytes.size),
            )
        }
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        for (start in 0..(size - needle.size)) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun approvedSessionToken(pairing: ViewerPairingManager): String {
        val code = pairing.createPairingCode()
        val pending = pairing.submitPairingCode(code.digits, "192.168.1.9", "Test device")
            as PairingSubmission.Waiting
        assertEquals(PairingApproval.APPROVED, pairing.approve(pending.requestId))
        return (pairing.poll(pending.requestId) as PairingPoll.Approved).sessionToken
    }

    private fun formatAuthority(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

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

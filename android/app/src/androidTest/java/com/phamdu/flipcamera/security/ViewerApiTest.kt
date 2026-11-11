package com.phamdu.flipcamera.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phamdu.flipcamera.data.CameraDatabase
import com.phamdu.flipcamera.data.SegmentEntity
import com.phamdu.flipcamera.data.SegmentFileStore
import com.phamdu.flipcamera.data.SegmentStatus
import com.phamdu.flipcamera.viewer.ViewerFrameHub
import java.io.File
import java.net.InetAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerApiTest {
    @Test
    fun liveRouteRequiresSessionAndReturnsUnavailableBeforeAFrameExists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val hub = ViewerFrameHub()
        val server = ViewerHttpsServer.forCurrentLan(context, 0) {
            ViewerApi(pairing).install(this)
            ViewerLiveApi(pairing, hub).install(this)
        }
        try {
            val port = runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.first()
            val authority = formatAuthority(host, port)
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(CertificatePinTrustManager(identity.certificate)), null)
            }

            val unauthorized = request(clientContext, host, port, "GET", "/api/live", authority, null, null)
            assertTrue(unauthorized.status.startsWith("HTTP/1.1 401"))
            assertFalse(hub.hasSubscribers)

            val token = approvedSessionToken(pairing)
            val unavailable = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/live",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertTrue(
                "Expected 503 before MJPEG headers, got ${unavailable.status}",
                unavailable.status.startsWith("HTTP/1.1 503"),
            )
            assertFalse("Preflight cancellation must release its frame subscriber", hub.hasSubscribers)
        } finally {
            server.close()
        }
    }

    @Test
    fun mediaRoutesAuthenticateAndServeOnlyFinalizedSingleRanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, CameraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val id = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val startUtc = System.currentTimeMillis() - 3_000L
        val endUtc = startUtc + 2_000L
        val contents = "0123456789".toByteArray(Charsets.US_ASCII)
        val directory = File(context.filesDir, "segments")
        directory.mkdirs()
        val finalFile = File(directory, "$id.mp4")
        finalFile.writeBytes(contents)
        val dao = database.segmentDao()
        withContext(Dispatchers.IO) {
            dao.insert(
                SegmentEntity(
                    id = id,
                    sessionId = sessionId,
                    startUtc = startUtc,
                    internalName = "$id.pending.mp4",
                    path = File(directory, "$id.pending.mp4").absolutePath,
                ),
            )
            assertEquals(
                1,
                dao.finalizeSegment(
                    id = id,
                    expectedRevision = 1,
                    startUtc = startUtc,
                    endUtc = endUtc,
                    duration = endUtc - startUtc,
                    bytes = contents.size.toLong(),
                    width = 640,
                    height = 360,
                    codec = "video/avc",
                    audio = false,
                    finalInternalName = finalFile.name,
                    finalPath = finalFile.absolutePath,
                ),
            )
        }
        val pairing = ViewerPairingManager()
        val token = approvedSessionToken(pairing)
        val server = ViewerHttpsServer.forCurrentLan(context, 0) {
            ViewerApi(pairing).install(this)
            ViewerMediaApi(pairing, dao, SegmentFileStore(context)).install(this)
        }
        try {
            val port = runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.first()
            val authority = formatAuthority(host, port)
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(CertificatePinTrustManager(identity.certificate)), null)
            }

            val unauthenticated = request(
                clientContext, host, port, "GET", "/api/timeline", authority, null, null,
            )
            assertTrue(unauthenticated.status.startsWith("HTTP/1.1 401"))

            val timeline = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/timeline?from=$startUtc&to=$endUtc&limit=10",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertTrue(timeline.status.startsWith("HTTP/1.1 200"))
            assertTrue(timeline.body.contains(id))
            assertFalse(timeline.body.contains(finalFile.absolutePath))
            val timelineSegment = JSONObject(timeline.body).getJSONArray("segments").getJSONObject(0)
            assertEquals("/api/segments/$id/video", timelineSegment.getString("videoUrl"))

            val invalidTimelineTime = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/timeline?from=not-a-time&to=$endUtc",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertTrue(invalidTimelineTime.status.startsWith("HTTP/1.1 400"))
            assertFalse(invalidTimelineTime.body.contains(finalFile.absolutePath))
            val oversizedTimelineWindow = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/timeline?from=${endUtc - 32L * 24L * 60L * 60L * 1_000L}&to=$endUtc",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertTrue(oversizedTimelineWindow.status.startsWith("HTTP/1.1 400"))

            val unauthenticatedVideo = request(
                clientContext, host, port, "GET", "/api/segments/$id/video", authority, null, null,
            )
            assertTrue(unauthenticatedVideo.status.startsWith("HTTP/1.1 401"))
            assertFalse(unauthenticatedVideo.body.contains(finalFile.absolutePath))

            val invalidSegmentId = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/segments/not-a-uuid/video",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertTrue(invalidSegmentId.status.startsWith("HTTP/1.1 400"))
            assertFalse(invalidSegmentId.body.contains(finalFile.absolutePath))
            val encodedTraversal = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/segments/%2e%2e%2fsecret/video",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertFalse("Encoded traversal must not return a successful media response", encodedTraversal.status.startsWith("HTTP/1.1 2"))
            assertFalse(encodedTraversal.body.contains(finalFile.absolutePath))

            val partial = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/segments/$id/video",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
                mapOf("Range" to "bytes=3-6"),
            )
            assertTrue(partial.status.startsWith("HTTP/1.1 206"))
            assertEquals("bytes 3-6/${contents.size}", partial.headers["content-range"])
            assertEquals("4", partial.headers["content-length"])
            assertEquals("3456", partial.body)

            val multiRange = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/segments/$id/video",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
                mapOf("Range" to "bytes=0-1,4-5"),
            )
            assertTrue(multiRange.status.startsWith("HTTP/1.1 416"))
            assertEquals("bytes */${contents.size}", multiRange.headers["content-range"])
            assertEquals(SegmentStatus.READY, withContext(Dispatchers.IO) { dao.getById(id)?.status })

            val logout = request(
                clientContext,
                host,
                port,
                "POST",
                "/api/logout",
                authority,
                "https://$authority",
                "",
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertTrue(logout.status.startsWith("HTTP/1.1 204"))
            val rangeAfterRevoke = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/segments/$id/video",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
                mapOf("Range" to "bytes=0-1"),
            )
            assertTrue(rangeAfterRevoke.status.startsWith("HTTP/1.1 401"))
            assertFalse(rangeAfterRevoke.body.contains(finalFile.absolutePath))
        } finally {
            server.close()
            database.close()
            finalFile.delete()
        }
    }

    @Test
    fun validViewerSessionCannotCallRecorderOrLibraryAdminRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val token = approvedSessionToken(pairing)
        val segmentId = UUID.randomUUID().toString()
        val server = ViewerHttpsServer.forCurrentLan(context, 0) {
            ViewerApi(pairing).install(this)
        }
        try {
            val port = runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = formatAuthority(host, port)
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(CertificatePinTrustManager(identity.certificate)), null)
            }
            val adminRequests = listOf(
                "POST" to "/api/recording/start",
                "POST" to "/api/recording/stop",
                "POST" to "/api/settings",
                "POST" to "/api/retention",
                "POST" to "/api/segments/$segmentId/protect",
                "DELETE" to "/api/segments/$segmentId",
                "POST" to "/api/viewer/start",
            )
            adminRequests.forEach { (method, path) ->
                val response = request(
                    clientContext,
                    host,
                    port,
                    method,
                    path,
                    authority,
                    null,
                    null,
                    "$VIEWER_SESSION_COOKIE=$token",
                )
                assertFalse("$method $path unexpectedly reached a viewer session", response.status.startsWith("HTTP/1.1 2"))
                assertFalse(response.body.contains(token))
                assertFalse(response.body.contains("/data/user/"))
            }
            val sessionStillValid = request(
                clientContext,
                host,
                port,
                "GET",
                "/api/status",
                authority,
                null,
                null,
                "$VIEWER_SESSION_COOKIE=$token",
            )
            assertTrue(sessionStillValid.status.startsWith("HTTP/1.1 200"))
        } finally {
            server.close()
        }
    }

    @Test
    fun pairingApprovalSetsSecureCookieAndLogoutRevokesIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val code = pairing.createPairingCode()
        val server = ViewerHttpsServer.forCurrentLan(context, 0) {
            ViewerApi(pairing).install(this)
        }
        try {
            val port = runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = formatAuthority(host, port)
            val origin = "https://$authority"
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(CertificatePinTrustManager(identity.certificate)), null)
            }

            val crossOrigin = request(
                clientContext, host, port, "POST", "/api/pair/request", authority,
                "https://attacker.example:$port", form("code" to code.digits, "deviceName" to "Laptop"),
            )
            assertTrue(crossOrigin.status.startsWith("HTTP/1.1 403"))
            assertTrue(pairing.pendingRequests().isEmpty())

            val accepted = request(
                clientContext, host, port, "POST", "/api/pair/request", authority, origin,
                form("code" to code.digits, "deviceName" to "Laptop"),
            )
            assertTrue("Expected pending response, got ${accepted.status}: ${accepted.body}", accepted.status.startsWith("HTTP/1.1 202"))
            val requestId = Regex("\"requestId\":\"([A-Za-z0-9_-]+)\"")
                .find(accepted.body)?.groupValues?.get(1)
            assertNotNull(requestId)
            val id = requireNotNull(requestId)
            assertEquals(PairingApproval.APPROVED, pairing.approve(id))

            val polled = request(
                clientContext, host, port, "POST", "/api/pair/status", authority, origin,
                form("requestId" to id),
            )
            assertTrue(polled.status.startsWith("HTTP/1.1 200"))
            assertTrue(polled.headers["set-cookie"].orEmpty().contains("HttpOnly; SameSite=Strict"))
            assertTrue(polled.headers["set-cookie"].orEmpty().contains("; Secure;"))
            assertTrue(polled.headers["set-cookie"].orEmpty().contains("Path=/"))
            assertTrue(polled.headers["set-cookie"].orEmpty().contains("Max-Age=604800"))
            assertFalse(polled.headers["set-cookie"].orEmpty().contains("Domain="))
            val cookie = Regex("__Host-flip_viewer_session=([^;]+)")
                .find(polled.headers["set-cookie"].orEmpty())?.groupValues?.get(1)
            assertNotNull(cookie)
            val retriedPoll = request(
                clientContext, host, port, "POST", "/api/pair/status", authority, origin,
                form("requestId" to id),
            )
            assertEquals(polled.headers["set-cookie"], retriedPoll.headers["set-cookie"])

            val status = request(
                clientContext, host, port, "GET", "/api/status", authority, null, null,
                "__Host-flip_viewer_session=${requireNotNull(cookie)}",
            )
            assertTrue(status.status.startsWith("HTTP/1.1 200"))
            assertTrue(status.body.contains("\"readOnly\":true"))

            val logout = request(
                clientContext, host, port, "POST", "/api/logout", authority, origin, "",
                "__Host-flip_viewer_session=$cookie",
            )
            assertTrue(logout.status.startsWith("HTTP/1.1 204"))
            val afterLogout = request(
                clientContext, host, port, "GET", "/api/status", authority, null, null,
                "__Host-flip_viewer_session=$cookie",
            )
            assertTrue(afterLogout.status.startsWith("HTTP/1.1 401"))
            val replayedRequest = request(
                clientContext, host, port, "POST", "/api/pair/status", authority, origin,
                form("requestId" to id),
            )
            assertTrue(replayedRequest.status.startsWith("HTTP/1.1 409"))
        } finally {
            server.close()
        }
    }

    @Test
    fun apiRejectsUnknownHostAndOversizedForms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val code = pairing.createPairingCode()
        val server = ViewerHttpsServer.forCurrentLan(context, 0) { ViewerApi(pairing).install(this) }
        try {
            val port = runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = formatAuthority(host, port)
            val origin = "https://$authority"
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(CertificatePinTrustManager(identity.certificate)), null)
            }
            val badHost = request(
                clientContext, host, port, "POST", "/api/pair/request", "attacker.example:$port", origin,
                form("code" to code.digits, "deviceName" to "Laptop"),
            )
            assertTrue(badHost.status.startsWith("HTTP/1.1 421"))

            val secretInUrl = request(
                clientContext, host, port, "POST", "/api/pair/request?code=${code.digits}&deviceName=Laptop",
                authority, origin, null,
            )
            assertTrue(secretInUrl.status.startsWith("HTTP/1.1 400"))

            val oversized = request(
                clientContext, host, port, "POST", "/api/pair/request", authority, origin,
                "x".repeat(2_049),
            )
            assertTrue(oversized.status.startsWith("HTTP/1.1 413"))
            assertTrue(pairing.pendingRequests().isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun viewerPageAndLocalAssetsAreServedWithStrictHeaders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairing = ViewerPairingManager()
        val server = ViewerHttpsServer.forCurrentLan(context, 0) {
            ViewerApi(pairing).install(this)
            ViewerWebAssets(context).install(this)
        }
        try {
            val port = runBlocking { server.start() }
            val host = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val authority = formatAuthority(host, port)
            val identity = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(CertificatePinTrustManager(identity.certificate)), null)
            }

            val page = request(clientContext, host, port, "GET", "/", authority, null, null)
            assertTrue(page.status.startsWith("HTTP/1.1 200"))
            assertTrue(page.body.contains("Nhập mã đang hiển thị trong ứng dụng Flip Camera"))
            assertTrue(page.body.contains("Đang chờ khung hình"))
            assertTrue(page.body.contains("Bản ghi gần đây"))
            assertTrue(page.headers["content-security-policy"].orEmpty().contains("default-src 'none'"))
            assertTrue(page.headers["cache-control"].orEmpty().contains("no-store"))
            assertEquals("nosniff", page.headers["x-content-type-options"])

            val script = request(clientContext, host, port, "GET", "/viewer.js", authority, null, null)
            assertTrue(script.status.startsWith("HTTP/1.1 200"))
            assertTrue(script.body.contains("/api/pair/request"))
            assertTrue(script.body.contains("/api/live"))
            assertTrue(script.body.contains("/api/timeline"))
            assertTrue(script.body.contains("textContent"))
            assertFalse(script.body.contains("innerHTML"))
            assertFalse(script.body.contains("localStorage"))
            assertFalse(script.body.contains("sessionStorage"))
            assertFalse(script.body.contains(VIEWER_SESSION_COOKIE))
            val missing = request(clientContext, host, port, "GET", "/assets/unknown", authority, null, null)
            assertTrue(missing.status.startsWith("HTTP/1.1 404"))
            assertTrue(pairing.pendingRequests().isEmpty())
        } finally {
            server.close()
        }
    }

    private data class Response(
        val status: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun request(
        clientContext: SSLContext,
        peerHost: String,
        port: Int,
        method: String,
        path: String,
        hostHeader: String,
        origin: String?,
        body: String?,
        cookie: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val raw = Socket(InetAddress.getByName("127.0.0.1"), port)
        (clientContext.socketFactory.createSocket(raw, peerHost, port, true) as SSLSocket).use { client ->
            client.soTimeout = 5_000
            client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            client.startHandshake()
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                if (cookie != null) append("Cookie: $cookie\r\n")
                extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                if (bodyBytes != null) {
                    append("Content-Type: application/x-www-form-urlencoded\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                }
                append("\r\n")
            }
            client.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            if (bodyBytes != null) client.getOutputStream().write(bodyBytes)
            client.getOutputStream().flush()
            val rawResponse = client.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            val parts = rawResponse.split("\r\n\r\n", limit = 2)
            val lines = parts.first().split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
            }.toMap()
            return Response(lines.first(), headers, parts.getOrElse(1) { "" })
        }
    }

    private fun form(vararg fields: Pair<String, String>): String = fields.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
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

    private class CertificatePinTrustManager(private val expected: X509Certificate) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expected)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !chain.first().encoded.contentEquals(expected.encoded)) {
                throw CertificateException("Unexpected TLS certificate")
            }
        }
    }
}

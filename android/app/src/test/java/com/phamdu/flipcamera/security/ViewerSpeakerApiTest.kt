package com.phamdu.flipcamera.security

import com.phamdu.flipcamera.viewer.ViewerSpeakerHub
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class ViewerSpeakerApiTest {

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var serverPort: Int = 0
    private lateinit var pairing: ViewerPairingManager
    private lateinit var speakerHub: ViewerSpeakerHub
    private val receivedChunks = CopyOnWriteArrayList<ByteArray>()

    private val mockPlayer = object : ViewerSpeakerHub.AudioTrackPlayer {
        override fun init(): Boolean = true
        override fun play() {}
        override fun write(data: ByteArray): Int {
            receivedChunks.add(data)
            return data.size
        }
        override fun stop() {}
        override fun release() {}
    }

    @Before
    fun setUp() {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
        pairing = ViewerPairingManager()
        speakerHub = ViewerSpeakerHub(isEnabled = true, audioTrackFactory = { mockPlayer })
        val api = ViewerSpeakerApi(pairing, speakerHub)
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            api.install(this)
        }.start(wait = false)
        serverPort = kotlinx.coroutines.runBlocking { server!!.engine.resolvedConnectors().first().port }
    }

    @After
    fun tearDown() {
        server?.stop(50, 200)
        server = null
        speakerHub.close()
        receivedChunks.clear()
    }

    private fun approvedSessionToken(): String {
        val code = pairing.createPairingCode()
        val waiting = pairing.submitPairingCode(code.digits, "127.0.0.1", "Test viewer")
            as PairingSubmission.Waiting
        pairing.approve(waiting.requestId)
        return (pairing.poll(waiting.requestId) as PairingPoll.Approved).sessionToken
    }

    private fun openHttp(
        path: String,
        method: String,
        sessionToken: String? = null,
        origin: String? = null,
    ): HttpURLConnection {
        val url = URL("http://127.0.0.1:$serverPort$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.instanceFollowRedirects = false
        if (sessionToken != null) {
            conn.setRequestProperty("Cookie", "$VIEWER_SESSION_COOKIE=$sessionToken")
        }
        if (origin != null) {
            conn.setRequestProperty("Origin", origin)
        }
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream: InputStream? = if (code in 200..399) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return Pair(code, text)
    }

    @Test
    fun statusEndpointRequiresSession() {
        val conn = openHttp("/api/speaker/status", "GET")
        val (code, text) = readResponse(conn)
        assertEquals(401, code)
        assertTrue(text.contains("unauthorized"))
    }

    @Test
    fun statusEndpointReturnsEnabledAndActiveWithSession() {
        val token = approvedSessionToken()
        val conn = openHttp("/api/speaker/status", "GET", sessionToken = token)
        val (code, text) = readResponse(conn)
        assertEquals(200, code)
        assertTrue(text.contains("\"enabled\":true"))
        assertTrue(text.contains("\"active\":false"))
    }

    @Test
    fun postSpeakerRejectsUnauthenticated() {
        val conn = openHttp("/api/speaker", "POST")
        conn.doOutput = true
        conn.outputStream.use { it.write(ByteArray(100)) }
        val (code, text) = readResponse(conn)
        assertEquals(401, code)
        assertTrue(text.contains("unauthorized"))
    }

    @Test
    fun postSpeakerRejectsWhenSpeakerDisabled() {
        val token = approvedSessionToken()
        speakerHub.isEnabled = false
        val conn = openHttp("/api/speaker", "POST", sessionToken = token)
        conn.doOutput = true
        conn.outputStream.use { it.write(ByteArray(100)) }
        val (code, text) = readResponse(conn)
        assertEquals(503, code)
        assertTrue(text.contains("speaker_disabled"))
    }

    @Test
    fun postSpeakerRejectsInvalidOrigin() {
        val token = approvedSessionToken()
        val conn = openHttp("/api/speaker", "POST", sessionToken = token, origin = "http://evil.attacker.com")
        conn.doOutput = true
        conn.outputStream.use { it.write(ByteArray(100)) }
        val (code, text) = readResponse(conn)
        assertEquals(403, code)
        assertTrue(text.contains("origin_rejected"))
    }

    @Test
    fun postSpeakerRejectsEmptyOrSingleBytePayload() {
        val token = approvedSessionToken()
        val conn = openHttp("/api/speaker", "POST", sessionToken = token)
        conn.doOutput = true
        conn.outputStream.use { it.write(ByteArray(1)) }
        val (code, text) = readResponse(conn)
        assertEquals(400, code)
        assertTrue(text.contains("invalid_payload"))
    }

    @Test
    fun postSpeakerRejectsOddBytePayloadUnalignedPcm() {
        val token = approvedSessionToken()
        val conn = openHttp("/api/speaker", "POST", sessionToken = token)
        conn.doOutput = true
        // 101 bytes is odd: not aligned to 16-bit PCM (2 bytes per sample)
        conn.outputStream.use { it.write(ByteArray(101)) }
        val (code, text) = readResponse(conn)
        assertEquals(400, code)
        assertTrue(text.contains("unaligned_pcm"))
    }

    @Test
    fun postSpeakerRejectsPayloadExceedingLimit() {
        val token = approvedSessionToken()
        val conn = openHttp("/api/speaker", "POST", sessionToken = token)
        conn.doOutput = true
        // Exceeds MAX_CHUNK_BYTES (32_768)
        conn.outputStream.use { it.write(ByteArray(32_770)) }
        val (code, text) = readResponse(conn)
        assertEquals(413, code)
        assertTrue(text.contains("payload_too_large"))
    }

    @Test
    fun postSpeakerAcceptsValidPcmAndEnqueues() {
        val token = approvedSessionToken()
        val conn = openHttp("/api/speaker", "POST", sessionToken = token)
        conn.doOutput = true
        val payload = ByteArray(1024) { 1 }
        conn.outputStream.use { it.write(payload) }
        val (code, text) = readResponse(conn)
        assertEquals(200, code)
        assertTrue(text.contains("\"status\":\"ok\""))
        assertTrue(text.contains("\"bytes\":1024"))

        // Wait up to 1 second for background worker thread to process chunk
        var attempts = 0
        while (receivedChunks.isEmpty() && attempts < 20) {
            Thread.sleep(50)
            attempts++
        }
        assertEquals(1, receivedChunks.size)
        assertEquals(1024, receivedChunks[0].size)
    }

    @Test
    fun rateLimiterAllowsNormalTraffic() {
        val limiter = ViewerSpeakerApi.RateLimiter(maxBytesPerSec = 48_000, maxRequestsPerSec = 10)
        val token = "session-1"
        val now = 10_000L

        // Send 5 chunks of 4 KB = 20 KB total
        for (i in 0 until 5) {
            assertTrue(limiter.consume(token, 4096, now))
        }
    }

    @Test
    fun rateLimiterEnforcesByteLimit() {
        val limiter = ViewerSpeakerApi.RateLimiter(maxBytesPerSec = 48_000, maxRequestsPerSec = 10)
        val token = "session-1"
        val now = 10_000L

        // Send 40 KB
        assertTrue(limiter.consume(token, 40_000, now))
        // Try sending another 10 KB (total 50 KB > 48 KB limit)
        assertFalse(limiter.consume(token, 10_000, now))
    }

    @Test
    fun rateLimiterEnforcesRequestLimit() {
        val limiter = ViewerSpeakerApi.RateLimiter(maxBytesPerSec = 48_000, maxRequestsPerSec = 5)
        val token = "session-1"
        val now = 10_000L

        // Send 5 small requests
        for (i in 0 until 5) {
            assertTrue(limiter.consume(token, 100, now))
        }
        // 6th request in same second should be throttled
        assertFalse(limiter.consume(token, 100, now))
    }

    @Test
    fun rateLimiterResetsAfterOneSecond() {
        val limiter = ViewerSpeakerApi.RateLimiter(maxBytesPerSec = 48_000, maxRequestsPerSec = 5)
        val token = "session-1"
        val now = 10_000L

        for (i in 0 until 5) {
            assertTrue(limiter.consume(token, 100, now))
        }
        assertFalse(limiter.consume(token, 100, now))

        // Fast forward 1001 ms -> new window
        val later = now + 1001L
        assertTrue(limiter.consume(token, 100, later))
    }

    @Test
    fun rateLimiterIsolatesDifferentSessions() {
        val limiter = ViewerSpeakerApi.RateLimiter(maxBytesPerSec = 48_000, maxRequestsPerSec = 2)
        val now = 10_000L

        assertTrue(limiter.consume("token-a", 1000, now))
        assertTrue(limiter.consume("token-a", 1000, now))
        assertFalse("token-a exhausted", limiter.consume("token-a", 1000, now))

        // token-b should still have its full quota
        assertTrue("token-b fresh", limiter.consume("token-b", 1000, now))
        assertTrue("token-b 2nd", limiter.consume("token-b", 1000, now))
        assertFalse("token-b exhausted", limiter.consume("token-b", 1000, now))
    }

    @Test
    fun rateLimiterPrunesStaleSessions() {
        val limiter = ViewerSpeakerApi.RateLimiter(maxBytesPerSec = 48_000, maxRequestsPerSec = 10)
        val initialTime = 10_000L

        // Populate 10 sessions at initial time
        for (i in 1..10) {
            assertTrue(limiter.consume("old-session-$i", 100, initialTime))
        }
        assertEquals(10, limiter.sessionCount())

        // Advance time by 65,000ms (> STALE_SESSION_TIMEOUT_MS = 60_000ms)
        val laterTime = initialTime + 65_000L

        // Consuming with a new session triggers stale cleanup
        assertTrue(limiter.consume("new-session", 100, laterTime))

        // All 10 stale sessions must be evicted; only 'new-session' remains!
        assertEquals(1, limiter.sessionCount())
    }
}

package com.phamdu.flipcamera.security

import android.os.SystemClock
import com.phamdu.flipcamera.viewer.ViewerSpeakerHub
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticated speaker API allowing verified viewers to transmit audio (PCM 16-bit mono 16 kHz)
 * to the Flip3's speaker (Talkback).
 *
 * Enforces:
 * - Session verification via [VIEWER_SESSION_COOKIE].
 * - Origin verification (CSRF protection) if Origin header is present.
 * - Hardware / kill-switch check ([ViewerSpeakerHub.isEnabled]).
 * - Payload size limit: bounded channel read up to [MAX_CHUNK_BYTES] to prevent DoS/OOM on chunked streams.
 * - Sample alignment: PCM 16-bit requires even byte count (multiples of 2) to prevent screeching glitches.
 * - Rate limiting: max [MAX_BYTES_PER_SECOND] and [MAX_REQUESTS_PER_SECOND] per viewer session using
 *   monotonic clock [SystemClock.elapsedRealtime], with automatic eviction of stale sessions.
 */
internal class ViewerSpeakerApi(
    private val pairing: ViewerPairingManager,
    private val speakerHub: ViewerSpeakerHub,
    private val maxChunkBytes: Int = MAX_CHUNK_BYTES,
    private val maxBytesPerSecond: Int = MAX_BYTES_PER_SECOND,
    private val maxRequestsPerSecond: Int = MAX_REQUESTS_PER_SECOND,
    clock: () -> Long = ::defaultElapsedRealtime,
) {
    internal val rateLimiter = RateLimiter(maxBytesPerSecond, maxRequestsPerSecond, clock)

    fun install(application: Application) {
        application.routing {
            get("/api/speaker/status") {
                if (!call.requireViewerSession(pairing)) return@get
                call.noStore()
                call.respondText(
                    """{"enabled":${speakerHub.isEnabled},"active":${speakerHub.isPlaying}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }

            post("/api/speaker") {
                if (!call.requireViewerSession(pairing)) return@post
                val token = call.request.cookies[VIEWER_SESSION_COOKIE] ?: return@post

                if (call.request.headers[HttpHeaders.Origin] != null && !call.hasSameSecureOrigin()) {
                    call.respondSpeakerError(HttpStatusCode.Forbidden, "origin_rejected")
                    return@post
                }

                if (!speakerHub.isEnabled) {
                    call.respondSpeakerError(HttpStatusCode.ServiceUnavailable, "speaker_disabled")
                    return@post
                }

                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (contentLength != null && contentLength > maxChunkBytes) {
                    call.respondSpeakerError(HttpStatusCode.PayloadTooLarge, "payload_too_large")
                    return@post
                }

                // Read body with bounded streaming to prevent DoS/OOM even with Transfer-Encoding: chunked.
                val payload = call.receiveBoundedPayload(maxChunkBytes) ?: return@post

                if (payload.size < 2) {
                    call.respondSpeakerError(HttpStatusCode.BadRequest, "invalid_payload")
                    return@post
                }

                // PCM 16-bit mono requires 2 bytes per sample (even byte count).
                if (payload.size % 2 != 0) {
                    call.respondSpeakerError(HttpStatusCode.BadRequest, "unaligned_pcm")
                    return@post
                }

                if (!rateLimiter.consume(token, payload.size)) {
                    call.respondSpeakerError(HttpStatusCode.TooManyRequests, "speaker_rate_limited")
                    return@post
                }

                val accepted = speakerHub.playChunk(payload)
                if (!accepted) {
                    call.respondSpeakerError(HttpStatusCode.ServiceUnavailable, "speaker_disabled")
                    return@post
                }

                call.noStore()
                call.respondText(
                    """{"status":"ok","bytes":${payload.size}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }
        }
    }

    private suspend fun ApplicationCall.receiveBoundedPayload(maxBytes: Int): ByteArray? {
        val channel = request.receiveChannel()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var total = 0
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                respondSpeakerError(HttpStatusCode.PayloadTooLarge, "payload_too_large")
                return null
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun ApplicationCall.noStore() {
        response.headers.append(HttpHeaders.CacheControl, "private, no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        response.headers.append("Referrer-Policy", "no-referrer")
    }

    private suspend fun ApplicationCall.respondSpeakerError(status: HttpStatusCode, code: String) {
        noStore()
        respondText("""{"error":"$code"}""", ContentType.Application.Json, status)
    }

    internal class RateLimiter(
        private val maxBytesPerSec: Int,
        private val maxRequestsPerSec: Int,
        private val clock: () -> Long = ::defaultElapsedRealtime,
    ) {
        private val sessions = ConcurrentHashMap<String, TokenBucket>()

        fun consume(token: String, bytes: Int, now: Long = clock()): Boolean {
            cleanupStaleSessions(now)
            val bucket = sessions.computeIfAbsent(token) { TokenBucket(now) }
            return bucket.consume(bytes, now, maxBytesPerSec, maxRequestsPerSec)
        }

        fun reset() {
            sessions.clear()
        }

        internal fun sessionCount(): Int = sessions.size

        private fun cleanupStaleSessions(now: Long) {
            if (sessions.size > 8) {
                val cutoff = now - STALE_SESSION_TIMEOUT_MS
                sessions.entries.removeIf { it.value.isStale(cutoff) }
            }
        }

        private class TokenBucket(initialNow: Long) {
            private var windowStart: Long = initialNow
            private var bytesInWindow: Int = 0
            private var requestsInWindow: Int = 0
            @Volatile var lastSeen: Long = initialNow
                private set
            private val lock = Any()

            fun consume(bytes: Int, now: Long, maxBytes: Int, maxRequests: Int): Boolean = synchronized(lock) {
                lastSeen = now
                if (now - windowStart >= 1000L || now < windowStart) {
                    windowStart = now
                    bytesInWindow = 0
                    requestsInWindow = 0
                }
                if (requestsInWindow + 1 > maxRequests || bytesInWindow + bytes > maxBytes) {
                    return false
                }
                requestsInWindow += 1
                bytesInWindow += bytes
                return true
            }

            fun isStale(cutoff: Long): Boolean = lastSeen < cutoff
        }

        companion object {
            const val STALE_SESSION_TIMEOUT_MS = 60_000L
        }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 32_768 // 32 KB (~1.024s at 16kHz 16-bit mono)
        const val MAX_BYTES_PER_SECOND = 48_000 // 48 KB/s (1.5x real-time burst allowance)
        const val MAX_REQUESTS_PER_SECOND = 12 // ~12 requests/s

        private fun defaultElapsedRealtime(): Long =
            runCatching { SystemClock.elapsedRealtime() }
                .getOrElse { System.nanoTime() / 1_000_000L }
    }
}

package com.phamdu.flipcamera.security

import com.phamdu.flipcamera.viewer.ViewerFrameHub
import com.phamdu.flipcamera.viewer.ViewerJpegFrame
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.nio.charset.StandardCharsets

/** Authenticated, bounded MJPEG delivery for read-only viewers. */
internal class ViewerLiveApi(
    private val pairing: ViewerPairingManager,
    private val frameHub: ViewerFrameHub,
    private val maxStreams: Int = MAX_STREAMS,
) {
    private val activeStreams = AtomicInteger()
    internal val stats = LiveStreamStats()

    internal val activeStreamCount: Int
        get() = activeStreams.get()

    fun install(application: Application) {
        application.routing {
            get("/api/live") {
                if (!call.requireViewerSession(pairing)) return@get
                val token = call.request.cookies[VIEWER_SESSION_COOKIE] ?: return@get

                if (!tryAcquireStreamSlot()) {
                    call.respondStreamError(HttpStatusCode.TooManyRequests, "viewer_stream_limit")
                    return@get
                }
                val released = AtomicBoolean(false)
                val releaseSlot = {
                    if (released.compareAndSet(false, true)) activeStreams.decrementAndGet()
                }

                val initialFrame = try {
                    withTimeoutOrNull(FIRST_FRAME_WAIT_MS) {
                        frameHub.subscribeFrames().first()
                    }
                } catch (_: NoSuchElementException) {
                    null
                } catch (failure: Throwable) {
                    releaseSlot()
                    throw failure
                }
                if (initialFrame == null) {
                    releaseSlot()
                    call.respondStreamError(HttpStatusCode.ServiceUnavailable, "camera_unavailable")
                    return@get
                }

                try {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val contentType: ContentType =
                            ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY")
                        override val status: HttpStatusCode = HttpStatusCode.OK
                        override val headers: Headers = Headers.build {
                            append(HttpHeaders.CacheControl, "private, no-store, no-cache, max-age=0")
                            append(HttpHeaders.Pragma, "no-cache")
                            append("X-Content-Type-Options", "nosniff")
                            append("Referrer-Policy", "no-referrer")
                            append("X-Accel-Buffering", "no")
                        }

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            try {
                                coroutineScope {
                                    if (pairing.authenticate(token) == null) return@coroutineScope
                                    val firstFrame = frameHub.latestFrame(MAX_FRAME_AGE_MS)
                                        ?: return@coroutineScope
                                    if (pairing.authenticate(token) == null) return@coroutineScope
                                    writeFrame(channel, firstFrame, token)
                                    channel.flush()

                                    val frames = frameHub.subscribeFrames()
                                        .buffer(Channel.CONFLATED)
                                        .produceIn(this)
                                    try {
                                        var lastSequence = firstFrame.sequence
                                        var lastFrameElapsed = android.os.SystemClock.elapsedRealtime()
                                        while (currentCoroutineContext().isActive) {
                                            if (pairing.authenticate(token) == null) break
                                            val incoming = withTimeoutOrNull(FRAME_WAIT_MS) {
                                                frames.receiveCatching()
                                            }
                                            if (incoming == null) {
                                                if (android.os.SystemClock.elapsedRealtime() - lastFrameElapsed >=
                                                    NO_FRAME_TIMEOUT_MS
                                                ) break
                                                continue
                                            }
                                            val frame = incoming.getOrNull() ?: break
                                            if (frame.sequence <= lastSequence) continue
                                            if (frame.capturedAtUtc < System.currentTimeMillis() - MAX_FRAME_AGE_MS) continue
                                            if (pairing.authenticate(token) == null) break
                                            writeFrame(channel, frame, token)
                                            channel.flush()
                                            lastSequence = frame.sequence
                                            lastFrameElapsed = android.os.SystemClock.elapsedRealtime()
                                        }
                                    } finally {
                                        frames.cancel()
                                    }
                                }
                            } finally {
                                stats.clear(token)
                                releaseSlot()
                            }
                        }
                    })
                } catch (failure: Throwable) {
                    releaseSlot()
                    throw failure
                }
            }
        }
    }

    private fun tryAcquireStreamSlot(): Boolean {
        while (true) {
            val current = activeStreams.get()
            if (current >= maxStreams) return false
            if (activeStreams.compareAndSet(current, current + 1)) return true
        }
    }

    private suspend fun writeFrame(channel: ByteWriteChannel, frame: ViewerJpegFrame, token: String) {
        val header = (
            "--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.jpegBytes.size}\r\n" +
                "X-Frame-Sequence: ${frame.sequence}\r\n" +
                "X-Captured-At: ${frame.capturedAtUtc}\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        channel.writeFully(header, 0, header.size)
        channel.writeFully(frame.jpegBytes, 0, frame.jpegBytes.size)
        channel.writeFully(CRLF, 0, CRLF.size)
        stats.record(token, header.size + frame.jpegBytes.size + CRLF.size, android.os.SystemClock.elapsedRealtime())
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondStreamError(
        status: HttpStatusCode,
        code: String,
    ) {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        response.headers.append("Referrer-Policy", "no-referrer")
        respondText("""{"error":"$code"}""", ContentType.Application.Json, status)
    }

    companion object {
        const val BOUNDARY = "flipframe"
        const val MAX_STREAMS = 16
        const val MAX_FRAME_AGE_MS = 2_000L
        const val FIRST_FRAME_WAIT_MS = 2_000L
        const val FRAME_WAIT_MS = 5_000L
        const val NO_FRAME_TIMEOUT_MS = 15_000L
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

package com.phamdu.flipcamera.security

import com.phamdu.flipcamera.viewer.ViewerAudioHub
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** Authenticated, read-only live audio (PCM s16le mono 16 kHz). Off unless the user enabled audio. */
internal class ViewerAudioApi(
    private val pairing: ViewerPairingManager,
    private val audioHub: ViewerAudioHub,
    private val maxStreams: Int = MAX_STREAMS,
) {
    private val activeStreams = AtomicInteger()

    fun install(application: Application) {
        application.routing {
            get("/api/live-audio") {
                if (!call.requireViewerSession(pairing)) return@get
                val token = call.request.cookies[VIEWER_SESSION_COOKIE] ?: return@get
                if (activeStreams.incrementAndGet() > maxStreams) {
                    activeStreams.decrementAndGet()
                    call.respondAudioError(HttpStatusCode.TooManyRequests, "audio_stream_limit")
                    return@get
                }
                val released = AtomicBoolean(false)
                val releaseSlot = { if (released.compareAndSet(false, true)) activeStreams.decrementAndGet() }
                val channel = audioHub.subscribe()
                if (channel == null) {
                    releaseSlot()
                    call.respondAudioError(HttpStatusCode.ServiceUnavailable, "audio_unavailable")
                    return@get
                }
                try {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val contentType: ContentType = ContentType.Application.OctetStream
                        override val status: HttpStatusCode = HttpStatusCode.OK
                        override val headers: Headers = Headers.build {
                            append(HttpHeaders.CacheControl, "private, no-store, no-cache, max-age=0")
                            append("X-Content-Type-Options", "nosniff")
                            append("Referrer-Policy", "no-referrer")
                            append("X-Accel-Buffering", "no")
                            append("X-Audio-Format", "pcm_s16le;rate=${ViewerAudioHub.SAMPLE_RATE};channels=1")
                        }

                        override suspend fun writeTo(channel2: ByteWriteChannel) {
                            try {
                                while (currentCoroutineContext().isActive) {
                                    if (pairing.authenticate(token) == null) break
                                    val result = withTimeoutOrNull(WAIT_MS) { channel.receiveCatching() } ?: continue
                                    val chunk = result.getOrNull() ?: break
                                    channel2.writeFully(chunk, 0, chunk.size)
                                    channel2.flush()
                                }
                            } finally {
                                audioHub.unsubscribe(channel)
                                releaseSlot()
                            }
                        }
                    })
                } catch (failure: Throwable) {
                    audioHub.unsubscribe(channel)
                    releaseSlot()
                    throw failure
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondAudioError(
        status: HttpStatusCode,
        code: String,
    ) {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        respondText("""{"error":"$code"}""", ContentType.Application.Json, status)
    }

    companion object {
        const val MAX_STREAMS = 16
        const val WAIT_MS = 3_000L
    }
}

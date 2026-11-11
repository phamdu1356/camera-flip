package com.phamdu.flipcamera.viewer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One immutable, already-compressed preview frame shared by active viewer requests. */
internal data class ViewerJpegFrame(
    val sequence: Long,
    val capturedAtUtc: Long,
    val jpegBytes: ByteArray,
    internal val publishedAtElapsedMs: Long,
)

/**
 * Latest-only fan-out for remote previews. Each browser gets one conflated slot, so a slow
 * connection cannot accumulate frames or hold up CameraX's analyzer.
 */
internal class ViewerFrameHub {
    private val sequence = AtomicLong()
    private val latest = AtomicReference<ViewerJpegFrame?>(null)
    private val subscribers = ConcurrentHashMap.newKeySet<Channel<ViewerJpegFrame>>()

    val hasSubscribers: Boolean
        get() = subscribers.isNotEmpty()

    internal val subscriberCount: Int
        get() = subscribers.size

    fun publishJpeg(bytes: ByteArray, capturedAtUtc: Long) {
        if (bytes.isEmpty() || subscribers.isEmpty()) return
        val frame = ViewerJpegFrame(
            sequence = sequence.incrementAndGet(),
            capturedAtUtc = capturedAtUtc,
            jpegBytes = bytes.copyOf(),
            publishedAtElapsedMs = elapsedRealtimeMs(),
        )
        latest.set(frame)
        subscribers.forEach { it.trySend(frame) }
    }

    /** Returns a fresh frame for stream preflight; a stale frame is never replayed. */
    fun latestFrame(maxAgeMillis: Long = DEFAULT_FRESHNESS_MS): ViewerJpegFrame? {
        if (maxAgeMillis < 0) return null
        val frame = latest.get() ?: return null
        val age = elapsedRealtimeMs() - frame.publishedAtElapsedMs
        return frame.takeIf { age in 0..maxAgeMillis }
    }

    /**
     * Registers before reading the replay frame, closing the subscribe/publish race. A duplicate
     * replay can occur in that race, so sequence numbers are de-duplicated within each flow.
     */
    fun subscribeFrames(): Flow<ViewerJpegFrame> = flow {
        val channel = Channel<ViewerJpegFrame>(Channel.CONFLATED)
        subscribers += channel
        var lastSequence = 0L
        try {
            latestFrame()?.let { frame ->
                lastSequence = frame.sequence
                emit(frame)
            }
            for (frame in channel) {
                if (frame.sequence <= lastSequence) continue
                lastSequence = frame.sequence
                emit(frame)
            }
        } finally {
            subscribers -= channel
            channel.cancel()
        }
    }

    /** Drops stale imagery and terminates active streams when the camera stops. */
    fun clear() {
        latest.set(null)
        subscribers.forEach { it.cancel() }
        subscribers.clear()
    }

    private fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val DEFAULT_FRESHNESS_MS = 2_000L
    }
}

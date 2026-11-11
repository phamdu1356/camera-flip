package com.phamdu.flipcamera

import com.phamdu.flipcamera.data.DetectedMotionInterval
import com.phamdu.flipcamera.data.MotionDetector
import com.phamdu.flipcamera.data.MotionDetectorSnapshot
import com.phamdu.flipcamera.data.MotionEventEntity
import java.util.UUID

/** Camera-start metadata captured by the service. Motion timestamps are monotonic elapsed time. */
internal data class MotionCaptureContext(
    val sessionId: String?,
    val segmentId: String?,
    val utcOffsetMs: Long,
)

/**
 * Small service-boundary coordinator for motion capture. It ignores frames until CameraX reports
 * Start, closes any confirmed interval when recording stops/finalizes, and treats event/thumbnail
 * persistence as best effort so a metadata failure cannot change recorder state.
 *
 * The service calls these methods on its single-thread analysis executor. No frames are retained;
 * at most one bounded grayscale baseline and one <=512 KiB start-frame thumbnail are held.
 */
internal class MotionCaptureOrchestrator(
    private val detector: MotionDetector = MotionDetector(),
    private val createEventId: () -> String = { UUID.randomUUID().toString() },
    private val persistEvent: (MotionEventEntity, ByteArray?) -> Unit,
) {
    private var prepared = false
    private var capturing = false
    private var context: MotionCaptureContext? = null
    private var activeEventId: String? = null
    @Volatile private var activeStartUtc: Long? = null
    private var activeSessionId: String? = null
    private var activeSegmentId: String? = null
    private var activeThumbnail: ByteArray? = null

    internal val isCapturing: Boolean
        get() = capturing

    /** Read-only UTC start of a confirmed interval still in progress, safe to read from other threads. */
    internal val activeIntervalStartUtc: Long?
        get() = activeStartUtc

    /** Reset the detector in preparation for a user recording request; this is not CameraX Start. */
    fun prepare() {
        capturing = false
        prepared = true
        context = null
        detector.reset()
        clearActiveInterval()
    }

    /** Enable sampling only when the CameraX recording Start event arrives. */
    fun recordingStarted(context: MotionCaptureContext) {
        if (!prepared) prepare()
        // CameraX may emit a new Start for the next segment without ending the capture session.
        // Update context for future intervals; beginInterval has already snapshotted the context
        // of an interval in progress, so it remains linked to the segment where it began.
        this.context = context
        if (capturing) return
        capturing = true
    }

    /** Returns null before CameraX Start, after stop, or for an ignored out-of-order sample. */
    fun sample(timestampMs: Long, luminance: ByteArray): MotionDetectorSnapshot? {
        if (!prepared || !capturing) return null
        val update = detector.sample(timestampMs, luminance) ?: return null
        update.completedInterval?.let(::completeInterval)
        if (update.started) beginInterval(update)
        return update
    }

    /** Retain only a bounded JPEG captured from the frame that confirmed the interval start. */
    fun setActiveThumbnail(jpeg: ByteArray?) {
        if (activeEventId == null || jpeg == null || jpeg.size !in MIN_JPEG_BYTES..MAX_JPEG_BYTES) return
        if (jpeg[0] != JPEG_START_0 || jpeg[1] != JPEG_START_1 ||
            jpeg[jpeg.lastIndex - 1] != JPEG_END_0 || jpeg.last() != JPEG_END_1
        ) return
        activeThumbnail = jpeg.copyOf()
    }

    /** End an interval after an image transform change, keeping the current recording context. */
    fun resetBaseline(): MotionEventEntity? {
        val interval = detector.finish()
        val event = interval?.let(::completeInterval)
        detector.reset()
        clearActiveInterval()
        return event
    }

    /** Idempotent for stop followed by CameraX Finalize; event persistence is isolated from capture. */
    fun finish(): MotionEventEntity? {
        capturing = false
        prepared = false
        val interval = detector.finish()
        val event = interval?.let(::completeInterval)
        detector.reset()
        context = null
        clearActiveInterval()
        return event
    }

    private fun beginInterval(snapshot: MotionDetectorSnapshot) {
        val captureContext = context ?: return
        val startElapsed = snapshot.activeStartMs ?: snapshot.timestampMs
        activeEventId = createEventId()
        activeStartUtc = (startElapsed + captureContext.utcOffsetMs).coerceAtLeast(0L)
        activeSessionId = captureContext.sessionId
        activeSegmentId = captureContext.segmentId
        activeThumbnail = null
    }

    private fun completeInterval(interval: DetectedMotionInterval): MotionEventEntity? {
        val id = activeEventId
        val startUtc = activeStartUtc
        if (id == null || startUtc == null) {
            clearActiveInterval()
            return null
        }
        val duration = (interval.endMs - interval.startMs).coerceAtLeast(1L)
        val event = runCatching {
            MotionEventEntity(
                id = id,
                sessionId = activeSessionId,
                segmentId = activeSegmentId,
                startUtc = startUtc,
                endUtc = startUtc + duration,
                peakScore = interval.peakChangedFraction.coerceIn(0f, 1f),
            )
        }.getOrNull()
        val thumbnail = activeThumbnail
        clearActiveInterval()
        if (event != null) runCatching { persistEvent(event, thumbnail) }
        return event
    }

    private fun clearActiveInterval() {
        activeEventId = null
        activeStartUtc = null
        activeSessionId = null
        activeSegmentId = null
        activeThumbnail = null
    }

    private companion object {
        const val MIN_JPEG_BYTES = 4
        const val MAX_JPEG_BYTES = 512 * 1024
        const val JPEG_START_0: Byte = 0xff.toByte()
        const val JPEG_START_1: Byte = 0xd8.toByte()
        const val JPEG_END_0: Byte = 0xff.toByte()
        const val JPEG_END_1: Byte = 0xd9.toByte()
    }
}

package com.phamdu.flipcamera.data

import kotlin.math.abs
import kotlin.math.max

/** A completed half-open interval, in exactly the timestamp domain passed to [MotionDetector.sample]. */
data class DetectedMotionInterval(
    val startMs: Long,
    val endMs: Long,
    val peakChangedFraction: Float,
) {
    init {
        require(startMs >= 0L && endMs > startMs)
        require(peakChangedFraction.isFinite() && peakChangedFraction in 0f..1f)
    }
}

/** Per-frame state after hysteresis has been applied. */
data class MotionDetectorSnapshot(
    val timestampMs: Long,
    val changedFraction: Float,
    /** True when this frame crossed the instantaneous pixel-change threshold. */
    val motionDetected: Boolean,
    /** Debounced interval state; brief quiet/motion gaps remain in the same interval. */
    val active: Boolean,
    val activeStartMs: Long?,
    /** True on the frame that confirms a new interval (including a max-duration split). */
    val started: Boolean = false,
    /** Non-null only on the sample that closes or splits an interval. */
    val completedInterval: DetectedMotionInterval? = null,
) {
    init {
        require(timestampMs >= 0L)
        require(changedFraction.isFinite() && changedFraction in 0f..1f)
        require((active && activeStartMs != null) || (!active && activeStartMs == null))
    }
}

/**
 * Bounded, deterministic motion detector for a small grayscale frame sampled from CameraX.
 *
 * This class has no Android, wall-clock, thread, or storage dependencies. Supply strictly
 * increasing timestamps from a monotonic clock (for example elapsedRealtime); map returned
 * boundaries to UTC in the caller. The detector copies at most width*height bytes and never
 * queues frames. A frame should be downsampled before calling this method.
 */
class MotionDetector(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val pixelDifferenceThreshold: Int = 18,
    private val changedFractionThreshold: Float = 0.06f,
    private val startFrames: Int = 2,
    private val quietFramesToEnd: Int = 4,
    private val sampleIntervalMs: Long = 500L,
    private val maxEventDurationMs: Long = 120_000L,
    private val maxSampleGapMs: Long = 5_000L,
) {
    private val pixelCount: Int
    private val differenceHistogram = IntArray(511)
    private var previousFrame: ByteArray? = null
    private var lastTimestampMs: Long? = null
    private var candidateStartMs = 0L
    private var candidateCount = 0
    private var candidatePeak = 0f
    private var activeEvent: ActiveEvent? = null

    private data class ActiveEvent(
        val startMs: Long,
        var lastMotionMs: Long,
        var peak: Float,
        var quietFrames: Int = 0,
    )

    private data class Transition(
        val completed: DetectedMotionInterval? = null,
        val started: Boolean = false,
    )

    init {
        require(width > 0 && height > 0)
        val count = width.toLong() * height.toLong()
        require(count <= MAX_PIXELS) { "Motion frames are limited to $MAX_PIXELS grayscale pixels" }
        pixelCount = count.toInt()
        require(pixelDifferenceThreshold in 1..255)
        require(changedFractionThreshold.isFinite() && changedFractionThreshold in 0.0001f..1f)
        require(startFrames in 1..MAX_FRAME_COUNT)
        require(quietFramesToEnd in 1..MAX_FRAME_COUNT)
        require(sampleIntervalMs in 1L..MAX_SAMPLE_INTERVAL_MS)
        require(maxEventDurationMs >= sampleIntervalMs)
        require(maxSampleGapMs >= sampleIntervalMs)
    }

    /**
     * Consume one already-downsampled luminance plane. The return contains the current debounced
     * state and, on the closing/splitting frame, the completed interval. Out-of-order or duplicate
     * timestamps are ignored without changing detector state.
     */
    fun sample(timestampMs: Long, luminance: ByteArray): MotionDetectorSnapshot? {
        require(timestampMs >= 0L)
        require(luminance.size == pixelCount) { "Expected $pixelCount grayscale pixels" }

        val lastTimestamp = lastTimestampMs
        if (lastTimestamp != null && timestampMs <= lastTimestamp) return null

        val previous = previousFrame
        if (previous == null) {
            previousFrame = luminance.copyOf()
            lastTimestampMs = timestampMs
            return snapshot(timestampMs, changedFraction = 0f, motionDetected = false)
        }

        // A long analyzer/camera pause splits intervals and resets the baseline so a stale frame
        // is never compared against a much later image.
        if (lastTimestamp != null && timestampMs - lastTimestamp > maxSampleGapMs) {
            val closed = closeActive()
            clearCandidate()
            previousFrame = luminance.copyOf()
            lastTimestampMs = timestampMs
            return snapshot(timestampMs, 0f, motionDetected = false, completed = closed)
        }

        java.util.Arrays.fill(differenceHistogram, 0)
        for (index in 0 until pixelCount) {
            val current = luminance[index].toInt() and 0xff
            val before = previous[index].toInt() and 0xff
            differenceHistogram[current - before + HISTOGRAM_OFFSET]++
        }
        // A median global luminance shift compensates auto-exposure/flicker while retaining
        // local changes as motion. The median prevents a moving minority from dominating.
        val medianTarget = (pixelCount - 1) / 2
        var cumulative = 0
        var globalShift = 0
        for (index in differenceHistogram.indices) {
            cumulative += differenceHistogram[index]
            if (cumulative > medianTarget) {
                globalShift = index - HISTOGRAM_OFFSET
                break
            }
        }
        var changedPixels = 0
        for (index in 0 until pixelCount) {
            val current = luminance[index].toInt() and 0xff
            val before = previous[index].toInt() and 0xff
            if (abs((current - before) - globalShift) >= pixelDifferenceThreshold) changedPixels++
        }
        val changedFraction = changedPixels.toFloat() / pixelCount.toFloat()
        previousFrame = luminance.copyOf()
        lastTimestampMs = timestampMs

        val motionDetected = changedFraction >= changedFractionThreshold
        val transition = if (motionDetected) {
            consumeMotion(timestampMs, changedFraction)
        } else {
            consumeQuiet(timestampMs)
        }
        return snapshot(
            timestampMs = timestampMs,
            changedFraction = changedFraction,
            motionDetected = motionDetected,
            started = transition.started,
            completed = transition.completed,
        )
    }

    /** Close the current interval when recording stops; also discard an unconfirmed candidate. */
    fun finish(): DetectedMotionInterval? {
        clearCandidate()
        return closeActive()
    }

    /** Clear all frame/timing state, for example before opening a new camera session. */
    fun reset() {
        previousFrame = null
        lastTimestampMs = null
        clearCandidate()
        activeEvent = null
    }

    private fun consumeMotion(timestampMs: Long, score: Float): Transition {
        val active = activeEvent
        if (active != null) {
            if (timestampMs - active.startMs >= maxEventDurationMs) {
                val completed = closeAt(active, active.startMs + maxEventDurationMs)
                // Continuous motion begins a new bounded interval at this frame.
                activeEvent = ActiveEvent(timestampMs, timestampMs, score)
                return Transition(completed = completed, started = true)
            }
            active.lastMotionMs = timestampMs
            active.peak = max(active.peak, score)
            active.quietFrames = 0
            return Transition()
        }

        if (candidateCount == 0) candidateStartMs = timestampMs
        candidateCount++
        candidatePeak = max(candidatePeak, score)
        if (candidateCount < startFrames) return Transition()

        activeEvent = ActiveEvent(
            startMs = candidateStartMs,
            lastMotionMs = timestampMs,
            peak = candidatePeak,
        )
        clearCandidate()
        return Transition(started = true)
    }

    private fun consumeQuiet(timestampMs: Long): Transition {
        val active = activeEvent ?: run {
            clearCandidate()
            return Transition()
        }

        active.quietFrames++
        if (timestampMs - active.startMs >= maxEventDurationMs) {
            return Transition(completed = closeAt(active, active.startMs + maxEventDurationMs))
        }
        if (active.quietFrames >= quietFramesToEnd) return Transition(completed = closeActive())
        return Transition()
    }

    private fun snapshot(
        timestampMs: Long,
        changedFraction: Float,
        motionDetected: Boolean,
        started: Boolean = false,
        completed: DetectedMotionInterval? = null,
    ): MotionDetectorSnapshot {
        val active = activeEvent
        return MotionDetectorSnapshot(
            timestampMs = timestampMs,
            changedFraction = changedFraction,
            motionDetected = motionDetected,
            active = active != null,
            activeStartMs = active?.startMs,
            started = started,
            completedInterval = completed,
        )
    }

    private fun closeActive(): DetectedMotionInterval? {
        val active = activeEvent ?: return null
        return closeAt(active, minOf(active.startMs + maxEventDurationMs, active.lastMotionMs + sampleIntervalMs))
    }

    private fun closeAt(active: ActiveEvent, proposedEndMs: Long): DetectedMotionInterval {
        activeEvent = null
        return DetectedMotionInterval(
            startMs = active.startMs,
            endMs = max(active.startMs + 1L, proposedEndMs),
            peakChangedFraction = active.peak,
        )
    }

    private fun clearCandidate() {
        candidateCount = 0
        candidatePeak = 0f
        candidateStartMs = 0L
    }

    companion object {
        const val DEFAULT_WIDTH = 80
        const val DEFAULT_HEIGHT = 45
        private const val MAX_PIXELS = 9_216L // 128x72; bounds both allocation and per-frame work.
        private const val MAX_FRAME_COUNT = 120
        private const val MAX_SAMPLE_INTERVAL_MS = 60_000L
        private const val HISTOGRAM_OFFSET = 255
    }
}

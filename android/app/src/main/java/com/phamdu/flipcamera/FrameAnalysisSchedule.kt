package com.phamdu.flipcamera

/** Live follows the camera's frame rate; motion sampling stays independently bounded. */
internal class FrameAnalysisSchedule(private val motionIntervalMs: Long = 500L) {
    private var lastMotionAt = -1L

    init {
        require(motionIntervalMs > 0L)
    }

    fun plan(
        nowElapsedMs: Long,
        hasViewers: Boolean,
        motionEnabled: Boolean,
        intervalOverrideMs: Long? = null,
    ): FrameAnalysisWork {
        val interval = (intervalOverrideMs ?: motionIntervalMs).coerceAtLeast(100L)
        val sampleMotion = motionEnabled &&
            (lastMotionAt < 0L || nowElapsedMs - lastMotionAt >= interval)
        if (sampleMotion) lastMotionAt = nowElapsedMs
        return FrameAnalysisWork(publishLive = hasViewers, sampleMotion = sampleMotion)
    }

    fun resetMotion() {
        lastMotionAt = -1L
    }
}

internal data class FrameAnalysisWork(val publishLive: Boolean, val sampleMotion: Boolean)

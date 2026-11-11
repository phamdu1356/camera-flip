package com.phamdu.flipcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnalysisScheduleTest {
    @Test
    fun fifteenCameraFramesProduceFifteenLiveFramesButOnlyTwoMotionSamples() {
        val schedule = FrameAnalysisSchedule()
        val work = (0 until 15).map { frame ->
            schedule.plan(frame * 1_000L / 15, hasViewers = true, motionEnabled = true)
        }
        assertEquals(15, work.count { it.publishLive })
        assertEquals(2, work.count { it.sampleMotion })
    }

    @Test
    fun higherCameraRateDoesNotIncreaseMotionSamplingOrEncodeWithoutViewers() {
        val schedule = FrameAnalysisSchedule()
        val work = (0 until 60).map { frame ->
            schedule.plan(frame * 1_000L / 60, hasViewers = false, motionEnabled = true)
        }
        assertEquals(0, work.count { it.publishLive })
        assertEquals(2, work.count { it.sampleMotion })
        assertFalse(schedule.plan(1_001L, hasViewers = false, motionEnabled = false).sampleMotion)
    }

    @Test
    fun newRecordingOrOrientationBaselineCanSampleImmediately() {
        val schedule = FrameAnalysisSchedule()
        assertTrue(schedule.plan(100L, hasViewers = false, motionEnabled = true).sampleMotion)
        assertFalse(schedule.plan(200L, hasViewers = true, motionEnabled = true).sampleMotion)
        schedule.resetMotion()
        assertTrue(schedule.plan(201L, hasViewers = true, motionEnabled = true).sampleMotion)
    }

    @Test
    fun intervalOverrideThrottlesMotionSamplingUnderThermalLoad() {
        val schedule = FrameAnalysisSchedule(500L)
        // With 1500ms override, only 1 sample should be taken across 1000ms
        assertTrue(schedule.plan(0L, hasViewers = false, motionEnabled = true, intervalOverrideMs = 1500L).sampleMotion)
        assertFalse(schedule.plan(500L, hasViewers = false, motionEnabled = true, intervalOverrideMs = 1500L).sampleMotion)
        assertFalse(schedule.plan(1000L, hasViewers = false, motionEnabled = true, intervalOverrideMs = 1500L).sampleMotion)
        assertTrue(schedule.plan(1500L, hasViewers = false, motionEnabled = true, intervalOverrideMs = 1500L).sampleMotion)
    }
}

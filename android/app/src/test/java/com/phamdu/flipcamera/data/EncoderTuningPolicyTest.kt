package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderTuningPolicyTest {
    @Test
    fun unknownOrMissingCodecFallsBackToH264() {
        assertEquals(VideoCodecMode.H264, parseVideoCodecMode(null))
        assertEquals(VideoCodecMode.H264, parseVideoCodecMode(""))
        assertEquals(VideoCodecMode.H264, parseVideoCodecMode("hevc"))
        assertEquals(VideoCodecMode.H264, parseVideoCodecMode("VP9"))
        assertEquals(VideoCodecMode.HEVC, parseVideoCodecMode("HEVC"))
    }

    @Test
    fun defaultsKeepCameraXBehaviour() {
        val plan = resolveEncoderTuning(VideoCodecMode.H264, tuning = false, hasHevcEncoder = true)
        assertEquals(MIME_AVC, plan.mime)
        assertNull(plan.frameRate)
        assertNull(plan.iFrameSeconds)
        assertTrue(plan.isDefault)
    }

    @Test
    fun tuningSetsEncoderFpsAndIFrameInterval() {
        val plan = resolveEncoderTuning(VideoCodecMode.H264, tuning = true, hasHevcEncoder = false)
        assertEquals(MIME_AVC, plan.mime)
        assertEquals(15, plan.frameRate)
        assertEquals(2, plan.iFrameSeconds)
        assertFalse(plan.isDefault)
    }

    @Test
    fun hevcIsUsedOnlyWhenDeviceHasEncoder() {
        val withEncoder = resolveEncoderTuning(VideoCodecMode.HEVC, tuning = false, hasHevcEncoder = true)
        assertEquals(MIME_HEVC, withEncoder.mime)
        assertFalse(withEncoder.isDefault)

        val withoutEncoder = resolveEncoderTuning(VideoCodecMode.HEVC, tuning = false, hasHevcEncoder = false)
        assertEquals(MIME_AVC, withoutEncoder.mime)
        assertTrue(withoutEncoder.isDefault)
    }

    @Test
    fun hevcWithTuningKeepsTunedRates() {
        val plan = resolveEncoderTuning(VideoCodecMode.HEVC, tuning = true, hasHevcEncoder = true)
        assertEquals(MIME_HEVC, plan.mime)
        assertEquals(TUNED_ENCODER_FRAME_RATE, plan.frameRate)
        assertEquals(TUNED_I_FRAME_INTERVAL_SECONDS, plan.iFrameSeconds)
    }

    @Test
    fun segmentDurationIsShortOnlyForMotionMode() {
        assertEquals(300_000L, segmentDurationMs(RecordingMode.CONTINUOUS))
        assertEquals(15_000L, segmentDurationMs(RecordingMode.MOTION_ONLY))
    }

    @Test
    fun audioTuningBitrateIsOptimizedForMonoVoice() {
        assertEquals(48_000, com.phamdu.flipcamera.EncoderTuning.DEFAULT_AUDIO_BITRATE)
    }
}

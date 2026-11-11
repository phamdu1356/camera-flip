package com.phamdu.flipcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingBitratePolicyTest {
    @Test
    fun hdUsesLowerTargetThanPreviousTwoMegabits() {
        assertEquals(RECORDING_BITRATE_HD_BPS, recordingBitrateFor(sd = false))
        assertEquals(1_500_000, recordingBitrateFor(sd = false))
        assertTrue(recordingBitrateFor(sd = false) < 2_000_000)
    }

    @Test
    fun sdUsesLowerTargetThanPreviousOneMegabit() {
        assertEquals(RECORDING_BITRATE_SD_BPS, recordingBitrateFor(sd = true))
        assertEquals(800_000, recordingBitrateFor(sd = true))
        assertTrue(recordingBitrateFor(sd = true) < 1_000_000)
    }

    @Test
    fun sdTargetIsBelowHdTarget() {
        assertTrue(recordingBitrateFor(sd = true) < recordingBitrateFor(sd = false))
    }
}

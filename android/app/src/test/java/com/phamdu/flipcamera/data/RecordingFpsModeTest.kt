package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFpsModeTest {
    @Test
    fun missingOrUnknownValuesFallBackToSmooth() {
        assertEquals(RecordingFpsMode.SMOOTH, parseRecordingFpsMode(null))
        assertEquals(RecordingFpsMode.SMOOTH, parseRecordingFpsMode(""))
        assertEquals(RecordingFpsMode.SMOOTH, parseRecordingFpsMode("turbo"))
    }

    @Test
    fun storedNamesRoundTrip() {
        assertEquals(RecordingFpsMode.SMOOTH, parseRecordingFpsMode("SMOOTH"))
        assertEquals(RecordingFpsMode.SAVER, parseRecordingFpsMode("SAVER"))
    }
}

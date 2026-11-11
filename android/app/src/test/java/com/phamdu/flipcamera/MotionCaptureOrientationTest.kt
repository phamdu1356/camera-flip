package com.phamdu.flipcamera

import com.phamdu.flipcamera.data.MotionDetector
import com.phamdu.flipcamera.data.MotionEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionCaptureOrientationTest {
    @Test
    fun rotationClosesOldIntervalAndDetectsAgainWithoutAnotherCameraStart() {
        val events = mutableListOf<MotionEventEntity>()
        val capture = capture(events)
        val context = MotionCaptureContext(
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
            1_700_000_000_000L,
        )
        capture.prepare()
        capture.recordingStarted(context)
        capture.sample(0L, frame(false))
        capture.sample(500L, frame(true))
        assertTrue(capture.sample(1_000L, frame(false))!!.started)

        capture.resetBaseline()
        assertTrue(capture.isCapturing)
        assertEquals(1, events.size)
        assertFalse(capture.sample(1_500L, frame(false))!!.active)
        capture.sample(2_000L, frame(true))
        assertTrue(capture.sample(2_500L, frame(false))!!.started)
        capture.finish()

        assertEquals(2, events.size)
        assertTrue(events.all { it.sessionId == context.sessionId && it.segmentId == context.segmentId })
        assertEquals(context.utcOffsetMs + 2_000L, events.last().startUtc)
    }

    @Test
    fun resettingOrientationDoesNotStartCaptureBeforeStartOrAfterStop() {
        val capture = capture(mutableListOf())
        capture.prepare()
        capture.resetBaseline()
        assertFalse(capture.isCapturing)
        assertNull(capture.sample(0L, frame(false)))
        capture.recordingStarted(MotionCaptureContext(null, null, 0L))
        capture.finish()
        capture.resetBaseline()
        assertFalse(capture.isCapturing)
        assertNull(capture.sample(1_000L, frame(true)))
    }

    private fun capture(events: MutableList<MotionEventEntity>) = MotionCaptureOrchestrator(
        detector = MotionDetector(width = 4, height = 4, startFrames = 2, sampleIntervalMs = 500L),
        persistEvent = { event, _ -> events += event },
    )

    private fun frame(reverse: Boolean) = ByteArray(16) { index ->
        if ((index < 8) != reverse) 100.toByte() else 0
    }
}

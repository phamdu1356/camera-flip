package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    @Test
    fun consecutiveMotionSamplesStartAndQuietSamplesEndOneMergedInterval() {
        val detector = detector(quietFrames = 3)
        val still = frame(0)
        val moving = partialMotionFrame()

        assertFalse(detector.sample(0L, still)!!.active)
        // Detector owns a bounded copy: mutating the caller buffer cannot change its baseline.
        still.fill(100)
        val candidate = detector.sample(500L, moving)!!
        assertTrue(candidate.motionDetected)
        assertFalse(candidate.active)
        assertFalse(candidate.started)

        val begun = detector.sample(1_000L, frame(0))!!
        assertTrue(begun.active)
        assertTrue(begun.started)
        assertEquals(500L, begun.activeStartMs)

        assertTrue(detector.sample(1_500L, frame(0))!!.active)
        assertTrue(detector.sample(2_000L, frame(0))!!.active)

        // New motion before the configured quiet run ends remains in the same event.
        val resumed = detector.sample(2_500L, partialMotionFrame())!!
        assertTrue(resumed.active)
        assertFalse(resumed.started)
        assertNull(resumed.completedInterval)

        detector.sample(3_000L, partialMotionFrame())
        detector.sample(3_500L, partialMotionFrame())
        val ended = detector.sample(4_000L, partialMotionFrame())!!
        assertFalse(ended.active)
        assertEquals(
            DetectedMotionInterval(500L, 3_000L, 0.5f),
            ended.completedInterval,
        )
    }

    @Test
    fun unchangedAndSmallPixelNoiseDoNotStartAnInterval() {
        val detector = detector()
        val still = frame(32)
        detector.sample(10L, still)
        assertFalse(detector.sample(510L, frame(32))!!.motionDetected)

        val noisy = frame(32).also { it[0] = 40 } // 1/16 pixels is below the 25% threshold.
        assertFalse(detector.sample(1_010L, noisy)!!.motionDetected)
        assertFalse(detector.sample(1_510L, noisy)!!.active)
    }

    @Test
    fun uniformExposureShiftIsRemovedWhileLocalChangesStillCountAsMotion() {
        val detector = detector()
        detector.sample(0L, frame(40))
        val brighter = detector.sample(500L, frame(90))!!
        assertFalse(brighter.motionDetected)
        assertEquals(0f, brighter.changedFraction)

        val localChange = detector.sample(1_000L, partialMotionFrame(90))!!
        assertTrue(localChange.motionDetected)
        assertEquals(0.5f, localChange.changedFraction)
    }

    @Test
    fun continuouslyMovingSceneSplitsAtTheMaximumIntervalLength() {
        val detector = MotionDetector(
            width = 4,
            height = 4,
            pixelDifferenceThreshold = 18,
            changedFractionThreshold = 0.25f,
            startFrames = 2,
            quietFramesToEnd = 4,
            sampleIntervalMs = 500L,
            maxEventDurationMs = 2_000L,
            maxSampleGapMs = 5_000L,
        )
        val left = partialMotionFrame(100)
        val right = frame(0).also { frame ->
            for (index in frame.size / 2 until frame.size) frame[index] = 100.toByte()
        }
        detector.sample(0L, left)
        detector.sample(500L, right)
        assertTrue(detector.sample(1_000L, left)!!.started)
        assertTrue(detector.sample(1_500L, right)!!.active)
        assertTrue(detector.sample(2_000L, left)!!.active)

        val split = detector.sample(2_500L, right)!!
        assertTrue(split.started)
        assertEquals(2_500L, split.activeStartMs)
        assertEquals(DetectedMotionInterval(500L, 2_500L, 0.5f), split.completedInterval)
    }

    @Test
    fun timestampsAreExplicitAndOutOfOrderSamplesDoNotMutateState() {
        val detector = detector()
        detector.sample(100L, frame(0))
        assertNull(detector.sample(100L, frame(100)))
        assertNull(detector.sample(99L, frame(100)))

        val candidate = detector.sample(600L, frame(100))!!
        assertEquals(600L, candidate.timestampMs)
        assertFalse(candidate.active)
    }

    @Test
    fun samplingPauseClosesAnOpenIntervalAndUsesNewFrameAsBaseline() {
        val detector = detector()
        detector.sample(0L, frame(0))
        detector.sample(500L, partialMotionFrame())
        detector.sample(1_000L, frame(0)) // confirmed start

        val pause = detector.sample(7_000L, frame(100))!!
        assertFalse(pause.active)
        assertEquals(DetectedMotionInterval(500L, 1_500L, 0.5f), pause.completedInterval)

        // A baseline after a pause must not be mistaken for a changed frame.
        val next = detector.sample(7_500L, frame(100))!!
        assertFalse(next.motionDetected)
    }

    @Test
    fun finishClosesAtLastObservedMotionAndResetStartsFresh() {
        val detector = detector()
        detector.sample(0L, frame(0))
        detector.sample(500L, partialMotionFrame())
        detector.sample(1_000L, frame(0))

        assertEquals(DetectedMotionInterval(500L, 1_500L, 0.5f), detector.finish())
        assertNull(detector.finish())
        detector.reset()
        assertFalse(detector.sample(0L, frame(100))!!.active)
    }

    @Test
    fun dimensionsAndFrameSizeAreHardBounded() {
        assertThrows<IllegalArgumentException> {
            MotionDetector(width = 129, height = 72)
        }
        val detector = detector()
        assertThrows<IllegalArgumentException> {
            detector.sample(0L, ByteArray(15))
        }
    }

    private fun detector(quietFrames: Int = 4) = MotionDetector(
        width = 4,
        height = 4,
        pixelDifferenceThreshold = 18,
        changedFractionThreshold = 0.25f,
        startFrames = 2,
        quietFramesToEnd = quietFrames,
        sampleIntervalMs = 500L,
        maxEventDurationMs = 10_000L,
        maxSampleGapMs = 5_000L,
    )

    private fun frame(value: Int) = ByteArray(16) { value.toByte() }

    private fun partialMotionFrame(value: Int = 100) = frame(0).also { frame ->
        for (index in 0 until frame.size / 2) frame[index] = value.toByte()
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got $throwable", throwable is T)
            return
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}

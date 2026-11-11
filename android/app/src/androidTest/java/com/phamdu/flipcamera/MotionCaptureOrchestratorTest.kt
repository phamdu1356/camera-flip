package com.phamdu.flipcamera

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phamdu.flipcamera.data.MotionDetector
import com.phamdu.flipcamera.data.MotionEventEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MotionCaptureOrchestratorTest {
    @Test
    fun detectorIgnoresSamplesUntilCameraStartAndStopOrFinalizeClosesOnlyOnce() {
        val persisted = mutableListOf<Pair<MotionEventEntity, ByteArray?>>()
        val ids = sequenceOf(EVENT_ID_1, EVENT_ID_2, EVENT_ID_3).iterator()
        val capture = MotionCaptureOrchestrator(
            detector = detector(),
            createEventId = { ids.next() },
            persistEvent = { event, thumbnail -> persisted += event to thumbnail?.copyOf() },
        )
        capture.prepare()

        // A user recording request prepares the detector but does not open its sampling gate.
        assertNull(capture.sample(0L, leftFrame()))
        assertNull(capture.sample(500L, rightFrame()))
        assertTrue(persisted.isEmpty())
        assertFalse(capture.isCapturing)

        capture.recordingStarted(context())
        assertFalse(capture.sample(1_000L, leftFrame())!!.active) // New detector baseline.
        assertFalse(capture.sample(1_500L, rightFrame())!!.active) // First motion sample.
        val started = capture.sample(2_000L, leftFrame())!!
        assertTrue(started.started)
        assertEquals(1_500L, started.activeStartMs)

        val jpeg = validJpeg()
        capture.setActiveThumbnail(jpeg)
        val expectedThumbnail = jpeg.copyOf()
        jpeg[2] = 7 // Coordinator owns the bounded thumbnail bytes.

        // stopRecording and the later CameraX Finalize both call finish; only one event is saved.
        val stoppedEvent = capture.finish()
        val finalizedEvent = capture.finish()
        assertNotNull(stoppedEvent)
        assertNull(finalizedEvent)
        assertFalse(capture.isCapturing)
        assertEquals(1, persisted.size)

        val (event, storedThumbnail) = persisted.single()
        assertEquals(EVENT_ID_1, event.id)
        assertEquals(SESSION_ID, event.sessionId)
        assertEquals(SEGMENT_ID, event.segmentId)
        assertEquals(context().utcOffsetMs + 1_500L, event.startUtc)
        assertEquals(context().utcOffsetMs + 2_500L, event.endUtc)
        assertArrayEquals(expectedThumbnail, storedThumbnail)
    }

    @Test
    fun thumbnailAndPersistenceFailuresDoNotCloseTheRecordingCaptureGate() {
        val attempts = mutableListOf<Pair<MotionEventEntity, ByteArray?>>()
        val ids = sequenceOf(EVENT_ID_1, EVENT_ID_2, EVENT_ID_3).iterator()
        var failFirstPersistence = true
        val capture = MotionCaptureOrchestrator(
            detector = detector(quietFrames = 2),
            createEventId = { ids.next() },
            persistEvent = { event, thumbnail ->
                attempts += event to thumbnail?.copyOf()
                if (event.id == EVENT_ID_1 && failFirstPersistence) {
                    failFirstPersistence = false
                    throw IOException("simulated Room insert failure")
                }
                if (event.id == EVENT_ID_2 && thumbnail != null) {
                    throw IOException("simulated thumbnail persistence failure")
                }
            },
        )
        capture.prepare()
        capture.recordingStarted(context())
        capture.sample(0L, leftFrame())
        capture.sample(500L, rightFrame())
        capture.sample(1_000L, leftFrame()) // Starts interval 1.
        capture.setActiveThumbnail(byteArrayOf(1, 2, 3, 4)) // Invalid JPEG is ignored.

        capture.sample(1_500L, rightFrame())
        capture.sample(2_000L, leftFrame())
        capture.sample(2_500L, leftFrame())
        val firstClosed = capture.sample(3_000L, leftFrame())!!
        assertNotNull(firstClosed.completedInterval)
        assertTrue(capture.isCapturing)
        assertEquals(1, attempts.size)
        assertNull(attempts.first().second)

        // Sampling continues, can accept a second interval and attempt a real thumbnail write.
        capture.sample(3_500L, rightFrame())
        val secondStarted = capture.sample(4_000L, leftFrame())!!
        assertTrue(secondStarted.started)
        val validThumbnail = validJpeg()
        capture.setActiveThumbnail(validThumbnail)

        capture.sample(4_500L, rightFrame())
        capture.sample(5_000L, leftFrame())
        capture.sample(5_500L, leftFrame())
        val thumbnailFailure = capture.sample(6_000L, leftFrame())!!
        assertNotNull(thumbnailFailure.completedInterval)
        assertTrue(capture.isCapturing)

        // Both failed persistence paths leave motion sampling available for another event.
        capture.sample(6_500L, rightFrame())
        val thirdStarted = capture.sample(7_000L, leftFrame())!!
        assertTrue(thirdStarted.started)
        capture.finish()

        assertEquals(3, attempts.size)
        assertEquals(EVENT_ID_2, attempts[1].first.id)
        assertArrayEquals(validThumbnail, attempts[1].second)
        assertEquals(EVENT_ID_3, attempts.last().first.id)
        assertFalse(capture.isCapturing)
    }

    @Test
    fun rotationChangesSegmentForFutureEventsButPreservesActiveEventOrigin() {
        val persisted = mutableListOf<MotionEventEntity>()
        val ids = sequenceOf(EVENT_ID_1, EVENT_ID_2).iterator()
        val capture = MotionCaptureOrchestrator(
            detector = detector(quietFrames = 2),
            createEventId = { ids.next() },
            persistEvent = { event, _ -> persisted += event },
        )
        capture.prepare()
        capture.recordingStarted(context(SEGMENT_ID_A))
        capture.sample(0L, leftFrame())
        capture.sample(500L, rightFrame())
        assertTrue(capture.sample(1_000L, leftFrame())!!.started)

        // A new CameraX segment starts while event 1 is open.
        capture.recordingStarted(context(SEGMENT_ID_B))
        capture.sample(1_500L, rightFrame())
        capture.sample(2_000L, leftFrame())
        capture.sample(2_500L, leftFrame())
        val firstClosed = capture.sample(3_000L, leftFrame())!!
        assertNotNull(firstClosed.completedInterval)
        assertEquals(SEGMENT_ID_A, persisted.single().segmentId)

        // The next event begins after rotation and uses the newer segment context.
        capture.sample(3_500L, rightFrame())
        assertTrue(capture.sample(4_000L, leftFrame())!!.started)
        capture.finish()

        assertEquals(listOf(SEGMENT_ID_A, SEGMENT_ID_B), persisted.map { it.segmentId })
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

    private fun leftFrame() = ByteArray(16) { index -> if (index < 8) 100.toByte() else 0 }

    private fun rightFrame() = ByteArray(16) { index -> if (index < 8) 0 else 100.toByte() }

    private fun validJpeg() = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0x02, 0xff.toByte(), 0xd9.toByte())

    private fun context(segmentId: String = SEGMENT_ID) = MotionCaptureContext(
        sessionId = SESSION_ID,
        segmentId = segmentId,
        utcOffsetMs = 1_700_000_000_000L,
    )

    private companion object {
        const val EVENT_ID_1 = "00000000-0000-0000-0000-000000000001"
        const val EVENT_ID_2 = "00000000-0000-0000-0000-000000000002"
        const val EVENT_ID_3 = "00000000-0000-0000-0000-000000000003"
        const val SESSION_ID = "00000000-0000-0000-0000-000000000004"
        const val SEGMENT_ID = "00000000-0000-0000-0000-000000000005"
        const val SEGMENT_ID_A = "00000000-0000-0000-0000-000000000006"
        const val SEGMENT_ID_B = "00000000-0000-0000-0000-000000000007"
    }
}

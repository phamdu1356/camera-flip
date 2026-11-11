package com.phamdu.flipcamera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class WatermarkFramePolicyTest {
    @Test fun relatedFailureBeforeEncoderFinalizeNeverInvokesReadyCommit() {
        val segment = WatermarkSegmentPublication("segment-a", 7)
        val arrivedAtFinalize = CountDownLatch(1)
        val releaseFinalize = CountDownLatch(1)
        val readyWrites = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val job = executor.submit<Boolean> {
                arrivedAtFinalize.countDown()
                check(releaseFinalize.await(3, TimeUnit.SECONDS))
                val permit = segment.seal()
                runBlocking {
                    runCatching { segment.publish(permit) { readyWrites.incrementAndGet() } }.isSuccess
                }
            }
            assertTrue(arrivedAtFinalize.await(2, TimeUnit.SECONDS))
            assertTrue(segment.invalidate()) // Render failure delivered before Finalize seal.
            releaseFinalize.countDown()
            assertFalse(job.get(2, TimeUnit.SECONDS))
            assertEquals(0, readyWrites.get())
        } finally { releaseFinalize.countDown(); executor.shutdownNow() }
    }

    @Test fun laterPipelineFailureWhileInspectionAndRoomSuspendKeepsClosedSegmentValid() {
        val old = WatermarkSegmentPublication("segment-a", 7)
        val next = WatermarkSegmentPublication("segment-b", 7)
        val permit = old.seal() // Encoder Finalize: immutable attestation of old frame set.
        val inspectionEntered = CountDownLatch(1)
        val releaseInspection = CountDownLatch(1)
        val roomEntered = CountDownLatch(1)
        val releaseRoom = CountDownLatch(1)
        val readyWrites = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val job = executor.submit<Int> { runBlocking {
                inspectionEntered.countDown()
                check(releaseInspection.await(3, TimeUnit.SECONDS))
                old.publish(permit) {
                    roomEntered.countDown()
                    check(releaseRoom.await(3, TimeUnit.SECONDS))
                    readyWrites.incrementAndGet()
                }
            } }
            assertTrue(inspectionEntered.await(2, TimeUnit.SECONDS))
            assertFalse(old.invalidate()) // Late callback cannot reclassify closed frames.
            assertTrue(next.invalidate()) // The new segment is still unverified.
            releaseInspection.countDown()
            assertTrue(roomEntered.await(2, TimeUnit.SECONDS))
            assertFalse(old.invalidate()) // Also safe while the actual commit is suspended.
            releaseRoom.countDown()
            assertEquals(1, job.get(2, TimeUnit.SECONDS).toInt())
            assertNull(next.seal())
        } finally { releaseInspection.countDown(); releaseRoom.countDown(); executor.shutdownNow() }
    }

    @Test fun publicationPermitCannotBeReusedForAnotherSegmentOrBinding() = runBlocking {
        val first = WatermarkSegmentPublication("segment-a", 7)
        val permit = first.seal()
        for (other in listOf(WatermarkSegmentPublication("segment-b", 7), WatermarkSegmentPublication("segment-a", 8))) {
            other.seal()
            var committed = false
            assertTrue(runCatching { other.publish(permit) { committed = true } }.isFailure)
            assertFalse(committed)
        }
    }
    @Test fun cropRotationMirrorPreserveUprightOverlayCoordinates() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val transform = WatermarkTransform(40, 70, 1240, 670, rotation, mirror)
            val w = transform.displayWidth.toFloat()
            val h = transform.displayHeight.toFloat()
            for ((u, v) in listOf(0f to 0f, w to 0f, 0f to h, w to h, 21f to (h - 25f), w / 2 to h / 2)) {
                val (rawX, rawY) = transform.bufferPoint(u, v)
                assertTrue(rawX in 40f..1240f && rawY in 70f..670f)
                val x = rawX - 40f
                val y = rawY - 70f
                // Independent forward rendering in the documented crop -> rotate -> mirror order.
                var shown = when (rotation) {
                    0 -> x to y
                    90 -> (600f - y) to x
                    180 -> (1200f - x) to (600f - y)
                    else -> y to (1200f - x)
                }
                if (mirror) shown = (w - shown.first) to shown.second
                assertEquals("rotation=$rotation mirror=$mirror", u, shown.first, 0.001f)
                assertEquals(v, shown.second, 0.001f)
            }
        }
        assertEquals(40f to 670f, WatermarkTransform(40, 70, 1240, 670, 90, false).bufferPoint(0f, 0f))
        assertThrows(IllegalArgumentException::class.java) { WatermarkTransform(0, 0, 0, 50, 0, false) }
    }

    @Test fun sharedFrameClockIsIndependentOfCallbackDelayAndStreamOrder() {
        val clock = WatermarkFrameClock(true, 50_000_000_000L, 1_700_000_000_000L)
        val live = clock.utcMs(50_100_000_000L, 1, 50_200_000_000L, 1_700_000_000_200L)
        val video = clock.utcMs(50_100_000_000L, 0, 52_200_000_000L, 1_700_000_002_200L)
        assertEquals(1_700_000_000_100L, live)
        assertEquals(live, video)
    }

    @Test fun invalidOrDiscontinuousTimestampNeverFallsBackToProcessingTime() {
        assertThrows(IllegalArgumentException::class.java) { WatermarkFrameClock(false, 10L, 100L) }
        fun clock() = WatermarkFrameClock(true, 50_000_000_000L, 1_700_000_000_000L)
        for (bad in listOf(0L, -1L, 30_000_000_000L, 51_000_000_000L)) {
            assertThrows(IllegalStateException::class.java) { clock().utcMs(bad, 0, 50_000_000_000L, 1_700_000_000_000L) }
        }
        val backwards = clock()
        backwards.utcMs(50_000_000_000L, 0, 50_000_000_000L, 1_700_000_000_000L)
        assertThrows(IllegalStateException::class.java) {
            backwards.utcMs(49_999_999_999L, 0, 50_000_000_000L, 1_700_000_000_000L)
        }
        assertThrows(IllegalStateException::class.java) {
            clock().utcMs(50_000_000_000L, 0, 50_000_000_000L, 1_700_000_010_000L)
        }
    }

    @Test fun concurrentInjectedErrorsRequestOneStopAndCannotClearFirstFailure() {
        val health = WatermarkHealth()
        val stopRequests = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..31).map { index -> executor.submit {
                if (health.fail("injected draw error $index")) stopRequests.incrementAndGet()
            } }
            jobs.forEach { it.get() }
            assertEquals(1, stopRequests.get())
            val first = health.failure
            assertFalse(health.fail("later error"))
            assertEquals(first, health.failure)
        } finally { executor.shutdownNow() }
    }
}

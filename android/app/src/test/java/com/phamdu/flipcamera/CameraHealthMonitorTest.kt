package com.phamdu.flipcamera

import org.junit.Assert.*
import org.junit.Test

class CameraHealthMonitorTest {
    private val start = 10_000_000_000L
    private fun opened(health: CameraHealthMonitor, generation: Int = 1): CameraBindingToken =
        health.bind(generation).also {
            health.setAnalysis(it, true)
            health.update(it, CameraHealthPhase.OPEN, null, start)
        }

    @Test fun openAndRawFrameDoNotProveJpegPublication() {
        val health = CameraHealthMonitor()
        val binding = opened(health)
        assertEquals(LiveHealth.WAITING, health.snapshot(start, true).live)
        val ticket = health.frame(binding, start + 1, start + 2)!!
        assertTrue(health.snapshot(start + 2, true).rawFresh)
        assertFalse(health.publish(ticket, start + 3) { false })
        assertEquals(LiveHealth.WAITING, health.snapshot(start + 3, true).live)
        assertTrue(health.publish(ticket, start + 4) { true })
        assertEquals(LiveHealth.FRESH, health.snapshot(start + 4, true).live)
        assertEquals(LiveHealth.STALE, health.snapshot(start + 2_000_000_005L, true).live)
        assertEquals(LiveHealth.IDLE, health.snapshot(start + 5, false).live)
    }

    @Test fun previousBindingsCannotUpdateOrPublishEvenWithinSameGeneration() {
        val health = CameraHealthMonitor()
        val old = opened(health)
        val ticket = health.frame(old, start + 1, start + 2)!!
        val current = opened(health)
        assertFalse(health.update(old, CameraHealthPhase.ERROR, 3, start + 3))
        assertNull(health.frame(old, start + 4, start + 5))
        assertFalse(health.publish(ticket, start + 5) { fail("old publication"); true })
        assertTrue(health.current(current))
        opened(health, 2)
        assertFalse(health.update(current, CameraHealthPhase.ERROR, 3, start + 6))
    }

    @Test fun reopenResetsFramesAndRejectsEncodingFromBeforeFault() {
        val health = CameraHealthMonitor()
        val binding = opened(health)
        val old = health.frame(binding, start + 1, start + 2)!!
        health.publish(old, start + 3) { true }
        health.update(binding, CameraHealthPhase.REOPENING, 2, start + 4)
        assertFalse(health.snapshot(start + 4, true).canStart)
        assertFalse(health.snapshot(start + 4, true).rawFresh)
        health.update(binding, CameraHealthPhase.OPEN, null, start + 10)
        assertFalse(health.publish(old, start + 11) { fail("pre-fault JPEG"); true })
        assertNull(health.frame(binding, start + 9, start + 11))
        assertEquals(LiveHealth.WAITING, health.snapshot(start + 11, true).live)
        val fresh = health.frame(binding, start + 12, start + 13)!!
        assertTrue(health.publish(fresh, start + 14) { true })
        assertEquals(LiveHealth.FRESH, health.snapshot(start + 14, true).live)
    }

    @Test fun noAnalysisOrViewerDoesNotBlockRecordingReadiness() {
        val health = CameraHealthMonitor()
        val binding = health.bind(1)
        health.update(binding, CameraHealthPhase.OPEN, null, start)
        val snapshot = health.snapshot(start, false)
        assertTrue(snapshot.canStart)
        assertEquals(LiveHealth.UNAVAILABLE, snapshot.live)
    }

    @Test fun errorWithOpenTypeNeverAllowsStartOrFramePublication() {
        val health = CameraHealthMonitor()
        val binding = opened(health)
        val ticket = health.frame(binding, start + 1, start + 2)!!
        health.update(binding, CameraHealthPhase.OPEN, 3, start + 3)
        assertFalse(health.snapshot(start + 4, true).canStart)
        assertNull(health.frame(binding, start + 4, start + 5))
        assertFalse(health.publish(ticket, start + 5) { fail("error publication"); true })
    }

    @Test fun detachAndStaleFramesCannotPublish() {
        val health = CameraHealthMonitor()
        val binding = opened(health)
        val ticket = health.frame(binding, start + 1, start + 2)!!
        assertNull(health.frame(binding, start - 1, start + 2))
        assertNull(health.frame(binding, start + 3, start + 2))
        assertFalse(health.publish(ticket, start + 2_000_000_002L) { fail("stale"); true })
        health.detach()
        assertFalse(health.update(binding, CameraHealthPhase.OPEN, null, start + 4))
        assertFalse(health.publish(ticket, start + 4) { fail("detached"); true })
        assertFalse(health.snapshot(start + 4, true).canStart)
    }
}

package com.phamdu.flipcamera.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStreamStatsTest {
    @Test fun computesFpsAndKbps() {
        val s = LiveStreamStats()
        for (i in 0..30) s.record("t", 20_000, 1_000L + i * 100L) // 10 fps, 20 KB/frame
        val snap = s.snapshot("t", 4_000L)
        assertNotNull(snap)
        assertEquals(10f, snap!!.fps, 0.1f)
        assertEquals(1_653.0, snap.kbps.toDouble(), 60.0) // 31 khung * 20 KB / 3 s
    }

    @Test fun staleStreamHasNoData() {
        val s = LiveStreamStats()
        s.record("t", 1000, 0L); s.record("t", 1000, 100L)
        assertNull(s.snapshot("t", 10_000L))
        assertNull(s.snapshot("other", 0L))
    }

    @Test fun clearRemoves() {
        val s = LiveStreamStats()
        s.record("t", 1, 0L); s.record("t", 1, 10L)
        s.clear("t")
        assertNull(s.snapshot("t", 20L))
    }
}

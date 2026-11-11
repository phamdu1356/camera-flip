package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightTorchPolicyTest {
    @Test fun nightBelowLimitTurnsOn() {
        assertTrue(NightTorchPolicy.decide(22, 40f, false).torchOn)
        assertTrue(NightTorchPolicy.decide(3, 45f, false).torchOn)
    }

    @Test fun nightAboveLimitTurnsOff() {
        val d = NightTorchPolicy.decide(23, 45.1f, false)
        assertFalse(d.torchOn)
        assertTrue(d.heatLocked)
    }

    @Test fun hysteresisKeepsOffUntilCooled() {
        assertFalse(NightTorchPolicy.decide(23, 44f, true).torchOn)
        assertTrue(NightTorchPolicy.decide(23, 43f, true).torchOn)
    }

    @Test fun daytimeNeverTurnsOn() {
        assertFalse(NightTorchPolicy.decide(12, 30f, false).torchOn)
        assertFalse(NightTorchPolicy.decide(6, 30f, false).torchOn)
        assertTrue(NightTorchPolicy.decide(18, 30f, false).torchOn)
    }

    @Test fun unknownTemperatureKeepsLockState() {
        assertEquals(true, NightTorchPolicy.decide(23, null, true).heatLocked)
        assertTrue(NightTorchPolicy.decide(23, null, false).torchOn)
    }
}

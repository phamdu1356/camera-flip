package com.phamdu.flipcamera.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerRequestSecurityTest {
    @Test
    fun parsesHostPortAndBracketedIpv6Authority() {
        assertEquals(HostAuthority("camera.local", 8443), parseHostAuthority("Camera.Local.:8443"))
        assertEquals(HostAuthority("192.168.1.20", null), parseHostAuthority("192.168.1.20"))
        assertEquals(HostAuthority("fd00::1", 443), parseHostAuthority("[fd00::1]:443"))
    }

    @Test
    fun rejectsAmbiguousOrInjectedHostAuthorities() {
        listOf(
            null,
            "",
            "attacker.example/path",
            "user@camera.local",
            "camera.local:",
            "camera.local:0",
            "camera.local:65536",
            "fd00::1:443",
            "[fd00::1]junk",
            "[fd00::1%wlan0]",
        ).forEach { authority -> assertNull("Rejected: $authority", parseHostAuthority(authority)) }
    }
}

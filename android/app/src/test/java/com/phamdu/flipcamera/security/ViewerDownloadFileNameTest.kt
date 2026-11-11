package com.phamdu.flipcamera.security

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerDownloadFileNameTest {
    @Test
    fun usesUtcPlusSevenStartTime() {
        // 2026-11-05T17:30:15Z = 2026-11-06 00:30:15 UTC+7
        assertEquals("flip-2026-11-06_00-30-15.mp4", viewerDownloadFileName(1793899815000L))
    }
}

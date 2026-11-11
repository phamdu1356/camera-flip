package com.phamdu.flipcamera.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerByteRangeTest {
    @Test
    fun parsesClosedOpenAndSuffixRanges() {
        assertEquals(ViewerByteRange(0, 4), parseViewerByteRange("bytes=0-4", 10))
        assertEquals(ViewerByteRange(5, 9), parseViewerByteRange("bytes=5-", 10))
        assertEquals(ViewerByteRange(7, 9), parseViewerByteRange("bytes=-3", 10))
        assertEquals(ViewerByteRange(0, 9), parseViewerByteRange("bytes=-50", 10))
        assertEquals(ViewerByteRange(8, 9), parseViewerByteRange("bytes=8-50", 10))
    }

    @Test
    fun rejectsMalformedAndUnsatisfiableRanges() {
        listOf(
            "bytes=0-1,4-5",
            "items=0-1",
            "bytes=-0",
            "bytes=10-10",
            "bytes=4-2",
            "bytes=abc-def",
            "bytes=0-1-2",
        ).forEach { assertNull(it, parseViewerByteRange(it, 10)) }
        assertNull(parseViewerByteRange("bytes=0-0", 0))
    }
}

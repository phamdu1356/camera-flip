package com.phamdu.flipcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerFpsRangeTest {
    @Test
    fun prefersRangeWithLowerAtLeastTwentyAndHighestCappedUpper() {
        val picked = selectViewerFpsRange(
            listOf(ViewerFpsRange(7, 30), ViewerFpsRange(24, 30), ViewerFpsRange(15, 60)),
        )
        assertEquals(ViewerFpsRange(24, 30), picked)
    }

    @Test
    fun fixedThirtyBeatsSameCapWithLowerTwenty() {
        val picked = selectViewerFpsRange(listOf(ViewerFpsRange(20, 30), ViewerFpsRange(30, 30)))
        assertEquals(ViewerFpsRange(30, 30), picked)
    }

    @Test
    fun ratesAboveThirtyAreCappedSoHigherLowerWins() {
        val picked = selectViewerFpsRange(listOf(ViewerFpsRange(20, 60), ViewerFpsRange(24, 30)))
        assertEquals(ViewerFpsRange(24, 30), picked)
    }

    @Test
    fun fallsBackToLargestUpperAtLeastTwentyWhenNoLowerReachesTwenty() {
        val picked = selectViewerFpsRange(listOf(ViewerFpsRange(5, 15), ViewerFpsRange(5, 24)))
        assertEquals(ViewerFpsRange(5, 24), picked)
    }

    @Test
    fun keepsFallbackWhenNoRangeReachesTwenty() {
        assertEquals(ViewerFpsRange(15, 15),
            selectViewerFpsRange(listOf(ViewerFpsRange(5, 15), ViewerFpsRange(10, 15))))
    }

    @Test
    fun keepsFallbackWhenNoRangesAreReported() {
        assertEquals(ViewerFpsRange(15, 15), selectViewerFpsRange(emptyList()))
        assertEquals(ViewerFpsRange(12, 12), selectViewerFpsRange(emptyList(), fallbackFps = 12))
    }

    @Test
    fun labelShowsRangeOrSingleValue() {
        assertEquals("24-30", viewerFpsLabel(ViewerFpsRange(24, 30)))
        assertEquals("30", viewerFpsLabel(ViewerFpsRange(30, 30)))
    }
}

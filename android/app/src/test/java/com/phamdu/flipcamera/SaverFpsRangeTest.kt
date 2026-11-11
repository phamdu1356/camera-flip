package com.phamdu.flipcamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaverFpsRangeTest {
    @Test
    fun prefersExactFifteenOverRangeWithSameUpperAndLowerLimit() {
        val picked = selectSaverFpsRange(listOf(ViewerFpsRange(7, 15), ViewerFpsRange(15, 15)))
        assertEquals(ViewerFpsRange(15, 15), picked)
    }

    @Test
    fun picksSmallestUpperWithinFifteenToTwenty() {
        val picked = selectSaverFpsRange(
            listOf(ViewerFpsRange(20, 20), ViewerFpsRange(10, 18), ViewerFpsRange(5, 16)),
        )
        assertEquals(ViewerFpsRange(5, 16), picked)
    }

    @Test
    fun ignoresRangesOutsideFifteenToTwenty() {
        assertNull(selectSaverFpsRange(listOf(ViewerFpsRange(30, 30), ViewerFpsRange(5, 10), ViewerFpsRange(21, 24))))
    }

    @Test
    fun ignoresInvalidRangesAndEmptyLists() {
        assertNull(selectSaverFpsRange(emptyList()))
        assertNull(selectSaverFpsRange(listOf(ViewerFpsRange(16, 15))))
    }
}

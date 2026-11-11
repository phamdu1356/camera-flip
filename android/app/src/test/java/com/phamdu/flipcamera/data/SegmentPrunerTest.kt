package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPrunerTest {
    private val segStart = 100_000L
    private val segEnd = 160_000L
    private val now = 1_000_000L

    private fun keep(vararg events: MotionInterval, isProtected: Boolean = false): Boolean =
        shouldKeepSegment(segStart, segEnd, isProtected, events.toList(), now)

    @Test
    fun segmentWithoutMotionIsDeleted() {
        assertFalse(keep())
    }

    @Test
    fun protectedSegmentIsAlwaysKept() {
        assertTrue(keep(isProtected = true))
    }

    @Test
    fun eventEndingExactlyAtPreRollBoundaryDoesNotKeepSegment() {
        // Window starts at eventStart - 10s. Segment ends exactly there, so the half-open intervals do not overlap.
        val eventStart = segEnd + DEFAULT_PRE_ROLL_MS
        assertFalse(keep(MotionInterval(eventStart, eventStart + 1_000L)))
    }

    @Test
    fun eventOneMillisecondInsidePreRollKeepsSegment() {
        val eventStart = segEnd + DEFAULT_PRE_ROLL_MS - 1L
        assertTrue(keep(MotionInterval(eventStart, eventStart + 1_000L)))
    }

    @Test
    fun eventStartingExactlyAtPostRollBoundaryDoesNotKeepSegment() {
        // Window ends at eventEnd + postRoll. A segment starting exactly there does not overlap.
        val eventEnd = segStart - DEFAULT_POST_ROLL_MS
        assertFalse(keep(MotionInterval(eventEnd - 1_000L, eventEnd)))
    }

    @Test
    fun eventEndingOneMillisecondInsidePostRollKeepsSegment() {
        val eventEnd = segStart - DEFAULT_POST_ROLL_MS + 1L
        assertTrue(keep(MotionInterval(eventEnd - 1_000L, eventEnd)))
    }

    @Test
    fun eventInsideSegmentKeepsIt() {
        assertTrue(keep(MotionInterval(120_000L, 121_000L)))
    }

    @Test
    fun ongoingEventExtendsToNowAndKeepsSegment() {
        // The event started during the segment and has not ended; it counts as ending at now.
        assertTrue(keep(MotionInterval(150_000L, null)))
    }

    @Test
    fun ongoingEventStartedAfterSegmentEndStillKeepsSegmentWithinPreRoll() {
        val ongoingStart = segEnd + 5_000L
        assertTrue(shouldKeepSegment(segStart, segEnd, false, listOf(MotionInterval(ongoingStart, null)), now))
    }

    @Test
    fun ongoingEventStartedLongAfterSegmentDoesNotKeepSegment() {
        assertFalse(shouldKeepSegment(segStart, segEnd, false, listOf(MotionInterval(segEnd + 60_000L, null)), now))
    }

    @Test
    fun customRollsAreHonoured() {
        val eventStart = segEnd + 2_000L
        assertTrue(shouldKeepSegment(segStart, segEnd, false, listOf(MotionInterval(eventStart, eventStart + 1L)), now, preRollMs = 2_001L))
        assertFalse(shouldKeepSegment(segStart, segEnd, false, listOf(MotionInterval(eventStart, eventStart + 1L)), now, preRollMs = 2_000L))
    }

    @Test
    fun emptyIntervalListWithProtectionFalseDeletes() {
        assertFalse(shouldKeepSegment(segStart, segEnd, false, emptyList(), now))
    }
}

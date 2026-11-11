package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StoragePolicyTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_000 * day

    private fun seg(id: String, daysAgo: Long, bytes: Long = 10L, protectedFlag: Boolean = false) =
        StorageSegment(id = id, startUtc = now - daysAgo * day, bytes = bytes, isProtected = protectedFlag)

    @Test
    fun defaultConfigEvictsNothing() {
        val segments = listOf(seg("a", 400, bytes = 1_000_000L), seg("b", 1))
        assertEquals(emptyList<String>(), segmentsToEvict(segments, StoragePolicyConfig(), now))
    }

    @Test
    fun defaultConfigIsContinuousAndUnlimited() {
        val config = StoragePolicyConfig()
        assertEquals(RecordingMode.CONTINUOUS, config.mode)
        assertEquals(0, config.maxDays)
        assertEquals(0L, config.maxBytes)
    }

    @Test
    fun emptyListReturnsEmpty() {
        assertEquals(emptyList<String>(), segmentsToEvict(emptyList(), StoragePolicyConfig(maxDays = 1, maxBytes = 1), now))
    }

    @Test
    fun evictsSegmentsOlderThanMaxDaysOldestFirst() {
        val segments = listOf(seg("new", 1), seg("old", 10), seg("older", 20), seg("edge", 7))
        val result = segmentsToEvict(segments, StoragePolicyConfig(maxDays = 7), now)
        // "edge" started exactly at the cutoff, so it is not older than the limit.
        assertEquals(listOf("older", "old"), result)
    }

    @Test
    fun ageEvictionSkipsProtectedSegments() {
        val segments = listOf(seg("protectedOld", 30, protectedFlag = true), seg("oldUnprotected", 30))
        assertEquals(listOf("oldUnprotected"), segmentsToEvict(segments, StoragePolicyConfig(maxDays = 7), now))
    }

    @Test
    fun evictsOldestUntilTotalBytesFitLimit() {
        val segments = listOf(seg("c", 1, bytes = 40), seg("b", 2, bytes = 40), seg("a", 3, bytes = 40))
        // Total 120, limit 90: evict "a" (oldest) to reach 80.
        assertEquals(listOf("a"), segmentsToEvict(segments, StoragePolicyConfig(maxBytes = 90), now))
    }

    @Test
    fun byteEvictionContinuesUntilUnderLimit() {
        val segments = listOf(seg("c", 1, bytes = 40), seg("b", 2, bytes = 40), seg("a", 3, bytes = 40))
        assertEquals(listOf("a", "b"), segmentsToEvict(segments, StoragePolicyConfig(maxBytes = 50), now))
    }

    @Test
    fun byteLimitCountsProtectedBytesButNeverEvictsThem() {
        val segments = listOf(
            seg("protectedOld", 5, bytes = 100, protectedFlag = true),
            seg("unprotectedOld", 4, bytes = 10),
            seg("new", 1, bytes = 10),
        )
        // Protected 100 bytes alone exceed the limit, so every unprotected segment goes.
        assertEquals(listOf("unprotectedOld", "new"), segmentsToEvict(segments, StoragePolicyConfig(maxBytes = 50), now))
    }

    @Test
    fun byteLimitStopsWhenProtectedDataAloneFitsAndNoUnprotectedRemain() {
        val segments = listOf(seg("p", 1, bytes = 100, protectedFlag = true))
        assertEquals(emptyList<String>(), segmentsToEvict(segments, StoragePolicyConfig(maxBytes = 50), now))
    }

    @Test
    fun ageAndBytesCombineWithoutDoubleCounting() {
        val segments = listOf(
            seg("ancient", 30, bytes = 50),
            seg("middle", 2, bytes = 50),
            seg("recent", 1, bytes = 50),
        )
        // Age removes "ancient" first. Then 100 bytes remain against a 60-byte limit, so "middle" goes too.
        assertEquals(listOf("ancient", "middle"), segmentsToEvict(segments, StoragePolicyConfig(maxDays = 10, maxBytes = 60), now))
    }

    @Test
    fun tiesOnStartUtcAreOrderedById() {
        val start = now - 30 * day
        val segments = listOf(
            StorageSegment("b", start, 10, false),
            StorageSegment("a", start, 10, false),
        )
        assertEquals(listOf("a", "b"), segmentsToEvict(segments, StoragePolicyConfig(maxDays = 1), now))
    }

    @Test
    fun duplicateIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            segmentsToEvict(listOf(seg("a", 1), seg("a", 2)), StoragePolicyConfig(maxDays = 1), now)
        }
    }

    @Test
    fun negativeLimitsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { StoragePolicyConfig(maxDays = -1) }
        assertThrows(IllegalArgumentException::class.java) { StoragePolicyConfig(maxBytes = -1L) }
    }
}

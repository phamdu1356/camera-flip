package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class StorageEnforcementTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 100L * day

    private fun row(
        id: String = UUID.randomUUID().toString(),
        startUtc: Long,
        bytes: Long = 1_000L,
        status: SegmentStatus = SegmentStatus.READY,
        pinned: Boolean = false,
    ) = SegmentEntity(
        id = id,
        sessionId = UUID.randomUUID().toString(),
        startUtc = startUtc,
        endUtc = startUtc + 60_000L,
        duration = 60_000L,
        bytes = bytes,
        internalName = "seg-$id.mp4",
        path = "/private/segments/seg-$id.mp4",
        status = status,
        isProtected = pinned,
    )

    @Test
    fun defaultPolicyNeverSelectsAnything() {
        val rows = listOf(row(startUtc = 0L, bytes = 1L shl 40), row(startUtc = 1L * day))
        assertEquals(emptyList<String>(), storageEvictionIds(rows, StoragePolicyConfig(), now))
    }

    @Test
    fun onlyReadyRowsAreOffered() {
        val old = 0L
        val recording = row(startUtc = old, status = SegmentStatus.RECORDING)
        val failed = row(startUtc = old, status = SegmentStatus.FAILED)
        val deleted = row(startUtc = old, status = SegmentStatus.DELETED)
        val ready = row(startUtc = old)
        val ids = storageEvictionIds(
            listOf(recording, failed, deleted, ready),
            StoragePolicyConfig(maxDays = 1),
            now,
        )
        assertEquals(listOf(ready.id), ids)
    }

    @Test
    fun excludedInFlightSegmentIsNeverSelected() {
        val inFlight = row(startUtc = 0L)
        val ids = storageEvictionIds(
            listOf(inFlight),
            StoragePolicyConfig(maxDays = 1),
            now,
            excludeIds = setOf(inFlight.id),
        )
        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun maxDaysEvictsOnlyOlderUnprotectedRows() {
        val oldUnprotected = row(startUtc = 1L * day)
        val oldProtected = row(startUtc = 2L * day, pinned = true)
        val recent = row(startUtc = 99L * day)
        val ids = storageEvictionIds(
            listOf(recent, oldProtected, oldUnprotected),
            StoragePolicyConfig(maxDays = 30),
            now,
        )
        assertEquals(listOf(oldUnprotected.id), ids)
    }

    @Test
    fun maxBytesEvictsOldestUntilUnderLimitAndCountsProtectedBytes() {
        val a = row(startUtc = 1L * day, bytes = 400L)
        val b = row(startUtc = 2L * day, bytes = 400L)
        val c = row(startUtc = 3L * day, bytes = 400L, pinned = true)
        val ids = storageEvictionIds(listOf(c, b, a), StoragePolicyConfig(maxBytes = 900L), now)
        assertEquals(listOf(a.id), ids)
    }

    @Test
    fun parseAcceptsZeroAsUnlimitedAndDecimalGb() {
        val config = parseStoragePolicyInput(RecordingMode.MOTION_ONLY, " 7 ", "1,5")
        assertEquals(RecordingMode.MOTION_ONLY, config?.mode)
        assertEquals(7, config?.maxDays)
        assertEquals(1_610_612_736L, config?.maxBytes)

        val unlimited = parseStoragePolicyInput(RecordingMode.CONTINUOUS, "0", "0")
        assertEquals(StoragePolicyConfig(RecordingMode.CONTINUOUS, 0, 0L), unlimited)
    }

    @Test
    fun parseRejectsInvalidInputInsteadOfBecomingUnlimited() {
        val mode = RecordingMode.CONTINUOUS
        assertNull(parseStoragePolicyInput(mode, "", "1"))
        assertNull(parseStoragePolicyInput(mode, "-1", "1"))
        assertNull(parseStoragePolicyInput(mode, "1.5", "1"))
        assertNull(parseStoragePolicyInput(mode, "36501", "1"))
        assertNull(parseStoragePolicyInput(mode, "1", "abc"))
        assertNull(parseStoragePolicyInput(mode, "1", "NaN"))
        assertNull(parseStoragePolicyInput(mode, "1", "-0.5"))
        assertNull(parseStoragePolicyInput(mode, "1", "1000001"))
        // Positive but smaller than one byte would round to 0 (unlimited); reject it.
        assertNull(parseStoragePolicyInput(mode, "1", "0.0000000001"))
    }

    @Test
    fun formatStorageGbRoundTripsParsedValues() {
        assertEquals("0", formatStorageGb(0L))
        assertEquals("1.5", formatStorageGb(1_610_612_736L))
        val bytes = 3_221_225_473L
        val text = formatStorageGb(bytes)
        assertEquals(bytes, parseStoragePolicyInput(RecordingMode.CONTINUOUS, "0", text)?.maxBytes)
        assertTrue(text.isNotEmpty())
    }

    @Test
    fun fortyGbBoundaryExactDoesNotEvict() {
        val fortyGb = StoragePolicyStore.DEFAULT_MAX_STORAGE_BYTES // 42_949_672_960L
        val half = fortyGb / 2
        val a = row(startUtc = 1L * day, bytes = half)
        val b = row(startUtc = 2L * day, bytes = fortyGb - half)
        val ids = storageEvictionIds(listOf(a, b), StoragePolicyConfig(maxBytes = fortyGb), now)
        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun fortyGbBoundaryExceededEvictsOldestUnprotectedOnly() {
        val fortyGb = StoragePolicyStore.DEFAULT_MAX_STORAGE_BYTES
        val a = row(startUtc = 1L * day, bytes = 10L * 1024L * 1024L * 1024L) // 10 GB
        val b = row(startUtc = 2L * day, bytes = 15L * 1024L * 1024L * 1024L) // 15 GB
        val c = row(startUtc = 3L * day, bytes = 20L * 1024L * 1024L * 1024L) // 20 GB -> total 45 GB > 40 GB
        val ids = storageEvictionIds(listOf(c, b, a), StoragePolicyConfig(maxBytes = fortyGb), now)
        // Evicting 'a' (10 GB) reduces total to 35 GB <= 40 GB.
        assertEquals(listOf(a.id), ids)
    }

    @Test
    fun fortyGbBoundaryWithProtectedOldestSkipsProtectedAndEvictsNextOldest() {
        val fortyGb = StoragePolicyStore.DEFAULT_MAX_STORAGE_BYTES
        val protectedOld = row(startUtc = 1L * day, bytes = 10L * 1024L * 1024L * 1024L, pinned = true) // 10 GB protected
        val unprotectedMiddle = row(startUtc = 2L * day, bytes = 15L * 1024L * 1024L * 1024L) // 15 GB
        val unprotectedRecent = row(startUtc = 3L * day, bytes = 20L * 1024L * 1024L * 1024L) // 20 GB -> total 45 GB > 40 GB
        val ids = storageEvictionIds(listOf(unprotectedRecent, unprotectedMiddle, protectedOld), StoragePolicyConfig(maxBytes = fortyGb), now)
        // protectedOld must be preserved; unprotectedMiddle is evicted.
        assertEquals(listOf(unprotectedMiddle.id), ids)
    }

    @Test
    fun readAllReadySegmentsHandlesMoreThan200SegmentsWithoutDuplicateIdsOrCrash() = kotlinx.coroutines.runBlocking {
        val totalSegments = 250
        val baseStart = 1_000_000L
        val fakeDb = (0 until totalSegments).map { i ->
            val uuid = java.util.UUID(0L, (i + 1).toLong()).toString()
            row(
                id = uuid,
                startUtc = baseStart + i * 60_000L,
                bytes = 100_000_000L, // 100 MB each
                status = SegmentStatus.READY,
            )
        }.sortedWith(compareBy({ it.startUtc }, { it.id }))

        val dao = object : TestSegmentDao() {
            override suspend fun getReadySegmentsPage(
                afterStart: Long,
                afterId: String,
                limit: Int,
            ): List<SegmentEntity> {
                return fakeDb.filter {
                    it.startUtc > afterStart || (it.startUtc == afterStart && it.id > afterId)
                }.take(limit)
            }
        }

        val all = dao.readAllReadySegments()
        assertEquals("Should retrieve all 250 segments across pages", 250, all.size)
        val distinctIds = all.map { it.id }.distinct()
        assertEquals("All segment IDs must be unique across pages (no duplicate boundaries)", 250, distinctIds.size)

        // Verify that storageEvictionIds processes all 250 segments without IllegalArgumentException
        val config = StoragePolicyConfig(maxBytes = 40L * 1024L * 1024L * 1024L) // 40 GB
        val evictionIds = storageEvictionIds(all, config, nowUtc = baseStart + 300 * 60_000L)
        // 250 * 100 MB = 25,000 MB (~25 GB) <= 40 GB limit, so 0 evicted
        assertEquals(0, evictionIds.size)

        // Now test with limit of 10 GB (100 segments to keep, 150 to evict)
        val smallLimitConfig = StoragePolicyConfig(maxBytes = 10L * 1000L * 1000L * 1000L)
        val evict150 = storageEvictionIds(all, smallLimitConfig, nowUtc = baseStart + 300 * 60_000L)
        assertTrue("Should evict segments when total exceeds limit", evict150.isNotEmpty())
        assertEquals("Should evict oldest segments in order", (0 until evict150.size).map { java.util.UUID(0L, (it + 1).toLong()).toString() }, evict150)
    }

    @Test
    fun fiveMinuteSegmentsWithContinuousDurationEvictionWorksCorrectly() {
        // Continuous segment duration is 5 minutes (300,000 ms)
        val segmentDurationMs = CONTINUOUS_SEGMENT_DURATION_MS
        assertEquals(300_000L, segmentDurationMs)

        val fortyGb = StoragePolicyStore.DEFAULT_MAX_STORAGE_BYTES
        val segmentBytes = 80L * 1024L * 1024L // ~80 MB per 5-minute segment
        val baseTime = 1_000_000_000L

        // Generate 600 segments of 5 minutes each (~48 GB total > 40 GB limit)
        val segments = (0 until 600).map { i ->
            row(
                id = java.util.UUID(0L, (i + 1).toLong()).toString(),
                startUtc = baseTime + i * segmentDurationMs,
                bytes = segmentBytes,
                status = SegmentStatus.READY,
                pinned = (i == 0), // Oldest segment is pinned (protected)
            )
        }

        val config = StoragePolicyConfig(mode = RecordingMode.CONTINUOUS, maxBytes = fortyGb)
        val evictedIds = storageEvictionIds(segments, config, nowUtc = baseTime + 600 * segmentDurationMs)

        // Oldest segment (pinned) must NOT be evicted
        val oldestId = java.util.UUID(0L, 1L).toString()
        assertFalse("Protected oldest segment must be preserved", evictedIds.contains(oldestId))

        // Total capacity fits ~512 segments of 80MB. 600 segments exceed limit, so around 88 segments must be evicted.
        assertTrue("Segments exceeding 40GB limit must be evicted", evictedIds.isNotEmpty())

        // The second oldest segment (unprotected) must be the first one evicted
        val secondOldestId = java.util.UUID(0L, 2L).toString()
        assertEquals("Second oldest unprotected segment must be first evicted", secondOldestId, evictedIds.first())
    }

    private open class TestSegmentDao : SegmentDao() {
        override suspend fun insertRecording(segment: SegmentEntity) = Unit
        override suspend fun getById(id: String): SegmentEntity? = null
        override suspend fun getRecordingSegmentsForRecovery(): List<SegmentEntity> = emptyList()
        override fun getAllSegmentsFlow(): kotlinx.coroutines.flow.Flow<List<SegmentEntity>> = kotlinx.coroutines.flow.emptyFlow()
        override fun getSegmentsBySession(sessionId: String): kotlinx.coroutines.flow.Flow<List<SegmentEntity>> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun selectReadyTimeline(fromUtc: Long, toUtc: Long?, limit: Int): List<SegmentEntity> = emptyList()
        override suspend fun getReadyTimelinePage(fromUtc: Long, toUtc: Long, afterStart: Long, afterId: String, afterType: String, limit: Int): List<SegmentEntity> = emptyList()
        override suspend fun getReadySegmentsPage(afterStart: Long, afterId: String, limit: Int): List<SegmentEntity> = emptyList()
        override suspend fun getNextReady(afterStart: Long, afterId: String): SegmentEntity? = null
        override suspend fun setProtected(id: String, isProtected: Boolean, expectedStatus: SegmentStatus, expectedRevision: Int): Int = 0
        override suspend fun publishReady(id: String, expectedRevision: Int, startUtc: Long, endUtc: Long, duration: Long, bytes: Long, width: Int, height: Int, codec: String, audio: Boolean, internalName: String, path: String): Int = 0
        override suspend fun failRecording(id: String, expectedRevision: Int): Int = 0
        override suspend fun getExpiredSegments(cutoffUtc: Long): List<SegmentEntity> = emptyList()
        override suspend fun selectDeletedSegmentsForCleanup(afterId: String?, limit: Int): List<SegmentEntity> = emptyList()
        override suspend fun markDeleted(id: String, expectedStatus: SegmentStatus, expectedRevision: Int): Int = 0
        override suspend fun tombstoneReadyEventsForSegment(segmentId: String): Int = 0
        override suspend fun insertGapRecord(gap: GapEntity) = Unit
        override suspend fun selectGaps(fromUtc: Long, toUtc: Long?, limit: Int): List<GapEntity> = emptyList()
        override suspend fun getGapTimelinePage(fromUtc: Long, toUtc: Long, afterStart: Long, afterId: String, afterType: String, limit: Int): List<GapEntity> = emptyList()
    }
}

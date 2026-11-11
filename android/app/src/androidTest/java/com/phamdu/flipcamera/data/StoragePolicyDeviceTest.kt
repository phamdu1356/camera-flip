package com.phamdu.flipcamera.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Field
import java.util.UUID

/**
 * Device check of SegmentPruner and StoragePolicy against real Room and real files, without
 * touching the app's own data.
 *
 * Isolation:
 * - Metadata: an in-memory [CameraDatabase]. The app's flip_camera.db is never opened.
 * - Files: a temp tree under cacheDir, exposed to SegmentFileStore/MotionThumbnailStore via
 *   [IsolatedStorageContext] whose filesDir is the temp tree. filesDir/segments is never touched.
 * - RetentionManager resolves its event DAO through CameraDatabase.getInstance(). That singleton
 *   is pointed at the in-memory database for this test only (reflection on CameraDatabase.INSTANCE)
 *   and the previous value is restored in @After.
 */
@RunWith(AndroidJUnit4::class)
class StoragePolicyDeviceTest {
    private val baseContext: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val segmentBase = now - 2 * DAY_MS
    private val sessionId = UUID.randomUUID().toString()

    private lateinit var tempRoot: File
    private lateinit var segmentsDir: File
    private lateinit var database: CameraDatabase
    private lateinit var segmentDao: SegmentDao
    private lateinit var motionDao: MotionEventDao
    private lateinit var retention: RetentionManager
    private lateinit var instanceField: Field
    private var previousInstance: CameraDatabase? = null

    private data class StoredSegment(val id: String, val startUtc: Long, val file: File)

    @Before
    fun setUp() {
        tempRoot = File(baseContext.cacheDir, "storage-policy-${UUID.randomUUID()}").canonicalFile
        val privateFiles = File(tempRoot, "files").apply { mkdirs() }.canonicalFile
        segmentsDir = File(privateFiles, "segments").apply { mkdirs() }

        instanceField = CameraDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        previousInstance = instanceField.get(null) as CameraDatabase?

        database = Room.inMemoryDatabaseBuilder(baseContext, CameraDatabase::class.java).build()
        instanceField.set(null, database)
        segmentDao = database.segmentDao()
        motionDao = database.motionEventDao()

        val isolated = IsolatedStorageContext(baseContext, privateFiles)
        retention = RetentionManager(isolated, segmentDao)
    }

    @After
    fun tearDown() {
        instanceField.set(null, previousInstance)
        database.close()
        tempRoot.deleteRecursively()
    }

    @Test
    fun motionOnlyBacklogPruneDeletesOnlyIdleUnprotectedSegments() = runBlocking {
        val idle = insertReadySegment(segmentBase)
        val withMotion = insertReadySegment(segmentBase + 100_000L)
        val protectedSegment = insertReadySegment(segmentBase + 200_000L, protected = true)
        // Starts before the grace cutoff but ended only 25 s ago, so it is still inside graceMs.
        val endingInGrace = insertReadySegment(now - 40_000L)
        val motion = insertEvent(segmentBase + 105_000L, segmentBase + 106_000L)

        val deleted = pruner(RecordingMode.MOTION_ONLY).pruneBacklog(now)

        assertEquals(1, deleted)
        assertEquals(SegmentStatus.DELETED, segmentDao.getById(idle.id)?.status)
        assertFalse("idle segment file should be removed", idle.file.exists())

        assertEquals(SegmentStatus.READY, segmentDao.getById(withMotion.id)?.status)
        assertTrue("segment overlapping a motion event must stay", withMotion.file.exists())

        assertEquals(SegmentStatus.READY, segmentDao.getById(protectedSegment.id)?.status)
        assertTrue("protected segment must stay", protectedSegment.file.exists())

        assertEquals(SegmentStatus.READY, segmentDao.getById(endingInGrace.id)?.status)
        assertTrue("segment still inside graceMs must stay", endingInGrace.file.exists())

        assertEquals(MotionEventStatus.READY, motionDao.getById(motion.id)?.status)
    }

    @Test
    fun ongoingMotionStartProtectsItsContainingSegmentUntilTheIntervalCompletes() = runBlocking {
        val old = insertReadySegment(segmentBase)
        val inside = insertReadySegment(segmentBase + 500_000L)
        val ongoingStart = segmentBase + 510_000L
        val pruner = pruner(RecordingMode.MOTION_ONLY)

        // The ongoing interval extends to nowUtc, so the segment holding its start must stay.
        assertEquals(1, pruner.pruneBacklog(now, ongoingMotionStartUtc = ongoingStart))
        assertEquals(SegmentStatus.DELETED, segmentDao.getById(old.id)?.status)
        assertFalse(old.file.exists())
        assertEquals(SegmentStatus.READY, segmentDao.getById(inside.id)?.status)
        assertTrue(inside.file.exists())

        // Once the interval is closed (no ongoing start), the same segment becomes idle and is pruned.
        assertEquals(1, pruner.pruneBacklog(now))
        assertEquals(SegmentStatus.DELETED, segmentDao.getById(inside.id)?.status)
        assertFalse(inside.file.exists())
    }

    @Test
    fun continuousModeNeverPrunesIdleSegments() = runBlocking {
        val idle = insertReadySegment(segmentBase)
        val protectedSegment = insertReadySegment(segmentBase + 200_000L, protected = true)
        val endingInGrace = insertReadySegment(now - 40_000L)

        val pruner = pruner(RecordingMode.CONTINUOUS)

        assertEquals(0, pruner.pruneBacklog(now))
        assertFalse(pruner.pruneIfNoMotion(idle.id, now))
        assertEquals(SegmentStatus.READY, segmentDao.getById(idle.id)?.status)
        assertEquals(SegmentStatus.READY, segmentDao.getById(protectedSegment.id)?.status)
        assertEquals(SegmentStatus.READY, segmentDao.getById(endingInGrace.id)?.status)
        assertTrue(idle.file.exists())
        assertTrue(protectedSegment.file.exists())
        assertTrue(endingInGrace.file.exists())
    }

    @Test
    fun byteLimitEvictsOldestUnprotectedSegmentsAndKeepsProtectedOnes() = runBlocking {
        val s1 = insertReadySegment(segmentBase)
        val s2 = insertReadySegment(segmentBase + 60_000L, protected = true)
        val s3 = insertReadySegment(segmentBase + 120_000L)
        val s4 = insertReadySegment(segmentBase + 180_000L)
        val s5 = insertReadySegment(segmentBase + 240_000L)
        val stored = listOf(s1, s2, s3, s4, s5)

        // Five 100-byte segments = 500 bytes against a 250-byte limit.
        val candidates = stored.map { StorageSegment(it.id, it.startUtc, FILE_BYTES, it.id == s2.id) }
        val ids = segmentsToEvict(candidates, StoragePolicyConfig(maxBytes = 250L), now)

        assertEquals(listOf(s1.id, s3.id, s4.id), ids)
        assertEquals(3, pruner(RecordingMode.CONTINUOUS).evictSegments(ids))

        for (evicted in listOf(s1, s3, s4)) {
            assertEquals(SegmentStatus.DELETED, segmentDao.getById(evicted.id)?.status)
            assertFalse("evicted file should be removed", evicted.file.exists())
        }
        for (kept in listOf(s2, s5)) {
            assertEquals(SegmentStatus.READY, segmentDao.getById(kept.id)?.status)
            assertTrue("kept file should remain", kept.file.exists())
        }
        val remainingBytes = segmentDao.getReadyTimeline(0L, null).sumOf { it.bytes }
        assertEquals(200L, remainingBytes)
        assertTrue(remainingBytes <= 250L)
    }

    @Test
    fun ageLimitEvictsOnlyUnprotectedSegmentsOlderThanTheCutoff() = runBlocking {
        val old = insertReadySegment(now - 3 * DAY_MS)
        val oldProtected = insertReadySegment(now - 3 * DAY_MS + 1_000L, protected = true)
        val recent = insertReadySegment(now - HOUR_MS)

        val ids = segmentsToEvict(
            listOf(
                StorageSegment(old.id, old.startUtc, FILE_BYTES, isProtected = false),
                StorageSegment(oldProtected.id, oldProtected.startUtc, FILE_BYTES, isProtected = true),
                StorageSegment(recent.id, recent.startUtc, FILE_BYTES, isProtected = false),
            ),
            StoragePolicyConfig(maxDays = 2),
            now,
        )

        assertEquals(listOf(old.id), ids)
        assertEquals(1, pruner(RecordingMode.CONTINUOUS).evictSegments(ids))
        assertEquals(SegmentStatus.DELETED, segmentDao.getById(old.id)?.status)
        assertFalse(old.file.exists())
        assertEquals(SegmentStatus.READY, segmentDao.getById(oldProtected.id)?.status)
        assertTrue(oldProtected.file.exists())
        assertEquals(SegmentStatus.READY, segmentDao.getById(recent.id)?.status)
        assertTrue(recent.file.exists())
    }

    @Test
    fun defaultZeroLimitsNeverEvictEvenOldSegments() = runBlocking {
        val ancient = insertReadySegment(now - 365 * DAY_MS)
        val other = insertReadySegment(now - 2 * DAY_MS)
        val candidates = listOf(
            StorageSegment(ancient.id, ancient.startUtc, FILE_BYTES, isProtected = false),
            StorageSegment(other.id, other.startUtc, FILE_BYTES, isProtected = false),
        )

        assertTrue(segmentsToEvict(candidates, StoragePolicyConfig(), now).isEmpty())
        assertEquals(SegmentStatus.READY, segmentDao.getById(ancient.id)?.status)
        assertEquals(SegmentStatus.READY, segmentDao.getById(other.id)?.status)
        assertTrue(ancient.file.exists())
        assertTrue(other.file.exists())
    }

    @Test
    fun evictionTombstonesRowsAndLinkedEventsAndIsIdempotent() = runBlocking {
        val segment = insertReadySegment(segmentBase)
        val linked = insertEvent(segmentBase + 1_000L, segmentBase + 2_000L, segmentId = segment.id)
        val pruner = pruner(RecordingMode.MOTION_ONLY)

        // A linked READY event blocks motion-only pruning; the storage-policy path does not check it.
        assertEquals(0, pruner.pruneBacklog(now))
        assertEquals(SegmentStatus.READY, segmentDao.getById(segment.id)?.status)

        assertEquals(1, pruner.evictSegments(listOf(segment.id)))
        assertFalse(segment.file.exists())

        val row = segmentDao.getById(segment.id)
        assertEquals(SegmentStatus.DELETED, row?.status)
        assertTrue("DELETED keeps metadata as a tombstone", row != null)
        assertEquals(MotionEventStatus.DELETED, motionDao.getById(linked.id)?.status)
        assertTrue(motionDao.getTimeline(0L, null).none { it.id == linked.id })
        assertTrue(segmentDao.getReadyTimeline(0L, null).none { it.id == segment.id })

        // A second eviction of the same id must change nothing.
        assertEquals(0, pruner.evictSegments(listOf(segment.id)))
        assertEquals(SegmentStatus.DELETED, segmentDao.getById(segment.id)?.status)
    }

    private fun pruner(mode: RecordingMode) =
        SegmentPruner(segmentDao, motionDao, retention) { mode }

    private suspend fun insertReadySegment(startUtc: Long, protected: Boolean = false): StoredSegment {
        val id = UUID.randomUUID().toString()
        val finalName = "$id.mp4"
        val finalFile = File(segmentsDir, finalName)
        finalFile.writeBytes(ByteArray(FILE_BYTES.toInt()) { 1 })
        val pendingName = "$id.pending.mp4"
        segmentDao.insert(
            SegmentEntity(
                id = id,
                sessionId = sessionId,
                startUtc = startUtc,
                internalName = pendingName,
                path = File(segmentsDir, pendingName).absolutePath,
            ),
        )
        assertEquals(
            1,
            segmentDao.finalizeSegment(
                id = id,
                expectedRevision = 1,
                startUtc = startUtc,
                endUtc = startUtc + SEGMENT_MS,
                duration = SEGMENT_MS,
                bytes = FILE_BYTES,
                width = 1280,
                height = 720,
                codec = "video/avc",
                audio = false,
                finalInternalName = finalName,
                finalPath = finalFile.absolutePath,
            ),
        )
        if (protected) {
            assertEquals(1, segmentDao.setProtected(id, true, SegmentStatus.READY, expectedRevision = 2))
        }
        return StoredSegment(id, startUtc, finalFile)
    }

    private suspend fun insertEvent(startUtc: Long, endUtc: Long, segmentId: String? = null): MotionEventEntity {
        val event = MotionEventEntity(
            sessionId = sessionId,
            segmentId = segmentId,
            startUtc = startUtc,
            endUtc = endUtc,
            peakScore = 0.5f,
        )
        assertTrue(motionDao.insert(event))
        return event
    }

    /** Redirects filesDir into the temp tree; applicationContext returns this wrapper so no real app storage is reached. */
    private class IsolatedStorageContext(base: Context, private val privateFiles: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = privateFiles
        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val SEGMENT_MS = 15_000L
        private const val FILE_BYTES = 100L
        private const val HOUR_MS = 60L * 60L * 1000L
        private const val DAY_MS = 24L * HOUR_MS
    }
}

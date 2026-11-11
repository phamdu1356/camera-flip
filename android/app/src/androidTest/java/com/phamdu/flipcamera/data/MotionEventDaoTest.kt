package com.phamdu.flipcamera.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MotionEventDaoTest {
    private lateinit var database: CameraDatabase
    private lateinit var dao: MotionEventDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CameraDatabase::class.java).build()
        dao = database.motionEventDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun timelineUsesOverlappingBoundariesAndThumbnailUpdateUsesRevisionCas() = runBlocking {
        val segmentId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        insertReadySegment(segmentId, sessionId, startUtc = 0L, endUtc = 10_000L)
        val event = MotionEventEntity(
            sessionId = sessionId,
            segmentId = segmentId,
            startUtc = 1_000L,
            endUtc = 2_000L,
            peakScore = 0.75f,
        )
        dao.insert(event)
        val newer = MotionEventEntity(
            sessionId = event.sessionId,
            startUtc = 3_000L,
            endUtc = 4_000L,
            peakScore = 0.5f,
        )
        dao.insert(newer)

        assertEquals(listOf(event.id), dao.getTimeline(1_500L, 1_600L).map { it.id })
        assertEquals(listOf(event.id), dao.getTimeline(0L, 1_500L).map { it.id })
        assertEquals(emptyList<MotionEventEntity>(), dao.getTimeline(2_000L, 3_000L))
        assertEquals(listOf(newer.id), dao.getTimeline(0L, 5_000L, limit = 1).map { it.id })
        assertEquals(0, dao.attachThumbnail(event.id, "${event.id}.jpg", expectedRevision = 2))
        assertEquals(1, dao.attachThumbnail(event.id, "${event.id}.jpg", expectedRevision = 1))
        assertEquals(0, dao.attachThumbnail(event.id, "${event.id}.jpg", expectedRevision = 1))

        val stored = dao.getById(event.id)
        assertEquals(segmentId, stored?.segmentId)
        assertEquals("${event.id}.jpg", stored?.thumbnailName)
        assertEquals(2, stored?.revision)
        assertEquals(listOf(event.id), dao.getReadyForSegment(segmentId).map { it.id })
        assertEquals(
            listOf(MotionEventThumbnailReference(event.id, "${event.id}.jpg")),
            dao.getThumbnailReferences().filter { it.id == event.id },
        )
    }

    @Test
    fun timelineAndThumbnailNameInputsAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.getTimeline(1_000L, 2_000L, limit = 501) }
        }

        val event = MotionEventEntity(
            sessionId = null,
            startUtc = 1_000L,
            endUtc = 2_000L,
            peakScore = 0.5f,
        )
        runBlocking { dao.insert(event) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.attachThumbnail(event.id, "../../other.jpg", expectedRevision = 1) }
        }
    }

    @Test
    fun expiredEventsUseTombstonesUntilThumbnailCleanupSucceeds() = runBlocking {
        val event = MotionEventEntity(
            sessionId = UUID.randomUUID().toString(),
            startUtc = 1_000L,
            endUtc = 2_000L,
            peakScore = 0.5f,
        )
        dao.insert(event)

        assertEquals(listOf(event.id), dao.getExpired(cutoffUtc = 3_000L).map { it.id })
        assertEquals(0, dao.markDeleted(event.id, expectedRevision = 2))
        assertEquals(1, dao.attachThumbnail(event.id, "${event.id}.jpg", expectedRevision = 1))
        assertEquals(1, dao.markDeleted(event.id, expectedRevision = 2))
        assertEquals(0, dao.markDeleted(event.id, expectedRevision = 2))

        assertTrue(dao.getTimeline(0L, null).isEmpty())
        assertEquals(listOf(event.id), dao.getDeletedForCleanup().map { it.id })
        assertEquals(0, dao.confirmThumbnailDeleted(event.id, "${event.id}.jpg", expectedRevision = 2))
        assertEquals(1, dao.confirmThumbnailDeleted(event.id, "${event.id}.jpg", expectedRevision = 3))
        assertTrue(dao.getDeletedForCleanup().isEmpty())
        assertTrue(dao.getExpired(cutoffUtc = 3_000L).isEmpty())
    }

    @Test
    fun retentionKeepsEventsLinkedToProtectedSegments() = runBlocking {
        val segmentDao = database.segmentDao()
        val segmentId = UUID.randomUUID().toString()
        val segment = SegmentEntity(
            id = segmentId,
            sessionId = UUID.randomUUID().toString(),
            startUtc = 1_000L,
            internalName = "$segmentId.pending.mp4",
            path = "/private/$segmentId.pending.mp4",
        )
        segmentDao.insert(segment)
        assertEquals(
            1,
            segmentDao.finalizeSegment(
                id = segmentId,
                expectedRevision = 1,
                startUtc = 1_000L,
                endUtc = 61_000L,
                duration = 60_000L,
                bytes = 1_000L,
                width = 1280,
                height = 720,
                codec = "video/avc",
                audio = false,
                finalInternalName = "$segmentId.mp4",
                finalPath = "/private/$segmentId.mp4",
            ),
        )
        assertEquals(1, segmentDao.setProtected(segmentId, true, SegmentStatus.READY, expectedRevision = 2))
        dao.insert(
            MotionEventEntity(
                sessionId = segment.sessionId,
                segmentId = segmentId,
                startUtc = 2_000L,
                endUtc = 3_000L,
                peakScore = 0.5f,
            ),
        )

        assertTrue(dao.getExpired(cutoffUtc = 10_000L).isEmpty())
    }

    @Test
    fun deletingSegmentAtomicallyTombstonesLinkedEvents() = runBlocking {
        val segmentDao = database.segmentDao()
        val segmentId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        segmentDao.insert(
            SegmentEntity(
                id = segmentId,
                sessionId = sessionId,
                startUtc = 1_000L,
                internalName = "$segmentId.pending.mp4",
                path = "/private/$segmentId.pending.mp4",
            ),
        )
        assertEquals(
            1,
            segmentDao.finalizeSegment(
                id = segmentId,
                expectedRevision = 1,
                startUtc = 1_000L,
                endUtc = 61_000L,
                duration = 60_000L,
                bytes = 1_000L,
                width = 1280,
                height = 720,
                codec = "video/avc",
                audio = false,
                finalInternalName = "$segmentId.mp4",
                finalPath = "/private/$segmentId.mp4",
            ),
        )
        val event = MotionEventEntity(
            sessionId = sessionId,
            segmentId = segmentId,
            startUtc = 2_000L,
            endUtc = 3_000L,
            peakScore = 0.5f,
        )
        dao.insert(event)

        assertEquals(1, segmentDao.markDeletedWithEvents(segmentId, SegmentStatus.READY, 2))
        assertEquals(MotionEventStatus.DELETED, dao.getById(event.id)?.status)
        assertTrue(dao.getTimeline(0L, null).isEmpty())

        val lateEvent = MotionEventEntity(
            sessionId = sessionId,
            segmentId = segmentId,
            startUtc = 4_000L,
            endUtc = 5_000L,
            peakScore = 0.5f,
        )
        assertFalse(dao.insert(lateEvent))
        assertEquals(null, dao.getById(lateEvent.id))
    }

    @Test
    fun eventCanBePersistedWhileItsSegmentIsStillRecording() = runBlocking {
        val segmentId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        database.segmentDao().insert(
            SegmentEntity(
                id = segmentId,
                sessionId = sessionId,
                startUtc = 1_000L,
                internalName = "$segmentId.pending.mp4",
                path = "/private/$segmentId.pending.mp4",
            ),
        )
        val event = MotionEventEntity(
            sessionId = sessionId,
            segmentId = segmentId,
            startUtc = 2_000L,
            endUtc = 3_000L,
            peakScore = 0.5f,
        )

        assertTrue(dao.insert(event))
        assertEquals(event.id, dao.getById(event.id)?.id)
    }

    @Test
    fun concurrentEventInsertAndSegmentDeleteNeverLeavesReadyEventOnDeletedSegment() = runBlocking {
        val segmentDao = database.segmentDao()
        val segmentId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        insertReadySegment(segmentId, sessionId, startUtc = 1_000L, endUtc = 61_000L)
        val event = MotionEventEntity(
            sessionId = sessionId,
            segmentId = segmentId,
            startUtc = 2_000L,
            endUtc = 3_000L,
            peakScore = 0.5f,
        )

        val results = coroutineScope {
            val insert = async(Dispatchers.IO) { dao.insert(event) }
            val delete = async(Dispatchers.IO) {
                segmentDao.markDeletedWithEvents(segmentId, SegmentStatus.READY, 2)
            }
            insert.await() to delete.await()
        }

        assertEquals(1, results.second)
        assertEquals(SegmentStatus.DELETED, segmentDao.getById(segmentId)?.status)
        val storedEvent = dao.getById(event.id)
        assertTrue(storedEvent == null || storedEvent.status == MotionEventStatus.DELETED)
        assertFalse(dao.getTimeline(0L, null).any { it.segmentId == segmentId })
    }

    private suspend fun insertReadySegment(
        segmentId: String,
        sessionId: String,
        startUtc: Long,
        endUtc: Long,
    ) {
        val segmentDao = database.segmentDao()
        segmentDao.insert(
            SegmentEntity(
                id = segmentId,
                sessionId = sessionId,
                startUtc = startUtc,
                internalName = "$segmentId.pending.mp4",
                path = "/private/$segmentId.pending.mp4",
            ),
        )
        assertEquals(
            1,
            segmentDao.finalizeSegment(
                id = segmentId,
                expectedRevision = 1,
                startUtc = startUtc,
                endUtc = endUtc,
                duration = endUtc - startUtc,
                bytes = 1_000L,
                width = 1280,
                height = 720,
                codec = "video/avc",
                audio = false,
                finalInternalName = "$segmentId.mp4",
                finalPath = "/private/$segmentId.mp4",
            ),
        )
    }
}

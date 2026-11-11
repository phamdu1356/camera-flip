package com.phamdu.flipcamera.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phamdu.flipcamera.data.CameraDatabase
import com.phamdu.flipcamera.data.MotionEventEntity
import com.phamdu.flipcamera.data.MotionThumbnailStore
import com.phamdu.flipcamera.data.SegmentEntity
import com.phamdu.flipcamera.data.SegmentStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomViewerEventSourceTest {
    private lateinit var database: CameraDatabase
    private lateinit var source: RoomViewerEventSource

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CameraDatabase::class.java).build()
        source = RoomViewerEventSource(
            database.motionEventDao(),
            database.segmentDao(),
            MotionThumbnailStore(context),
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun playbackFallsForwardToReadySegmentAndDeletedOriginCannotServeThumbnail() = runBlocking {
        val first = readySegment(0L, 60_000L)
        val next = readySegment(60_000L, 120_000L)
        val event = MotionEventEntity(
            sessionId = first.sessionId,
            segmentId = first.id,
            startUtc = 59_000L,
            endUtc = 70_000L,
            peakScore = 0.4f,
        )
        database.motionEventDao().insert(event)
        assertEquals(1, database.segmentDao().markDeleted(first.id, SegmentStatus.READY, first.revision))

        val result = source.listEvents(0L, 100_000L, 10).single()
        assertEquals(next.id, result.segmentId)
        assertEquals(next.startUtc, result.segmentStartUtc)
        assertEquals(next.duration, result.segmentDurationMs)
        assertFalse(result.thumbnailAvailable)
        assertNull(source.openThumbnail(event.id))
    }

    private suspend fun readySegment(startUtc: Long, endUtc: Long): SegmentEntity {
        val dao = database.segmentDao()
        val id = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val row = SegmentEntity(
            id = id,
            sessionId = sessionId,
            startUtc = startUtc,
            internalName = "$id.pending.mp4",
            path = "/private/$id.pending.mp4",
        )
        dao.insert(row)
        assertEquals(
            1,
            dao.finalizeSegment(
                id = id,
                expectedRevision = 1,
                startUtc = startUtc,
                endUtc = endUtc,
                duration = endUtc - startUtc,
                bytes = 1_000L,
                width = 1280,
                height = 720,
                codec = "video/avc",
                audio = false,
                finalInternalName = "$id.mp4",
                finalPath = "/private/$id.mp4",
            ),
        )
        return requireNotNull(dao.getById(id))
    }
}

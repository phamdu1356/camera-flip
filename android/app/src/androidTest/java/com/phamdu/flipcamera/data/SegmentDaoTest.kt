package com.phamdu.flipcamera.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SegmentDaoTest {
    private lateinit var database: CameraDatabase
    private lateinit var dao: SegmentDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CameraDatabase::class.java).build()
        dao = database.segmentDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun onlyCurrentRecordingCanPublishMeasuredReadyMetadata() = runBlocking {
        val recording = newRecording()
        dao.insert(recording)

        assertEquals(0, dao.finalizeSegment(
            id = recording.id,
            expectedRevision = 2,
            startUtc = 1_000L,
            endUtc = 3_000L,
            duration = 2_000L,
            bytes = 20_000L,
            width = 1920,
            height = 1080,
            codec = "video/avc",
            audio = false,
            finalInternalName = "final.mp4",
            finalPath = "/private/segments/final.mp4",
        ))
        assertEquals(1, dao.finalizeSegment(
            id = recording.id,
            expectedRevision = 1,
            startUtc = 1_000L,
            endUtc = 3_000L,
            duration = 2_000L,
            bytes = 20_000L,
            width = 1920,
            height = 1080,
            codec = "video/avc",
            audio = false,
            finalInternalName = "final.mp4",
            finalPath = "/private/segments/final.mp4",
        ))

        val stored = dao.getById(recording.id)
        assertNotNull(stored)
        assertEquals(SegmentStatus.READY, stored?.status)
        assertEquals(2, stored?.revision)
        assertEquals(1920, stored?.width)
        assertEquals(1080, stored?.height)
        assertEquals(2_000L, stored?.duration)
        assertEquals(20_000L, stored?.bytes)
        assertEquals("final.mp4", stored?.internalName)
    }

    @Test
    fun failedAndProtectedRowsCannotBeDeletedAsExpired() = runBlocking {
        val ready = newRecording().also { dao.insert(it) }
        assertEquals(1, dao.finalizeSegment(
            ready.id, 1, 1_000L, 2_000L, 1_000L, 4_000L, 1280, 720,
            "video/avc", false, "protected.mp4", "/private/segments/protected.mp4",
        ))
        assertEquals(1, dao.setProtected(ready.id, true, SegmentStatus.READY, 2))
        assertEquals(0, dao.markDeleted(ready.id, SegmentStatus.READY, 3))
        assertTrue(dao.getExpiredSegments(cutoffUtc = 10_000L).isEmpty())

        val failed = newRecording()
        dao.insert(failed)
        assertEquals(1, dao.failRecording(failed.id, expectedRevision = 1))
        assertEquals(0, dao.finalizeSegment(
            failed.id, 1, 1_000L, 2_000L, 1_000L, 4_000L, 1280, 720,
            "video/avc", false, "failed.mp4", "/private/segments/failed.mp4",
        ))
        assertEquals(SegmentStatus.FAILED, dao.getById(failed.id)?.status)
    }

    @Test
    fun recordingFailureAndUnavailableTimelineGapCommitTogetherWithRevisionCas() = runBlocking {
        val recording = newRecording(startUtc = 1_000L)
        dao.insert(recording)
        val gap = GapEntity(
            sessionId = recording.sessionId,
            startUtc = 1_000L,
            endUtc = 2_500L,
            reason = "MP4 could not be finalized",
        )

        assertEquals(1, dao.failRecordingWithGap(recording.id, expectedRevision = 1, gap = gap))
        assertEquals(SegmentStatus.FAILED, dao.getById(recording.id)?.status)
        assertEquals(listOf(gap), dao.getGaps(1_000L, 2_500L))

        val staleGap = gap.copy(id = UUID.randomUUID().toString(), endUtc = 3_000L)
        assertEquals(0, dao.failRecordingWithGap(recording.id, expectedRevision = 1, gap = staleGap))
        assertEquals(listOf(gap), dao.getGaps(1_000L, 3_000L))
    }

    @Test
    fun failedSegmentSetupWithoutARecordingRowStillPublishesTheAttemptGap() = runBlocking {
        val missingId = UUID.randomUUID().toString()
        val gap = GapEntity(
            sessionId = UUID.randomUUID().toString(),
            startUtc = 5_000L,
            endUtc = 5_250L,
            reason = "Segment setup failed before database insert",
        )

        assertEquals(0, dao.failRecordingWithGap(missingId, expectedRevision = 1, gap = gap))
        assertEquals(listOf(gap), dao.getGaps(5_000L, 5_250L))
    }

    @Test
    fun failedGapInsertRollsBackTheRecordingFailureTransition() = runBlocking {
        val recording = newRecording()
        dao.insert(recording)
        val duplicateGap = GapEntity(startUtc = 2_000L, endUtc = 3_000L, reason = "existing gap")
        dao.insertGap(duplicateGap)

        assertTrue(
            runCatching {
                dao.failRecordingWithGap(recording.id, expectedRevision = 1, gap = duplicateGap)
            }.isFailure,
        )
        assertEquals(SegmentStatus.RECORDING, dao.getById(recording.id)?.status)
        assertEquals(listOf(duplicateGap), dao.getGaps(2_000L, 3_000L))
    }

    @Test
    fun timelineAndGapQueriesUseOverlappingHalfOpenWindows() = runBlocking {
        val before = newRecording(startUtc = 1_000L)
        dao.insert(before)
        assertEquals(1, dao.finalizeSegment(
            before.id, 1, 1_000L, 2_000L, 1_000L, 4_000L, 1280, 720,
            "video/avc", false, "timeline.mp4", "/private/segments/timeline.mp4",
        ))
        val ready = dao.getReadyTimeline(fromUtc = 1_500L, toUtc = 1_600L)
        assertEquals(listOf(before.id), ready.map { it.id })
        assertTrue(dao.getReadyTimeline(fromUtc = 2_000L, toUtc = 3_000L).isEmpty())

        dao.insertGap(GapEntity(startUtc = 1_200L, endUtc = 1_800L, reason = "restart"))
        assertEquals(1, dao.getGaps(1_700L, 2_000L).size)
        assertTrue(dao.getGaps(1_800L, 2_000L).isEmpty())
    }

    @Test
    fun recoveryMarksMissingRecordingFailedAndRecordsUncoveredInterval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val pending = SegmentFileStore(context).newTempFile(id)
        assertTrue(pending.delete())
        val recording = SegmentEntity(
            id = id,
            sessionId = UUID.randomUUID().toString(),
            startUtc = 1_000L,
            internalName = "pending-$id.mp4",
            path = pending.absolutePath,
        )
        dao.insert(recording)

        val report = SegmentRecovery(context, dao).reconcile(recoveredAtUtc = 2_000L)

        assertEquals(0, report.recoveredReady)
        assertEquals(1, report.markedFailed)
        assertEquals(1, report.gapsRecorded)
        assertEquals(SegmentStatus.FAILED, dao.getById(id)?.status)
        val gaps = dao.getGaps(fromUtc = 1_000L, toUtc = 2_000L)
        assertEquals(1, gaps.size)
        assertEquals("Phân đoạn dở dang khi app khởi động lại; không có MP4 dùng được.", gaps.single().reason)
    }

    @Test
    fun retentionDeletesExpiredUnprotectedMediaAndKeepsProtectedMedia() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unprotectedId = UUID.randomUUID().toString()
        val unprotectedFile = createReadyFile(unprotectedId)
        val protectedId = UUID.randomUUID().toString()
        val protectedFile = createReadyFile(protectedId)
        assertEquals(1, dao.setProtected(protectedId, true, SegmentStatus.READY, expectedRevision = 2))

        val report = RetentionManager(context, dao).enforceAge(cutoffUtc = 3_000L)

        assertEquals(1, report.tombstonedSegments)
        assertEquals(1, report.filesDeleted)
        assertEquals(0, report.fileDeleteFailures)
        assertEquals(SegmentStatus.DELETED, dao.getById(unprotectedId)?.status)
        assertFalse(unprotectedFile.exists())
        assertEquals(SegmentStatus.READY, dao.getById(protectedId)?.status)
        assertTrue(protectedFile.exists())
    }

    private fun newRecording(startUtc: Long = 1_000L) = SegmentEntity(
        id = UUID.randomUUID().toString(),
        sessionId = UUID.randomUUID().toString(),
        startUtc = startUtc,
        internalName = "pending-${UUID.randomUUID()}.mp4",
        path = "/private/segments/pending.mp4",
    )

    private suspend fun createReadyFile(id: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SegmentFileStore(context)
        val temp = store.newTempFile(id)
        temp.writeBytes(byteArrayOf(1, 2, 3, 4))
        val final = File(temp.parentFile, "$id.mp4")
        Files.move(temp.toPath(), final.toPath())
        dao.insert(
            SegmentEntity(
                id = id,
                sessionId = UUID.randomUUID().toString(),
                startUtc = 1_000L,
                internalName = final.name,
                path = final.absolutePath,
            ),
        )
        assertEquals(
            1,
            dao.finalizeSegment(
                id, 1, 1_000L, 2_000L, 1_000L, final.length(), 1280, 720,
                "video/avc", false, final.name, final.absolutePath,
            ),
        )
        return final
    }
}

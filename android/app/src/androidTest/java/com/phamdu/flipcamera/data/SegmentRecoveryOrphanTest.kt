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
class SegmentRecoveryOrphanTest {
    private lateinit var database: CameraDatabase
    private lateinit var context: Context
    private val testArtifacts = mutableListOf<File>()

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CameraDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
        testArtifacts.forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
    }

    @Test
    fun rejectedDeterministicFinalIsRemovedOnlyAfterRowBecomesFailedAndGapIsRecorded() = runBlocking {
        val dao = database.segmentDao()
        val store = SegmentFileStore(context)
        val id = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val seedTemp = store.newTempFile(id)
        store.discardTempFile(seedTemp)
        assertFalse(seedTemp.exists())
        val corruptFinal = File(seedTemp.parentFile, "$id.mp4")
        testArtifacts += seedTemp
        testArtifacts += corruptFinal
        corruptFinal.writeBytes("not an MP4 container".toByteArray())
        assertTrue(corruptFinal.isFile)

        dao.insert(
            SegmentEntity(
                id = id,
                sessionId = sessionId,
                startUtc = 1_000L,
                internalName = seedTemp.name,
                path = seedTemp.absolutePath,
            ),
        )

        val report = SegmentRecovery(context, dao).reconcile(recoveredAtUtc = 2_000L)

        assertEquals(0, report.recoveredReady)
        assertEquals(1, report.markedFailed)
        assertEquals(1, report.gapsRecorded)
        val failed = dao.getById(id)
        assertNotNull(failed)
        assertEquals(SegmentStatus.FAILED, failed?.status)
        val gaps = dao.getGaps(fromUtc = 1_000L, toUtc = 2_000L)
        assertEquals(1, gaps.size)
        assertEquals(sessionId, gaps.single().sessionId)
        assertEquals(1_000L, gaps.single().startUtc)
        assertEquals(2_000L, gaps.single().endUtc)
        assertFalse("Invalid recovery orphan must not remain after the FAILED CAS", corruptFinal.exists())
        assertFalse("No pending temp file was adopted or left behind", seedTemp.exists())
    }

    @Test
    fun recoveryNeverDeletesSymlinksNonregularEntriesOrTheirOutOfRootTargets() = runBlocking {
        val dao = database.segmentDao()
        val segmentsDirectory = File(context.filesDir, "segments").apply { mkdirs() }
        val symlinkId = UUID.randomUUID().toString()
        val outsideTarget = File(context.cacheDir, "$symlinkId.mp4").apply {
            writeBytes("outside the segment root".toByteArray())
        }
        val symlink = File(segmentsDirectory, "$symlinkId.mp4")
        testArtifacts += outsideTarget
        testArtifacts += symlink
        Files.createSymbolicLink(symlink.toPath(), outsideTarget.toPath())

        val directoryId = UUID.randomUUID().toString()
        val nonregular = File(segmentsDirectory, "$directoryId.mp4")
        testArtifacts += nonregular
        assertTrue(nonregular.mkdir())

        dao.insert(recoveryRow(symlinkId, 1_000L))
        dao.insert(recoveryRow(directoryId, 1_000L))

        val report = SegmentRecovery(context, dao).reconcile(recoveredAtUtc = 2_000L)

        assertEquals(2, report.markedFailed)
        assertEquals(2, report.gapsRecorded)
        assertTrue(Files.isSymbolicLink(symlink.toPath()))
        assertTrue("Recovery must not follow and remove the link target", outsideTarget.isFile)
        assertTrue("A nonregular deterministic entry must be preserved", nonregular.isDirectory)
        assertEquals(SegmentStatus.FAILED, dao.getById(symlinkId)?.status)
        assertEquals(SegmentStatus.FAILED, dao.getById(directoryId)?.status)
    }

    private fun recoveryRow(id: String, startUtc: Long) = SegmentEntity(
        id = id,
        sessionId = UUID.randomUUID().toString(),
        startUtc = startUtc,
        internalName = "$id.pending.mp4",
        path = File(context.filesDir, "segments/$id.pending.mp4").absolutePath,
    )
}

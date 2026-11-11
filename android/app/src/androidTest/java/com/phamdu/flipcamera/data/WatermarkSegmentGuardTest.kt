package com.phamdu.flipcamera.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit emulator sandbox only. All file I/O is redirected away from real app media. */
@RunWith(AndroidJUnit4::class)
class WatermarkSegmentGuardTest {
    private lateinit var sandbox: Context
    private lateinit var directory: File
    private lateinit var database: CameraDatabase
    private lateinit var dao: SegmentDao

    @Before fun prepare() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("watermarkSandbox") == "true")
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "watermark-qa-${UUID.randomUUID()}").apply { check(mkdir()) }
        sandbox = object : ContextWrapper(application) {
            override fun getFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        database = Room.inMemoryDatabaseBuilder(application, CameraDatabase::class.java).build()
        dao = database.segmentDao()
    }

    @After fun cleanup() {
        if (::database.isInitialized) database.close()
        if (::directory.isInitialized) directory.deleteRecursively() // Only random QA cache directory.
    }

    @Test fun markerIsVisibleToNewHelperAndClearedOnlyByExplicitReadyCall() {
        val id = UUID.randomUUID().toString()
        val guard = WatermarkSegmentGuard(sandbox)
        assertFalse(guard.isUnverified(id))
        guard.markUnverified(id)
        assertTrue(WatermarkSegmentGuard(sandbox).isUnverified(id))
        assertThrows(Exception::class.java) { guard.markUnverified(id) }
        assertThrows(IllegalArgumentException::class.java) { guard.markUnverified("../escape") }
        guard.clearAfterReady(id)
        assertFalse(WatermarkSegmentGuard(sandbox).isUnverified(id))
    }

    @Test fun recordingWithMarkerQuarantinesPendingOrRenamedMediaWithoutTouchingBytes() = runBlocking {
        for (renamed in listOf(false, true)) {
            val id = UUID.randomUUID().toString()
            val pending = SegmentFileStore(sandbox).newTempFile(id)
            val bytes = byteArrayOf(9, 8, 7, 6)
            pending.writeBytes(bytes)
            WatermarkSegmentGuard(sandbox).markUnverified(id)
            dao.insert(row(id, pending))
            val media = if (renamed) File(pending.parentFile, "$id.mp4").also { check(pending.renameTo(it)) } else pending
            val report = SegmentRecovery(sandbox, dao).reconcile(2_000)
            assertEquals(0, report.recoveredReady)
            assertEquals(1, report.markedFailed)
            assertEquals(1, report.gapsRecorded)
            assertEquals(SegmentStatus.FAILED, dao.getById(id)!!.status)
            assertArrayEquals(bytes, media.readBytes())
            assertTrue(WatermarkSegmentGuard(sandbox).isUnverified(id))
            assertEquals(0, SegmentRecovery(sandbox, dao).reconcile(3_000).markedFailed)
        }
    }

    @Test fun readyAndFailedRowsWithLeftoverMarkersRemainUntouched() = runBlocking {
        for (ready in listOf(false, true)) {
            val id = UUID.randomUUID().toString()
            val pending = SegmentFileStore(sandbox).newTempFile(id).apply { writeBytes(byteArrayOf(1, 3, 5)) }
            dao.insert(row(id, pending))
            WatermarkSegmentGuard(sandbox).markUnverified(id)
            if (ready) assertEquals(1, dao.finalizeSegment(id, 1, 1_000, 2_000, 1_000, 3, 1280, 720,
                "video/avc", false, pending.name, pending.path))
            else assertEquals(1, dao.failRecording(id, 1))
            val report = SegmentRecovery(sandbox, dao).reconcile(3_000)
            assertEquals(0, report.recoveredReady + report.markedFailed + report.gapsRecorded)
            assertEquals(if (ready) SegmentStatus.READY else SegmentStatus.FAILED, dao.getById(id)!!.status)
            assertArrayEquals(byteArrayOf(1, 3, 5), pending.readBytes())
        }
    }

    @Test fun validLegacyMediaWithoutMarkerStillRecoversReady() = runBlocking {
        val id = UUID.randomUUID().toString()
        val pending = SegmentFileStore(sandbox).newTempFile(id)
        InstrumentationRegistry.getInstrumentation().context.assets.open("watermark-qa-legacy.mp4").use { input ->
            pending.outputStream().use { output -> input.copyTo(output) }
        }
        dao.insert(row(id, pending))
        val report = SegmentRecovery(sandbox, dao).reconcile(2_000)
        assertEquals(1, report.recoveredReady)
        assertEquals(0, report.markedFailed)
        val recovered = dao.getById(id)!!
        assertEquals(SegmentStatus.READY, recovered.status)
        assertTrue(File(recovered.path).isFile)
        assertFalse(WatermarkSegmentGuard(sandbox).isUnverified(id))
    }

    @Test fun legacyWithoutMarkerKeepsExistingRecoveryPolicy() = runBlocking {
        val id = UUID.randomUUID().toString()
        val pending = SegmentFileStore(sandbox).newTempFile(id)
        dao.insert(row(id, pending))
        check(pending.delete())
        val report = SegmentRecovery(sandbox, dao).reconcile(2_000)
        assertEquals(1, report.markedFailed)
        assertFalse(WatermarkSegmentGuard(sandbox).isUnverified(id))
        assertFalse(dao.getGaps(0, 3_000).single().reason.contains("Watermark"))
    }

    private fun row(id: String, file: File) = SegmentEntity(id = id, sessionId = UUID.randomUUID().toString(),
        startUtc = 1_000, internalName = file.name, path = file.path)
}

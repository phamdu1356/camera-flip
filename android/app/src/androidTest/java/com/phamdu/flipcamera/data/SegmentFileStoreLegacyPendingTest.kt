package com.phamdu.flipcamera.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentFileStoreLegacyPendingTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun adoptsAndDiscardsLegacyPendingBasenameForTheSameSegmentUuid() {
        val id = UUID.randomUUID().toString()
        val legacyPending = legacyPendingFile(id).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = SegmentFileStore(context)
        try {
            val adopted = store.adoptPendingFile(id, legacyPending.absolutePath)
            assertEquals(legacyPending.canonicalFile, adopted.canonicalFile)

            store.discardTempFile(adopted)
            assertFalse(legacyPending.exists())
        } finally {
            legacyPending.delete()
        }
    }

    @Test
    fun deletesLegacyPendingRowFileOnlyWhenBasenameMatchesItsSegmentUuid() {
        val id = UUID.randomUUID().toString()
        val legacyName = "pending-$id.mp4"
        val legacyPending = legacyPendingFile(id).apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val deleted = SegmentFileStore(context).deleteReferencedFile(
            segmentId = id,
            internalName = legacyName,
            path = legacyPending.absolutePath,
        )

        assertTrue(deleted)
        assertFalse(legacyPending.exists())
    }

    @Test
    fun rejectsLegacyPendingFileWithAnotherSegmentsUuid() {
        val id = UUID.randomUUID().toString()
        val otherId = UUID.randomUUID().toString()
        val otherPending = legacyPendingFile(otherId).apply { writeBytes(byteArrayOf(7, 8, 9)) }

        val error = runCatching {
            SegmentFileStore(context).adoptPendingFile(id, otherPending.absolutePath)
        }.exceptionOrNull()

        assertTrue(error is SegmentFileIOException)
        assertEquals(SegmentFileIOException.Reason.INVALID_PATH, (error as SegmentFileIOException).reason)
        assertTrue(otherPending.exists())
        otherPending.delete()
    }

    @Test
    fun rejectsLegacyPendingSymlinkAndPreservesItsTarget() {
        val id = UUID.randomUUID().toString()
        val target = File(context.filesDir.canonicalFile, "outside-$id.mp4").apply {
            writeBytes(byteArrayOf(10, 11))
        }
        val link = legacyPendingFile(id)
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val error = runCatching {
                SegmentFileStore(context).adoptPendingFile(id, link.absolutePath)
            }.exceptionOrNull()

            assertTrue(error is SegmentFileIOException)
            assertEquals(SegmentFileIOException.Reason.INVALID_PATH, (error as SegmentFileIOException).reason)
            assertTrue(target.exists())
        } finally {
            Files.deleteIfExists(link.toPath())
            target.delete()
        }
    }

    private fun legacyPendingFile(id: String): File =
        File(File(context.filesDir.canonicalFile, "segments").apply { mkdirs() }, "pending-$id.mp4")
}

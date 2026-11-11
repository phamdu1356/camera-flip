package com.phamdu.flipcamera.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SegmentFileStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun pendingFileIsPrivateAndOnlyItsCreatingStoreCanDeleteIt() {
        val owner = SegmentFileStore(context)
        val unrelatedStore = SegmentFileStore(context)
        val pending = owner.newTempFile(UUID.randomUUID().toString())
        try {
            assertEquals("segments", pending.parentFile?.name)
            assertTrue(pending.canonicalPath.startsWith(context.filesDir.canonicalPath + "/segments/"))
            assertTrue(pending.name.endsWith(".pending.mp4"))

            val failure = runCatching { unrelatedStore.discardTempFile(pending) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(pending.exists())
        } finally {
            owner.discardTempFile(pending)
        }
        assertFalse(pending.exists())
    }

    @Test
    fun segmentIdMustBeCanonicalUuidBeforeCreatingAFile() {
        val store = SegmentFileStore(context)
        val failure = runCatching { store.newTempFile("../../outside") }.exceptionOrNull()
        assertTrue(failure is SegmentFileIOException)
        assertEquals(SegmentFileIOException.Reason.INVALID_ID, (failure as SegmentFileIOException).reason)
    }
}

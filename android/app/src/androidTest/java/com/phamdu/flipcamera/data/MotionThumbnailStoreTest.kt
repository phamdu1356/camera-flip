package com.phamdu.flipcamera.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MotionThumbnailStoreTest {
    @Test
    fun writesPrivateGeneratedJpegAndSweepsUnreferencedFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MotionThumbnailStore(context)
        val keptId = UUID.randomUUID().toString()
        val orphanId = UUID.randomUUID().toString()
        val pendingId = UUID.randomUUID().toString()
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())

        assertEquals("$keptId.jpg", store.write(keptId, jpeg))
        assertEquals("$orphanId.jpg", store.write(orphanId, jpeg))
        val directory = File(context.filesDir, "motion-thumbnails")
        File(directory, ".$pendingId.pending").writeBytes(byteArrayOf(1, 2, 3))

        val handle = store.open(keptId, "$keptId.jpg") ?: error("stored thumbnail was not found")
        handle.stream.use { stream ->
            assertEquals(jpeg.size.toLong(), handle.byteLength)
            assertArrayEquals(jpeg, stream.readBytes())
        }
        assertNull(store.open(keptId, "../$orphanId.jpg"))
        assertEquals(2, store.cleanupOrphans(setOf("$keptId.jpg")))
        assertTrue(store.contains(keptId, "$keptId.jpg"))
        assertFalse(store.contains(orphanId, "$orphanId.jpg"))
        assertFalse(File(directory, ".$pendingId.pending").exists())
        assertTrue(store.delete(keptId))
        assertFalse(store.delete(keptId))
    }

    @Test
    fun rejectsNonJpegContentAndNonCanonicalEventIds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MotionThumbnailStore(context)
        val id = UUID.randomUUID().toString()
        assertNull(store.write(id, byteArrayOf(1, 2, 3, 4)))
        assertThrows(IOException::class.java) { store.write("../escape", byteArrayOf()) }
    }
}

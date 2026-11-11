package com.phamdu.flipcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ModelsTest {
    @Test
    fun canonicalUuidAndPrivateBasenameAreAccepted() {
        val id = UUID.randomUUID().toString()
        val row = recording(id = id, internalName = "pending-$id.mp4")

        assertEquals(id, row.id)
        assertTrue(row.internalName.endsWith(".mp4"))
    }

    @Test
    fun nonCanonicalUuidAndPathLikeBasenamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            recording(id = UUID.randomUUID().toString().uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) { recording(internalName = "../outside.mp4") }
        assertThrows(IllegalArgumentException::class.java) { recording(internalName = "/tmp/video.mp4") }
    }

    @Test
    fun readyRequiresMeasuredCompleteMetadata() {
        val id = UUID.randomUUID().toString()
        val ready = recording(
            id = id,
            endUtc = 2_000L,
            duration = 1_000L,
            bytes = 500L,
            status = SegmentStatus.READY,
        )
        assertEquals(SegmentStatus.READY, ready.status)

        assertThrows(IllegalArgumentException::class.java) {
            recording(id = UUID.randomUUID().toString(), status = SegmentStatus.READY)
        }
    }

    @Test
    fun gapUsesPositiveHalfOpenIntervalAndOptionalSession() {
        val gap = GapEntity(startUtc = 10L, endUtc = 11L, reason = "camera reconnect")
        assertEquals(1L, gap.endUtc - gap.startUtc)
        assertFalse(gap.sessionId != null)
        assertThrows(IllegalArgumentException::class.java) {
            GapEntity(startUtc = 11L, endUtc = 11L, reason = "empty")
        }
    }

    @Test
    fun statusConverterUsesStableEnumNames() {
        val converter = SegmentConverters()
        assertEquals("READY", converter.encodeStatus(SegmentStatus.READY))
        assertEquals(SegmentStatus.FAILED, converter.decodeStatus("FAILED"))
    }

    private fun recording(
        id: String = UUID.randomUUID().toString(),
        internalName: String = "pending-$id.mp4",
        endUtc: Long? = null,
        duration: Long = 0L,
        bytes: Long = 0L,
        status: SegmentStatus = SegmentStatus.RECORDING,
    ) = SegmentEntity(
        id = id,
        sessionId = UUID.randomUUID().toString(),
        startUtc = 1_000L,
        endUtc = endUtc,
        duration = duration,
        codec = "video/avc",
        width = 1280,
        height = 720,
        audio = false,
        bytes = bytes,
        internalName = internalName,
        path = "/private/segments/$internalName",
        status = status,
    )
}

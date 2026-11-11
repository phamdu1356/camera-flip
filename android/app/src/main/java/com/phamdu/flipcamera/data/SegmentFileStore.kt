package com.phamdu.flipcamera.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

data class FinalizedSegmentFile(
    val internalName: String,
    /** App-private local metadata; never expose as a viewer URL. */
    val path: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val videoMime: String?,
    val audioTrackPresent: Boolean,
    val durationMs: Long
)

class SegmentFileIOException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause) {
    enum class Reason {
        INVALID_ID, INVALID_PATH, INVALID_FILE, INVALID_MEDIA,
        CREATE_FAILED, RENAME_FAILED, DELETE_FAILED
    }
}

/**
 * Owns files directly under filesDir/segments. Call on an I/O thread, and finalize only after
 * CameraX reports a successful VideoRecordEvent.Finalize and has closed its output.
 *
 * Inspection verifies container metadata and a nonempty regular file, not every encoded frame.
 * Atomic rename makes publication indivisible; these checks do not guarantee power-loss
 * durability (there is no file/directory fsync), playback correctness, or crash recovery.
 * This class never updates Room or publishes READY. Pending ownership is intentionally local
 * to this instance; adopting files left by a previous process requires separate recovery.
 */
class SegmentFileStore(context: Context) {
    private val filesDirectory = io(SegmentFileIOException.Reason.CREATE_FAILED) {
        context.applicationContext.filesDir.canonicalFile
    }
    private val segmentsDirectory = File(filesDirectory, "segments")
    private val pendingOwners = mutableMapOf<String, String>()

    /** Returns currently available disk space on the storage volume containing segments. */
    fun usableSpaceBytes(): Long = io(SegmentFileIOException.Reason.CREATE_FAILED) {
        ensureDirectory()
        segmentsDirectory.usableSpace
    }

    @Synchronized
    @Throws(IOException::class)
    fun newTempFile(segmentId: String): File = io(SegmentFileIOException.Reason.CREATE_FAILED) {
        validateId(segmentId)
        ensureDirectory()
        if (segmentsDirectory.usableSpace < MIN_STORAGE_HEADROOM_BYTES) {
            fail(SegmentFileIOException.Reason.CREATE_FAILED, "Dung lượng bộ nhớ khả dụng dưới ngưỡng an toàn")
        }
        if (segmentId in pendingOwners.values) {
            fail(SegmentFileIOException.Reason.CREATE_FAILED, "Segment already has a pending file")
        }
        // The canonical UUID makes a temp/final pair recoverable after a process crash.
        val file = File(segmentsDirectory, "$segmentId.pending.mp4")
        validateChild(file, pending = true, expectedSegmentId = segmentId)
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            fail(SegmentFileIOException.Reason.CREATE_FAILED, "A temp file already exists for this segment")
        }
        if (!file.createNewFile()) {
            fail(SegmentFileIOException.Reason.CREATE_FAILED, "Could not create a unique pending file")
        }
        pendingOwners[file.name] = segmentId
        file
    }

    @Synchronized
    @Throws(IOException::class)
    fun finalizeTempFile(segmentId: String, tempFile: File): FinalizedSegmentFile =
        io(SegmentFileIOException.Reason.INVALID_FILE) {
            validateId(segmentId)
            ensureDirectory()
            validateChild(tempFile, pending = true, expectedSegmentId = segmentId)
            if (pendingOwners[tempFile.name] != segmentId) {
                fail(SegmentFileIOException.Reason.INVALID_PATH, "Pending file does not belong to this segment")
            }
            val originalBytes = requireNonemptyFile(tempFile)
            val measured = inspect(tempFile)
            if (requireNonemptyFile(tempFile) != originalBytes) {
                fail(SegmentFileIOException.Reason.INVALID_FILE, "Pending file changed during inspection")
            }

            val target = File(segmentsDirectory, "$segmentId.mp4")
            validateChild(target, pending = false, expectedSegmentId = segmentId)
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                fail(SegmentFileIOException.Reason.RENAME_FAILED, "Final filename already exists")
            }
            io(SegmentFileIOException.Reason.RENAME_FAILED) {
                // Both files have the same private parent. Fail if atomic move is unsupported.
                Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            pendingOwners.remove(tempFile.name)
            try {
                validateChild(target, pending = false)
                val finalBytes = requireNonemptyFile(target)
                if (finalBytes != originalBytes) {
                    fail(SegmentFileIOException.Reason.INVALID_FILE, "Final file size changed after rename")
                }
                FinalizedSegmentFile(
                    internalName = target.name,
                    path = target.absolutePath,
                    bytes = finalBytes,
                    width = measured.width,
                    height = measured.height,
                    videoMime = measured.videoMime,
                    audioTrackPresent = measured.audioTrackPresent,
                    durationMs = measured.durationMs
                )
            } catch (failure: IOException) {
                // The caller has no result/path to clean up if post-rename verification fails.
                try {
                    deleteChild(target, pending = false)
                } catch (cleanupFailure: IOException) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

    /** Reclaim a pending file referenced by a RECORDING row after this process has restarted. */
    @Synchronized
    @Throws(IOException::class)
    fun adoptPendingFile(segmentId: String, persistedPath: String): File =
        io(SegmentFileIOException.Reason.INVALID_PATH) {
            validateId(segmentId)
            ensureDirectory()
            val file = File(persistedPath)
            validateChild(file, pending = true, expectedSegmentId = segmentId)
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                fail(SegmentFileIOException.Reason.INVALID_FILE, "Pending recovery file is missing or invalid")
            }
            val existingOwner = pendingOwners[file.name]
            if (existingOwner != null && existingOwner != segmentId) {
                fail(SegmentFileIOException.Reason.INVALID_PATH, "Pending file is already owned by another segment")
            }
            pendingOwners[file.name] = segmentId
            file
        }

    /** Inspect the deterministic final path left by a crash after rename but before Room commit. */
    @Synchronized
    @Throws(IOException::class)
    fun findFinalizedForRecovery(segmentId: String): FinalizedSegmentFile? =
        io(SegmentFileIOException.Reason.INVALID_FILE) {
            validateId(segmentId)
            ensureDirectory()
            val file = File(segmentsDirectory, "$segmentId.mp4")
            validateChild(file, pending = false, expectedSegmentId = segmentId)
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@io null
            val bytes = requireNonemptyFile(file)
            val measured = inspect(file)
            if (requireNonemptyFile(file) != bytes) {
                fail(SegmentFileIOException.Reason.INVALID_FILE, "Recovery file changed during inspection")
            }
            FinalizedSegmentFile(
                internalName = file.name,
                path = file.absolutePath,
                bytes = bytes,
                width = measured.width,
                height = measured.height,
                videoMime = measured.videoMime,
                audioTrackPresent = measured.audioTrackPresent,
                durationMs = measured.durationMs,
            )
        }

    /**
     * Remove a corrupt deterministic recovery file only after verifying its UUID-derived path
     * remains a regular direct child of private segments storage. Valid media, symlinks,
     * nonregular entries, and ambiguous I/O failures are preserved.
     */
    @Synchronized
    @Throws(IOException::class)
    fun discardInvalidFinalizedFile(segmentId: String): Boolean =
        io(SegmentFileIOException.Reason.DELETE_FAILED) {
            validateId(segmentId)
            ensureDirectory()
            val file = File(segmentsDirectory, "$segmentId.mp4")
            validateChild(file, pending = false, expectedSegmentId = segmentId)
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@io false
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@io false

            try {
                val bytes = requireNonemptyFile(file)
                inspect(file)
                if (requireNonemptyFile(file) != bytes) return@io false
                false
            } catch (rejected: SegmentFileIOException) {
                if (rejected.reason !in setOf(
                        SegmentFileIOException.Reason.INVALID_FILE,
                        SegmentFileIOException.Reason.INVALID_MEDIA,
                    )
                ) throw rejected
                // Recheck immediately before unlinking; never follow a link or remove a directory.
                validateChild(file, pending = false, expectedSegmentId = segmentId)
                if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                ) return@io false
                deleteChild(file, pending = false, expectedSegmentId = segmentId)
                true
            }
        }

    /**
     * Open only the deterministic finalized file for this UUID. The caller must first verify
     * that Room still marks the row READY. Returning an open descriptor keeps reads valid if
     * retention unlinks the file after this check; no database path is accepted here.
     */
    @Synchronized
    @Throws(IOException::class)
    fun openReadySegment(segmentId: String, internalName: String): FileInputStream =
        io(SegmentFileIOException.Reason.INVALID_FILE) {
            validateId(segmentId)
            requireInternalName(internalName)
            if (internalName != "$segmentId.mp4") {
                fail(SegmentFileIOException.Reason.INVALID_PATH, "Finalized name does not match segment ID")
            }
            ensureDirectory()
            val file = File(segmentsDirectory, internalName)
            validateChild(file, pending = false, expectedSegmentId = segmentId)
            requireNonemptyFile(file)
            FileInputStream(file)
        }

    /** Also permits an empty pending file when CameraX failed before writing any bytes. */
    @Synchronized
    @Throws(IOException::class)
    fun discardTempFile(file: File) = io(SegmentFileIOException.Reason.DELETE_FAILED) {
        ensureDirectory()
        validateChild(file, pending = true)
        if (pendingOwners[file.name] == null) {
            fail(SegmentFileIOException.Reason.INVALID_PATH, "Pending file is not owned by this store")
        }
        deleteChild(file, pending = true)
        pendingOwners.remove(file.name)
        Unit
    }

    /** Remove a renamed orphan when Room declines the caller's READY compare-and-set. */
    @Synchronized
    @Throws(IOException::class)
    fun discardFinalizedFile(finalized: FinalizedSegmentFile) =
        io(SegmentFileIOException.Reason.DELETE_FAILED) {
            ensureDirectory()
            val file = File(finalized.path)
            validateChild(file, pending = false)
            if (file.name != finalized.internalName) {
                fail(SegmentFileIOException.Reason.INVALID_PATH, "Final name and path do not match")
            }
            deleteChild(file, pending = false)
        }

    /** Delete a row-referenced file only after validating its UUID basename and private parent. */
    @Synchronized
    @Throws(IOException::class)
    fun deleteReferencedFile(segmentId: String, internalName: String, path: String): Boolean =
        io(SegmentFileIOException.Reason.DELETE_FAILED) {
            validateId(segmentId)
            ensureDirectory()
            requireInternalName(internalName)
            val file = File(path)
            val pending = pendingUuidToken(file.name) != null
            validateChild(file, pending, expectedSegmentId = segmentId)
            val legacyPendingName = "pending-$segmentId.mp4"
            if (file.name != internalName && !(pending && internalName == legacyPendingName)) {
                fail(SegmentFileIOException.Reason.INVALID_PATH, "Stored name and private file path do not match")
            }
            val existed = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            deleteChild(file, pending, expectedSegmentId = segmentId)
            pendingOwners.remove(file.name)
            existed
        }

    private data class MeasuredMedia(
        val width: Int,
        val height: Int,
        val videoMime: String,
        val audioTrackPresent: Boolean,
        val durationMs: Long
    )

    private fun inspect(file: File): MeasuredMedia = io(SegmentFileIOException.Reason.INVALID_MEDIA) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video: MediaFormat? = null
            var videoMime: String? = null
            var audioPresent = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) audioPresent = true
                if (mime.startsWith("video/") && video == null) {
                    video = format
                    videoMime = mime
                }
            }
            val format = video
                ?: fail(SegmentFileIOException.Reason.INVALID_MEDIA, "Container has no video track")
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            // Track duration is measured from the encoded container, never from wall time.
            val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000L
            if (width <= 0 || height <= 0 || durationMs <= 0L) {
                fail(SegmentFileIOException.Reason.INVALID_MEDIA, "Video dimensions or measured duration are invalid")
            }
            MeasuredMedia(width, height, videoMime!!, audioPresent, durationMs)
        } finally {
            extractor.release()
        }
    }

    private fun ensureDirectory() {
        if (segmentsDirectory.canonicalFile != segmentsDirectory ||
            segmentsDirectory.parentFile != filesDirectory ||
            Files.isSymbolicLink(segmentsDirectory.toPath())) {
            fail(SegmentFileIOException.Reason.INVALID_PATH, "Segments directory is outside private storage")
        }
        if (!segmentsDirectory.isDirectory && !segmentsDirectory.mkdirs()) {
            fail(SegmentFileIOException.Reason.CREATE_FAILED, "Could not create private segments directory")
        }
        if (!segmentsDirectory.isDirectory) {
            fail(SegmentFileIOException.Reason.INVALID_PATH, "Segments root is not a directory")
        }
    }

    private fun validateId(segmentId: String) {
        val canonical = try {
            UUID.fromString(segmentId).toString()
        } catch (failure: IllegalArgumentException) {
            throw SegmentFileIOException(SegmentFileIOException.Reason.INVALID_ID, "Expected a canonical segment UUID", failure)
        }
        if (canonical != segmentId) {
            fail(SegmentFileIOException.Reason.INVALID_ID, "Expected a canonical segment UUID")
        }
    }

    private fun validateChild(file: File, pending: Boolean, expectedSegmentId: String? = null) {
        val name = file.name
        val token = if (pending) {
            pendingUuidToken(name).orEmpty()
        } else if (name.endsWith(".mp4")) {
            name.removeSuffix(".mp4")
        } else {
            ""
        }
        val canonicalToken = try {
            UUID.fromString(token).toString() == token
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!canonicalToken || (expectedSegmentId != null && token != expectedSegmentId) ||
            !file.isAbsolute || file.parentFile != segmentsDirectory ||
            file.canonicalFile != file.absoluteFile || Files.isSymbolicLink(file.toPath())) {
            fail(SegmentFileIOException.Reason.INVALID_PATH, "Expected a generated direct child of private segments storage")
        }
    }

    /** Both current and historical names are accepted, but their UUID must match the row ID. */
    private fun pendingUuidToken(name: String): String? = when {
        name.endsWith(".pending.mp4") -> name.removeSuffix(".pending.mp4")
        name.startsWith("pending-") && name.endsWith(".mp4") ->
            name.removePrefix("pending-").removeSuffix(".mp4")
        else -> null
    }

    private fun requireNonemptyFile(file: File): Long {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            fail(SegmentFileIOException.Reason.INVALID_FILE, "Segment is not a regular file")
        }
        return file.length().also {
            if (it <= 0L) fail(SegmentFileIOException.Reason.INVALID_FILE, "Segment is empty")
        }
    }

    private fun deleteChild(file: File, pending: Boolean, expectedSegmentId: String? = null) {
        validateChild(file, pending, expectedSegmentId)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            fail(SegmentFileIOException.Reason.INVALID_FILE, "Refusing to delete a nonregular segment file")
        }
        Files.delete(file.toPath())
    }

    private inline fun <T> io(reason: SegmentFileIOException.Reason, action: () -> T): T = try {
        action()
    } catch (failure: SegmentFileIOException) {
        throw failure
    } catch (failure: IOException) {
        throw SegmentFileIOException(reason, "Segment file operation failed: $reason", failure)
    } catch (failure: RuntimeException) {
        throw SegmentFileIOException(reason, "Segment file operation failed: $reason", failure)
    }

    private fun fail(reason: SegmentFileIOException.Reason, message: String): Nothing =
        throw SegmentFileIOException(reason, message)

    companion object {
        const val MIN_STORAGE_HEADROOM_BYTES = 100L * 1024L * 1024L
    }
}

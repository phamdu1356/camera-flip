package com.phamdu.flipcamera.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.UUID

/** Durable opt-in quarantine for new watermarked recordings. Never reads or removes media. */
class WatermarkSegmentGuard(context: Context) {
    private val root = context.applicationContext.filesDir.canonicalFile
    private val directory = File(root, "watermark-guards")

    /** Must return successfully before the encoder can write this segment. Call on an I/O thread. */
    @Synchronized
    fun markUnverified(segmentId: String) {
        val marker = marker(segmentId)
        ensureDirectory()
        val descriptor = Os.open(marker.path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
            0x180) // 0600; marker existence is the record, no secret or media content.
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        syncDirectory(directory)
    }

    /** Missing marker means legacy recording; I/O errors are propagated, never treated as legacy. */
    @Synchronized
    fun isUnverified(segmentId: String): Boolean {
        val marker = marker(segmentId)
        if (!directoryPresent()) return false
        checkDirectory()
        return try {
            Os.lstat(marker.path)
            true // Any entry (including an unexpected symlink) quarantines the segment.
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) false else throw error
        }
    }

    /** Caller must already have successful encoder Finalize + publication gate + committed READY. */
    @Synchronized
    fun clearAfterReady(segmentId: String) {
        val marker = marker(segmentId)
        if (!directoryPresent()) return
        checkDirectory()
        try { Os.remove(marker.path) } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENOENT) throw error
        }
        syncDirectory(directory)
    }

    private fun marker(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid segment UUID" }
        return File(directory, "$id.watermark-unverified")
    }

    private fun ensureDirectory() {
        if (!directoryPresent()) {
            check(directory.mkdir()) { "Cannot create watermark guard directory" }
            syncDirectory(root) // Persist directory entry before any encoder starts.
        }
        checkDirectory()
    }

    private fun directoryPresent(): Boolean = try {
        Os.lstat(directory.path)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    private fun checkDirectory() {
        val info = Os.lstat(directory.path)
        check(OsConstants.S_ISDIR(info.st_mode) && directory.canonicalFile == directory.absoluteFile) {
            "Invalid watermark guard directory"
        }
    }

    private fun syncDirectory(file: File) {
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Expected directory for durable watermark guard" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }
}

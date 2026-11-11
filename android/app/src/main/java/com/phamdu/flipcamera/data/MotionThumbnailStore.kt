package com.phamdu.flipcamera.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class MotionThumbnailHandle(val stream: FileInputStream, val byteLength: Long)

/** Owns small generated JPEGs directly under app-private filesDir/motion-thumbnails. */
internal class MotionThumbnailStore(context: Context) {
    private val filesRoot = context.applicationContext.filesDir.canonicalFile
    private val directory = File(filesRoot, DIRECTORY_NAME)

    @Synchronized
    @Throws(IOException::class)
    fun write(eventId: String, jpeg: ByteArray): String? = io {
        validateId(eventId)
        if (jpeg.size !in MIN_JPEG_BYTES..MAX_JPEG_BYTES ||
            jpeg[0] != JPEG_START_0 || jpeg[1] != JPEG_START_1 ||
            jpeg[jpeg.lastIndex - 1] != JPEG_END_0 || jpeg.last() != JPEG_END_1
        ) return@io null

        ensureDirectory()
        val target = child(eventId, "$eventId.jpg")
        val temporary = child(eventId, ".$eventId.pending")
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) return@io null

        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(jpeg)
                output.fd.sync()
            }
            if (!Files.isRegularFile(temporary.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                temporary.length() != jpeg.size.toLong()
            ) throw IOException("Thumbnail changed while being written")
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) || target.length() != jpeg.size.toLong()) {
                Files.deleteIfExists(target.toPath())
                throw IOException("Thumbnail changed after atomic rename")
            }
            "$eventId.jpg"
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            if (failure is IOException) throw failure
            throw IOException("Could not store motion thumbnail", failure)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun open(eventId: String, thumbnailName: String): MotionThumbnailHandle? = io {
        validateId(eventId)
        if (thumbnailName != "$eventId.jpg") return@io null
        ensureDirectory()
        val file = child(eventId, thumbnailName)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@io null
        val bytes = file.length()
        if (bytes !in MIN_JPEG_BYTES.toLong()..MAX_JPEG_BYTES.toLong()) return@io null
        MotionThumbnailHandle(FileInputStream(file), bytes)
    }

    @Synchronized
    @Throws(IOException::class)
    fun delete(eventId: String): Boolean = io {
        validateId(eventId)
        ensureDirectory()
        val file = child(eventId, "$eventId.jpg")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@io false
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Refusing to delete a nonregular thumbnail")
        }
        Files.delete(file.toPath())
        true
    }

    /** Remove generated temp files and JPEGs no longer referenced by any Room event row. */
    @Synchronized
    @Throws(IOException::class)
    fun cleanupOrphans(referencedNames: Set<String>): Int = io {
        ensureDirectory()
        var removed = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                val name = path.fileName.toString()
                val eventId = when {
                    name.startsWith(".") && name.endsWith(".pending") -> name.removePrefix(".").removeSuffix(".pending")
                    name.endsWith(".jpg") -> name.removeSuffix(".jpg")
                    else -> continue
                }
                if (runCatching { validateId(eventId) }.isFailure) continue
                val generatedName = if (name.endsWith(".jpg")) "$eventId.jpg" else ".$eventId.pending"
                val file = child(eventId, generatedName)
                if (generatedName.endsWith(".pending") || generatedName !in referencedNames) {
                    Files.delete(file.toPath())
                    removed++
                }
            }
        }
        removed
    }

    @Synchronized
    fun contains(eventId: String, thumbnailName: String): Boolean =
        runCatching { open(eventId, thumbnailName)?.let { handle -> handle.stream.use { true } } ?: false }
            .getOrDefault(false)

    private fun ensureDirectory() {
        if (directory.parentFile != filesRoot || directory.canonicalFile != directory.absoluteFile ||
            Files.isSymbolicLink(directory.toPath())
        ) throw IOException("Motion thumbnail directory is outside private storage")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create motion thumbnail directory")
        }
        if (!directory.isDirectory) throw IOException("Motion thumbnail root is not a directory")
    }

    private fun child(eventId: String, name: String): File {
        val allowedNames = setOf("$eventId.jpg", ".$eventId.pending")
        if (name !in allowedNames) throw IOException("Invalid generated thumbnail basename")
        val file = File(directory, name)
        if (!file.isAbsolute || file.parentFile != directory || file.canonicalFile != file.absoluteFile ||
            Files.isSymbolicLink(file.toPath())
        ) throw IOException("Thumbnail is not a direct private file")
        return file
    }

    private fun validateId(eventId: String) {
        val canonical = try {
            UUID.fromString(eventId).toString()
        } catch (failure: IllegalArgumentException) {
            throw IOException("Expected a canonical event UUID", failure)
        }
        if (canonical != eventId) throw IOException("Expected a canonical event UUID")
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (failure: IOException) {
        throw failure
    } catch (failure: Exception) {
        throw IOException("Motion thumbnail operation failed", failure)
    }

    private companion object {
        const val DIRECTORY_NAME = "motion-thumbnails"
        const val MIN_JPEG_BYTES = 4
        const val MAX_JPEG_BYTES = 512 * 1024
        const val JPEG_START_0: Byte = 0xff.toByte()
        const val JPEG_START_1: Byte = 0xd8.toByte()
        const val JPEG_END_0: Byte = 0xff.toByte()
        const val JPEG_END_1: Byte = 0xd9.toByte()
    }
}

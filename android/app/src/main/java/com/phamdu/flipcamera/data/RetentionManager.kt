package com.phamdu.flipcamera.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RetentionReport(
    val tombstonedSegments: Int,
    val filesDeleted: Int,
    val filesAlreadyMissing: Int,
    val fileDeleteFailures: Int,
    val tombstonedEvents: Int,
    val thumbnailsDeleted: Int,
    val thumbnailDeleteFailures: Int,
)

data class EventCleanupReport(
    val tombstonedEvents: Int,
    val thumbnailsDeleted: Int,
    val thumbnailDeleteFailures: Int,
)

/**
 * Apply an explicit UTC cutoff. The native admin UI or its scheduler chooses the policy; this
 * class never invents an age limit. Room tombstones unprotected rows before safe private-file
 * deletion, so concurrent protection changes win and failed deletions can be retried later.
 */
class RetentionManager(context: Context, private val dao: SegmentDao) {
    private val fileStore = SegmentFileStore(context.applicationContext)
    private val eventDao = CameraDatabase.getInstance(context.applicationContext).motionEventDao()
    private val thumbnailStore = MotionThumbnailStore(context.applicationContext)
    private data class SegmentCleanup(val deleted: Int, val missing: Int, val failures: Int)

    suspend fun enforceAge(cutoffUtc: Long): RetentionReport = withContext(Dispatchers.IO) {
        var tombstoned = 0
        var tombstonedEvents = 0

        while (true) {
            val candidates = dao.getExpiredSegments(cutoffUtc)
            if (candidates.isEmpty()) break
            var madeProgress = false
            for (row in candidates) {
                if (dao.markDeletedWithEvents(row.id, row.status, row.revision) == 1) {
                    madeProgress = true
                    tombstoned++
                }
            }
            if (!madeProgress) break
        }

        val segmentCleanup = cleanDeletedSegments()

        // Event metadata is tombstoned before thumbnail I/O; failed deletes keep the generated
        // basename so a later cleanup pass can retry without accepting a client path.
        while (true) {
            val candidates = eventDao.getExpired(cutoffUtc)
            if (candidates.isEmpty()) break
            var madeProgress = false
            for (event in candidates) {
                if (eventDao.markDeleted(event.id, event.revision) == 1) {
                    madeProgress = true
                    tombstonedEvents++
                }
            }
            if (!madeProgress) break
        }

        val cleanup = cleanDeletedEventThumbnails()
        RetentionReport(
            tombstonedSegments = tombstoned,
            filesDeleted = segmentCleanup.deleted,
            filesAlreadyMissing = segmentCleanup.missing,
            fileDeleteFailures = segmentCleanup.failures,
            tombstonedEvents = tombstonedEvents,
            thumbnailsDeleted = cleanup.first,
            thumbnailDeleteFailures = cleanup.second,
        )
    }

    /** A user deleting a recording also removes events/thumbnails attached to that recording. */
    suspend fun deleteEventsForSegment(segmentId: String): EventCleanupReport = withContext(Dispatchers.IO) {
        requireCanonicalUuid(segmentId)
        var tombstoned = 0
        for (event in eventDao.getReadyForSegment(segmentId)) {
            if (eventDao.markDeleted(event.id, event.revision) == 1) tombstoned++
        }
        val cleanup = cleanDeletedEventThumbnails()
        EventCleanupReport(tombstoned, cleanup.first, cleanup.second)
    }

    /** Retry prior segment/event tombstone cleanup without applying a new age cutoff. */
    suspend fun retryPendingCleanup(): RetentionReport = withContext(Dispatchers.IO) {
        val segments = cleanDeletedSegments()
        val events = cleanDeletedEventThumbnails()
        RetentionReport(
            tombstonedSegments = 0,
            filesDeleted = segments.deleted,
            filesAlreadyMissing = segments.missing,
            fileDeleteFailures = segments.failures,
            tombstonedEvents = 0,
            thumbnailsDeleted = events.first,
            thumbnailDeleteFailures = events.second,
        )
    }

    /**
     * Reclaims storage space by deleting the oldest unprotected segments until usable space
     * reaches targetFreeBytes or no more unprotected segments can be deleted.
     * Returns the count of segments successfully deleted.
     */
    suspend fun trimOldestUnprotected(targetFreeBytes: Long): Int = withContext(Dispatchers.IO) {
        if (fileStore.usableSpaceBytes() >= targetFreeBytes) return@withContext 0
        var totalDeleted = 0
        val candidates = dao.getExpiredSegments(Long.MAX_VALUE)
        for (candidate in candidates) {
            if (fileStore.usableSpaceBytes() >= targetFreeBytes) break
            if (dao.markDeletedWithEvents(candidate.id, candidate.status, candidate.revision) == 1) {
                runCatching {
                    fileStore.deleteReferencedFile(candidate.id, candidate.internalName, candidate.path)
                    totalDeleted++
                }
            }
        }
        if (totalDeleted > 0) {
            cleanDeletedEventThumbnails()
        }
        totalDeleted
    }

    private suspend fun cleanDeletedSegments(): SegmentCleanup {
        var deleted = 0
        var missing = 0
        var failures = 0
        var afterId: String? = null
        while (true) {
            val batch = dao.getDeletedSegmentsForCleanup(afterId)
            if (batch.isEmpty()) break
            for (row in batch) {
                try {
                    if (fileStore.deleteReferencedFile(row.id, row.internalName, row.path)) deleted++
                    else missing++
                } catch (_: Exception) {
                    // DELETED remains a 410 tombstone; the stored path can be retried safely later.
                    failures++
                }
            }
            afterId = batch.last().id
        }
        return SegmentCleanup(deleted, missing, failures)
    }

    private suspend fun cleanDeletedEventThumbnails(): Pair<Int, Int> {
        var deleted = 0
        var failures = 0
        var afterId: String? = null
        while (true) {
            val batch = eventDao.getDeletedForCleanup(afterId)
            if (batch.isEmpty()) break
            for (event in batch) {
                val name = event.thumbnailName ?: continue
                try {
                    if (thumbnailStore.delete(event.id)) deleted++
                    if (eventDao.confirmThumbnailDeleted(event.id, name, event.revision) != 1 &&
                        eventDao.getById(event.id)?.thumbnailName == name
                    ) {
                        failures++
                    }
                } catch (_: Exception) {
                    failures++
                }
            }
            afterId = batch.last().id
        }
        return deleted to failures
    }
}

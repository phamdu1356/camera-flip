package com.phamdu.flipcamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Bounded read access and revision-checked metadata writes for completed motion intervals. */
@Dao
abstract class MotionEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRecord(event: MotionEventEntity)

    @Query("""
        SELECT EXISTS(
          SELECT 1 FROM segments WHERE id = :segmentId AND status IN ('READY', 'RECORDING')
        )
    """)
    protected abstract suspend fun hasActiveSegment(segmentId: String): Boolean

    /** Reject late writes for deleted/failed segments; Room serializes this check and insert with segment deletion. */
    @Transaction
    open suspend fun insert(event: MotionEventEntity): Boolean {
        require(event.revision == 1 && event.status == MotionEventStatus.READY)
        if (event.segmentId != null && !hasActiveSegment(event.segmentId)) return false
        insertRecord(event)
        return true
    }

    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): MotionEventEntity?

    @Query("""
        SELECT * FROM events
        WHERE status = 'READY' AND segment_id = :segmentId
        ORDER BY start_utc ASC, id ASC LIMIT :limit
    """)
    protected abstract suspend fun selectReadyForSegment(segmentId: String, limit: Int): List<MotionEventEntity>

    suspend fun getReadyForSegment(segmentId: String, limit: Int = 500): List<MotionEventEntity> {
        requireCanonicalUuid(segmentId)
        require(limit in 1..500)
        return selectReadyForSegment(segmentId, limit)
    }

    @Query("""
        SELECT id, thumbnail_name FROM events
        WHERE thumbnail_name IS NOT NULL AND (:afterId IS NULL OR id > :afterId)
        ORDER BY id ASC LIMIT :limit
    """)
    protected abstract suspend fun selectThumbnailReferences(
        afterId: String?,
        limit: Int,
    ): List<MotionEventThumbnailReference>

    suspend fun getThumbnailReferences(
        afterId: String? = null,
        limit: Int = 500,
    ): List<MotionEventThumbnailReference> {
        if (afterId != null) requireCanonicalUuid(afterId)
        require(limit in 1..500)
        return selectThumbnailReferences(afterId, limit)
    }

    @Query("""
        SELECT * FROM events
        WHERE status = 'READY' AND end_utc > :fromUtc AND (:toUtc IS NULL OR start_utc < :toUtc)
        ORDER BY start_utc DESC, id DESC LIMIT :limit
    """)
    protected abstract suspend fun selectTimeline(fromUtc: Long, toUtc: Long?, limit: Int): List<MotionEventEntity>

    /** Includes events crossing either query boundary, with a hard maximum of 500 rows. */
    suspend fun getTimeline(fromUtc: Long, toUtc: Long?, limit: Int = 200): List<MotionEventEntity> {
        require(fromUtc >= 0L && (toUtc == null || toUtc > fromUtc))
        require(limit in 1..500)
        return selectTimeline(fromUtc, toUtc, limit)
    }

    /** Attach only the generated `<event UUID>.jpg` basename, never an arbitrary path. */
    @Query("""
        UPDATE events SET thumbnail_name = :thumbnailName, revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision AND revision < 2147483647
          AND status = 'READY' AND thumbnail_name IS NULL
    """)
    protected abstract suspend fun updateThumbnailName(
        id: String,
        thumbnailName: String,
        expectedRevision: Int,
    ): Int

    suspend fun attachThumbnail(id: String, thumbnailName: String, expectedRevision: Int): Int {
        requireCanonicalUuid(id)
        require(thumbnailName == "$id.jpg")
        require(expectedRevision > 0)
        return updateThumbnailName(id, thumbnailName, expectedRevision)
    }

    @Query("""
        SELECT * FROM events
        WHERE status = 'READY' AND end_utc < :cutoffUtc
          AND NOT EXISTS (
            SELECT 1 FROM segments
            WHERE segments.id = events.segment_id AND segments.protected = 1
          )
        ORDER BY end_utc ASC, id ASC LIMIT :limit
    """)
    protected abstract suspend fun selectExpired(cutoffUtc: Long, limit: Int): List<MotionEventEntity>

    /** Oldest first so a bounded retention pass makes deterministic progress. */
    suspend fun getExpired(cutoffUtc: Long, limit: Int = 200): List<MotionEventEntity> {
        require(cutoffUtc >= 0L)
        require(limit in 1..500)
        return selectExpired(cutoffUtc, limit)
    }

    @Query("""
        UPDATE events SET status = 'DELETED', revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision AND revision < 2147483647
          AND status = 'READY'
    """)
    protected abstract suspend fun markDeletedRecord(id: String, expectedRevision: Int): Int

    suspend fun markDeleted(id: String, expectedRevision: Int): Int {
        requireCanonicalUuid(id)
        require(expectedRevision > 0)
        return markDeletedRecord(id, expectedRevision)
    }

    @Query("""
        SELECT * FROM events
        WHERE status = 'DELETED' AND thumbnail_name IS NOT NULL
          AND (:afterId IS NULL OR id > :afterId)
        ORDER BY id ASC LIMIT :limit
    """)
    protected abstract suspend fun selectDeletedForCleanup(afterId: String?, limit: Int): List<MotionEventEntity>

    /** Includes only tombstones whose generated thumbnail still needs cleanup/retry. */
    suspend fun getDeletedForCleanup(afterId: String? = null, limit: Int = 200): List<MotionEventEntity> {
        if (afterId != null) requireCanonicalUuid(afterId)
        require(limit in 1..500)
        return selectDeletedForCleanup(afterId, limit)
    }

    @Query("""
        UPDATE events SET thumbnail_name = NULL, revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision AND revision < 2147483647
          AND status = 'DELETED' AND thumbnail_name = :expectedThumbnailName
    """)
    protected abstract suspend fun clearDeletedThumbnail(
        id: String,
        expectedThumbnailName: String,
        expectedRevision: Int,
    ): Int

    /** Call only after the app-private thumbnail file has been removed successfully or was absent. */
    suspend fun confirmThumbnailDeleted(id: String, expectedThumbnailName: String, expectedRevision: Int): Int {
        requireCanonicalUuid(id)
        require(expectedThumbnailName == "$id.jpg")
        require(expectedRevision > 0)
        return clearDeletedThumbnail(id, expectedThumbnailName, expectedRevision)
    }
}

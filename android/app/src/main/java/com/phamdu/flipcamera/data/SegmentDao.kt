package com.phamdu.flipcamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Metadata writes use compare-and-set revisions; a result of 0 means no change, stale revision,
 * invalid transition, or missing row. Callers must reread rather than assume success. No generic
 * update or REPLACE operation can erase a newer protection/status change. File I/O is outside
 * this DAO: these guards do not implement MP4 validation, flush/rename, or crash recovery.
 */
@Dao
abstract class SegmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRecording(segment: SegmentEntity)

    /** New records begin incomplete. Only finalizeSegment may publish READY metadata. */
    suspend fun insert(segment: SegmentEntity) {
        require(segment.status == SegmentStatus.RECORDING && segment.revision == 1)
        require(segment.endUtc == null && segment.duration == 0L && segment.bytes == 0L)
        insertRecording(segment)
    }

    @Query("SELECT * FROM segments WHERE id = :id LIMIT 1")
    abstract suspend fun getById(id: String): SegmentEntity?

    @Query("SELECT * FROM segments WHERE status = 'RECORDING' ORDER BY start_utc ASC, id ASC")
    abstract suspend fun getRecordingSegmentsForRecovery(): List<SegmentEntity>

    @Query("SELECT * FROM segments ORDER BY start_utc DESC, id DESC LIMIT 500")
    abstract fun getAllSegmentsFlow(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE session_id = :sessionId ORDER BY start_utc ASC, id ASC LIMIT 500")
    abstract fun getSegmentsBySession(sessionId: String): Flow<List<SegmentEntity>>

    @Query("""
        SELECT * FROM segments
        WHERE status = 'READY' AND end_utc > :fromUtc
          AND (:toUtc IS NULL OR start_utc < :toUtc)
        ORDER BY start_utc ASC, id ASC LIMIT :limit
    """)
    protected abstract suspend fun selectReadyTimeline(fromUtc: Long, toUtc: Long?, limit: Int): List<SegmentEntity>

    /** Includes segments crossing either query boundary, using half-open intervals [from, to). */
    suspend fun getReadyTimeline(fromUtc: Long, toUtc: Long?, limit: Int = 200): List<SegmentEntity> {
        requireWindow(fromUtc, toUtc, limit)
        return selectReadyTimeline(fromUtc, toUtc, limit)
    }

    @Query("""
        SELECT * FROM segments WHERE status = 'READY' AND end_utc > :fromUtc AND start_utc < :toUtc
        AND (start_utc > :afterStart OR (start_utc = :afterStart AND
            (id > :afterId OR (id = :afterId AND 'segment' > :afterType))))
        ORDER BY start_utc ASC, id ASC LIMIT :limit
    """)
    abstract suspend fun getReadyTimelinePage(fromUtc: Long, toUtc: Long, afterStart: Long, afterId: String, afterType: String, limit: Int): List<SegmentEntity>

    @Query("""
        SELECT * FROM segments WHERE status = 'READY'
        AND (start_utc > :afterStart OR (start_utc = :afterStart AND id > :afterId))
        ORDER BY start_utc ASC, id ASC LIMIT :limit
    """)
    abstract suspend fun getReadySegmentsPage(afterStart: Long, afterId: String, limit: Int): List<SegmentEntity>

    @Query("""
        SELECT * FROM segments WHERE status = 'READY'
        AND (start_utc > :afterStart OR (start_utc = :afterStart AND id > :afterId))
        ORDER BY start_utc ASC, id ASC LIMIT 1
    """)
    abstract suspend fun getNextReady(afterStart: Long, afterId: String): SegmentEntity?

    /** expectedStatus and expectedRevision must both come from the caller's latest snapshot. */
    @Query("""
        UPDATE segments SET protected = :isProtected, revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision AND revision < 2147483647
          AND status = :expectedStatus AND status IN ('RECORDING', 'READY', 'FAILED')
          AND protected != :isProtected
    """)
    abstract suspend fun setProtected(
        id: String, isProtected: Boolean, expectedStatus: SegmentStatus, expectedRevision: Int
    ): Int

    /**
     * Expected prior status is always RECORDING; publishReady checks it with expectedRevision
     * atomically and returns the affected-row count. Call only after closing/finalizing the MP4
     * and verifying it opens. startUtc is the observed recorder Start UTC, replacing the initial
     * request timestamp. Supply measured media duration, observed UTC end, and inspected video
     * dimensions/MIME/audio-track presence; requested recording quality can differ from the file.
     * finalInternalName/path point to the finalized app-private file, never a client-supplied path.
     */
    suspend fun finalizeSegment(
        id: String,
        expectedRevision: Int,
        startUtc: Long,
        endUtc: Long,
        duration: Long,
        bytes: Long,
        width: Int,
        height: Int,
        codec: String,
        audio: Boolean,
        finalInternalName: String,
        finalPath: String
    ): Int {
        require(expectedRevision > 0 && duration > 0L && bytes > 0L)
        require(endUtc > startUtc)
        require(width > 0 && height > 0 && codec.isNotBlank())
        requireInternalName(finalInternalName)
        require(finalPath.isNotBlank())
        return publishReady(
            id, expectedRevision, startUtc, endUtc, duration, bytes, width, height, codec, audio,
            finalInternalName, finalPath
        )
    }

    @Query("""
        UPDATE segments SET status = 'READY', start_utc = :startUtc, end_utc = :endUtc,
          duration = :duration, bytes = :bytes, width = :width, height = :height,
          codec = :codec, audio = :audio, internal_name = :internalName, path = :path,
          revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision AND revision < 2147483647
          AND status = 'RECORDING' AND :endUtc > :startUtc
          AND :duration > 0 AND :bytes > 0 AND :width > 0 AND :height > 0
          AND LENGTH(TRIM(:codec)) > 0
    """)
    protected abstract suspend fun publishReady(
        id: String, expectedRevision: Int, startUtc: Long, endUtc: Long, duration: Long, bytes: Long,
        width: Int, height: Int, codec: String, audio: Boolean,
        internalName: String, path: String
    ): Int

    /** Only expected prior status RECORDING can become FAILED, with the matching revision. */
    @Query("""
        UPDATE segments SET status = 'FAILED', revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision AND revision < 2147483647
          AND status = 'RECORDING'
    """)
    abstract suspend fun failRecording(id: String, expectedRevision: Int): Int

    /** Fail a recording and publish its unavailable interval atomically in the viewer timeline.
     * If setup failed before its RECORDING row was inserted, still publish the attempted interval.
     */
    @Transaction
    open suspend fun failRecordingWithGap(
        id: String,
        expectedRevision: Int,
        gap: GapEntity,
    ): Int {
        val changed = failRecording(id, expectedRevision)
        if (changed == 1 || getById(id) == null) insertGap(gap)
        return changed
    }

    @Query("""
        SELECT * FROM segments WHERE protected = 0
          AND ((status = 'READY' AND end_utc < :cutoffUtc)
            OR (status = 'FAILED' AND start_utc < :cutoffUtc))
        ORDER BY start_utc ASC, id ASC LIMIT 500
    """)
    abstract suspend fun getExpiredSegments(cutoffUtc: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE status = 'DELETED' AND (:afterId IS NULL OR id > :afterId) ORDER BY id ASC LIMIT :limit")
    protected abstract suspend fun selectDeletedSegmentsForCleanup(afterId: String?, limit: Int): List<SegmentEntity>

    suspend fun getDeletedSegmentsForCleanup(afterId: String?, limit: Int = 500): List<SegmentEntity> {
        require(limit in 1..500)
        return selectDeletedSegmentsForCleanup(afterId, limit)
    }

    /**
     * Claim an unprotected file for deletion before touching it: delete the file only when this
     * returns 1. Protection cannot subsequently be set on the tombstone. Keep metadata for 410
     * lookup; retry/orphan cleanup after file-deletion failures belongs to the storage layer.
     */
    @Query("""
        UPDATE segments SET status = 'DELETED', revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision AND revision < 2147483647
          AND protected = 0 AND status = :expectedStatus AND status IN ('READY', 'FAILED')
    """)
    abstract suspend fun markDeleted(
        id: String, expectedStatus: SegmentStatus, expectedRevision: Int
    ): Int

    /** Atomically tombstone linked events with the segment so a process stop cannot orphan READY events. */
    @Transaction
    open suspend fun markDeletedWithEvents(
        id: String,
        expectedStatus: SegmentStatus,
        expectedRevision: Int,
    ): Int {
        val changed = markDeleted(id, expectedStatus, expectedRevision)
        if (changed == 1) tombstoneReadyEventsForSegment(id)
        return changed
    }

    @Query("""
        UPDATE events SET status = 'DELETED',
          revision = CASE WHEN revision < 2147483647 THEN revision + 1 ELSE revision END
        WHERE segment_id = :segmentId AND status = 'READY'
    """)
    protected abstract suspend fun tombstoneReadyEventsForSegment(segmentId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertGapRecord(gap: GapEntity)

    suspend fun insertGap(gap: GapEntity) {
        require(gap.revision == 1)
        insertGapRecord(gap)
    }

    @Query("""
        SELECT * FROM gaps WHERE end_utc > :fromUtc AND (:toUtc IS NULL OR start_utc < :toUtc)
        ORDER BY start_utc ASC, id ASC LIMIT :limit
    """)
    protected abstract suspend fun selectGaps(fromUtc: Long, toUtc: Long?, limit: Int): List<GapEntity>

    suspend fun getGaps(fromUtc: Long, toUtc: Long?, limit: Int = 200): List<GapEntity> {
        requireWindow(fromUtc, toUtc, limit)
        return selectGaps(fromUtc, toUtc, limit)
    }

    @Query("""
        SELECT * FROM gaps WHERE end_utc > :fromUtc AND start_utc < :toUtc
        AND (start_utc > :afterStart OR (start_utc = :afterStart AND
            (id > :afterId OR (id = :afterId AND 'gap' > :afterType))))
        ORDER BY start_utc ASC, id ASC LIMIT :limit
    """)
    abstract suspend fun getGapTimelinePage(fromUtc: Long, toUtc: Long, afterStart: Long, afterId: String, afterType: String, limit: Int): List<GapEntity>

    private fun requireWindow(fromUtc: Long, toUtc: Long?, limit: Int) {
        require(toUtc == null || toUtc > fromUtc)
        require(limit in 1..500)
    }
}

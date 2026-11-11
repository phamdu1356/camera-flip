package com.phamdu.flipcamera.data

import kotlin.math.max

/** A completed motion interval (or an ongoing one with endUtc null) in the UTC millisecond domain. */
data class MotionInterval(val startUtc: Long, val endUtc: Long?) {
    init {
        require(startUtc >= 0L)
        require(endUtc == null || endUtc > startUtc)
    }
}

const val DEFAULT_PRE_ROLL_MS = 10_000L
const val DEFAULT_POST_ROLL_MS = 15_000L

/**
 * Pure keep/delete decision for one READY segment in motion-only mode.
 *
 * Keeps the segment when it is protected, or when its half-open interval [segmentStart, segmentEnd)
 * overlaps [eventStart - preRoll, eventEnd + postRoll) of any event. An event with endUtc null is
 * still ongoing and is treated as extending to nowUtc.
 */
fun shouldKeepSegment(
    segmentStartUtc: Long,
    segmentEndUtc: Long,
    isProtected: Boolean,
    motionIntervals: List<MotionInterval>,
    nowUtc: Long,
    preRollMs: Long = DEFAULT_PRE_ROLL_MS,
    postRollMs: Long = DEFAULT_POST_ROLL_MS,
): Boolean {
    require(segmentEndUtc > segmentStartUtc)
    require(preRollMs >= 0L && postRollMs >= 0L)
    if (isProtected) return true
    return motionIntervals.any { event ->
        val windowStart = event.startUtc - preRollMs
        val windowEnd = max(event.endUtc ?: nowUtc, event.startUtc) + postRollMs
        segmentStartUtc < windowEnd && segmentEndUtc > windowStart
    }
}

/**
 * Motion-only storage reduction. Only READY, unprotected segments with no overlapping motion are
 * tombstoned; file deletion then goes through RetentionManager's existing retryable cleanup.
 *
 * Caller contract: the motion table holds only completed intervals. While an interval is still in
 * progress, the caller must pass its start as [ongoingMotionStartUtc] so it can be protected.
 * Nothing is deleted unless [modeProvider] returns MOTION_ONLY.
 */
class SegmentPruner(
    private val segmentDao: SegmentDao,
    private val motionEventDao: MotionEventDao,
    private val retentionManager: RetentionManager,
    private val modeProvider: () -> RecordingMode = { RecordingMode.CONTINUOUS },
) {
    /**
     * Decide and, when safe, delete one segment. Returns false (keep) while the segment is still
     * within [graceMs] of its end, so a motion event that finishes late cannot be missed.
     */
    suspend fun pruneIfNoMotion(
        segmentId: String,
        nowUtc: Long,
        ongoingMotionStartUtc: Long? = null,
        graceMs: Long = DEFAULT_GRACE_MS,
    ): Boolean {
        if (modeProvider() != RecordingMode.MOTION_ONLY) return false
        val segment = segmentDao.getById(segmentId) ?: return false
        val deleted = pruneCandidate(segment, nowUtc, ongoingMotionStartUtc, graceMs)
        if (deleted) retentionManager.retryPendingCleanup()
        return deleted
    }

    /**
     * Walk READY segments that ended more than [graceMs] before [nowUtc] using a cursor, so kept
     * segments cannot starve later pages. Returns the number of segments tombstoned.
     */
    suspend fun pruneBacklog(
        nowUtc: Long,
        graceMs: Long = DEFAULT_GRACE_MS,
        ongoingMotionStartUtc: Long? = null,
    ): Int {
        if (modeProvider() != RecordingMode.MOTION_ONLY) return 0
        val cutoff = nowUtc - graceMs
        if (cutoff <= 0L) return 0
        var deleted = 0
        var afterStart = -1L
        var afterId = ""
        while (true) {
            val page = segmentDao.getReadyTimelinePage(0L, cutoff, afterStart, afterId, "segment", PAGE_SIZE)
            for (segment in page) {
                if (pruneCandidate(segment, nowUtc, ongoingMotionStartUtc, graceMs)) deleted++
            }
            if (page.size < PAGE_SIZE) break
            val last = page.last()
            afterStart = last.startUtc
            afterId = last.id
        }
        if (deleted > 0) retentionManager.retryPendingCleanup()
        return deleted
    }

    /**
     * Storage-policy eviction for ids chosen by [segmentsToEvict]. Protected rows are skipped and
     * the DAO's revision check guards against concurrent protection changes. Returns the count
     * tombstoned.
     */
    suspend fun evictSegments(segmentIds: List<String>): Int {
        var tombstoned = 0
        for (id in segmentIds) {
            val segment = segmentDao.getById(id) ?: continue
            if (tombstone(segment)) tombstoned++
        }
        if (tombstoned > 0) retentionManager.retryPendingCleanup()
        return tombstoned
    }

    private suspend fun pruneCandidate(
        segment: SegmentEntity,
        nowUtc: Long,
        ongoingMotionStartUtc: Long?,
        graceMs: Long,
    ): Boolean {
        if (segment.status != SegmentStatus.READY || segment.isProtected) return false
        val endUtc = segment.endUtc ?: return false
        if (endUtc + graceMs > nowUtc) return false

        // A linked READY event would be tombstoned with the segment, so it must block deletion.
        if (motionEventDao.getReadyForSegment(segment.id, limit = 1).isNotEmpty()) return false

        val windowStart = max(0L, segment.startUtc - DEFAULT_PRE_ROLL_MS)
        val windowEnd = endUtc + DEFAULT_POST_ROLL_MS
        val events = motionEventDao.getTimeline(windowStart, windowEnd, limit = MAX_EVENTS_PER_CHECK)
        // A full page may hide overlapping events, so fail closed.
        if (events.size >= MAX_EVENTS_PER_CHECK) return false

        val intervals = events.map { MotionInterval(it.startUtc, it.endUtc) } +
            listOfNotNull(ongoingMotionStartUtc?.let { MotionInterval(it, null) })
        if (shouldKeepSegment(segment.startUtc, endUtc, segment.isProtected, intervals, nowUtc)) return false

        return tombstone(segment)
    }

    private suspend fun tombstone(segment: SegmentEntity): Boolean {
        if (segment.isProtected) return false
        if (segment.status != SegmentStatus.READY && segment.status != SegmentStatus.FAILED) return false
        return segmentDao.markDeletedWithEvents(segment.id, segment.status, segment.revision) == 1
    }

    companion object {
        const val DEFAULT_GRACE_MS = 30_000L
        private const val PAGE_SIZE = 200
        private const val MAX_EVENTS_PER_CHECK = 500
    }
}

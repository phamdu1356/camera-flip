package com.phamdu.flipcamera.security

import com.phamdu.flipcamera.data.MotionEventDao
import com.phamdu.flipcamera.data.MotionEventStatus
import com.phamdu.flipcamera.data.MotionThumbnailStore
import com.phamdu.flipcamera.data.SegmentDao
import com.phamdu.flipcamera.data.SegmentStatus

/** Adapts local Room metadata and validated private thumbnails to the read-only viewer contract. */
internal class RoomViewerEventSource(
    private val events: MotionEventDao,
    private val segments: SegmentDao,
    private val thumbnails: MotionThumbnailStore,
) : ViewerEventSource {
    override suspend fun listEvents(fromUtc: Long, toUtc: Long, limit: Int): List<ViewerEventRecord> =
        events.getTimeline(fromUtc, toUtc, limit).map { event ->
            val readyOrigin = event.segmentId?.let { id ->
                segments.getById(id)?.takeIf { it.status == SegmentStatus.READY }
            }
            // If the original segment was deleted, keep the event metadata but do not expose its
            // thumbnail. Playback may continue from another READY segment overlapping the event.
            val overlappingSegments = segments.getReadyTimeline(event.startUtc, event.endUtc, limit = 200)
            val playbackSegment = overlappingSegments.firstOrNull { it.id == event.segmentId }
                ?: overlappingSegments.firstOrNull()
            ViewerEventRecord(
                id = event.id,
                kind = event.type.name,
                startUtc = event.startUtc,
                endUtc = event.endUtc,
                peakUtc = null,
                motionScore = event.peakScore.toDouble(),
                segmentId = playbackSegment?.id,
                segmentStartUtc = playbackSegment?.startUtc,
                segmentDurationMs = playbackSegment?.duration,
                thumbnailAvailable = (event.segmentId == null || readyOrigin != null) &&
                    event.status == MotionEventStatus.READY &&
                    event.thumbnailName?.let { name -> thumbnails.contains(event.id, name) } == true,
            )
        }

    override suspend fun openThumbnail(eventId: String): ViewerEventThumbnail? {
        val event = events.getById(eventId)
            ?.takeIf { it.status == MotionEventStatus.READY }
            ?: return null
        if (event.segmentId != null &&
            segments.getById(event.segmentId)?.status != SegmentStatus.READY
        ) return null
        val name = event.thumbnailName ?: return null
        val handle = runCatching { thumbnails.open(event.id, name) }.getOrNull() ?: return null
        return ViewerEventThumbnail(handle.stream, handle.byteLength)
    }
}

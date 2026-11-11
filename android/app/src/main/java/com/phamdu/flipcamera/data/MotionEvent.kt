package com.phamdu.flipcamera.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.util.UUID

enum class MotionEventType {
    MOTION
}

enum class MotionEventStatus {
    READY,
    DELETED,
}

/**
 * A completed motion interval. Times use the same UTC millisecond domain as segments and use a
 * half-open interval [startUtc, endUtc). The thumbnail is a generated basename only; callers
 * must resolve it beneath the app-private event-thumbnail directory.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["start_utc"]), Index(value = ["session_id", "start_utc"])],
)
data class MotionEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "session_id")
    val sessionId: String?,

    /** Segment active when the interval began; null for a transition between recording runs. */
    @ColumnInfo(name = "segment_id")
    val segmentId: String? = null,

    @ColumnInfo(name = "start_utc")
    val startUtc: Long,

    @ColumnInfo(name = "end_utc")
    val endUtc: Long,

    @ColumnInfo(name = "event_type")
    val type: MotionEventType = MotionEventType.MOTION,

    @ColumnInfo(name = "status")
    val status: MotionEventStatus = MotionEventStatus.READY,

    /** Peak changed-pixel fraction in [0, 1], useful only as a relative detector score. */
    @ColumnInfo(name = "peak_score")
    val peakScore: Float,

    /** Null until the thumbnail file has been written and the row is safely updated. */
    @ColumnInfo(name = "thumbnail_name")
    val thumbnailName: String? = null,

    @ColumnInfo(name = "revision")
    val revision: Int = 1,
) {
    init {
        requireCanonicalUuid(id)
        require(sessionId == null || sessionId.isNotBlank())
        require(segmentId == null || UUID.fromString(segmentId).toString() == segmentId)
        require(startUtc >= 0L && endUtc > startUtc)
        require(peakScore.isFinite() && peakScore in 0f..1f)
        require(thumbnailName == null || thumbnailName == "$id.jpg")
        require(revision > 0)
    }
}

class MotionEventConverters {
    @TypeConverter
    fun encodeType(type: MotionEventType): String = type.name

    @TypeConverter
    fun decodeType(value: String): MotionEventType = MotionEventType.valueOf(value)

    @TypeConverter
    fun encodeStatus(status: MotionEventStatus): String = status.name

    @TypeConverter
    fun decodeStatus(value: String): MotionEventStatus = MotionEventStatus.valueOf(value)
}

/** Minimal row used to reconcile generated thumbnail files after a process restart. */
data class MotionEventThumbnailReference(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "thumbnail_name") val thumbnailName: String,
)

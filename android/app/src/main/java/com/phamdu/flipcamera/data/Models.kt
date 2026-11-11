package com.phamdu.flipcamera.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.util.UUID

/** Only READY identifies a finalized file that the recorder has verified can be opened. */
enum class SegmentStatus {
    RECORDING,
    READY,
    FAILED,
    DELETED
}

enum class RecorderState {
    STOPPED,
    STARTING,
    RECORDING,
    STOPPING,
    ERROR
}

/**
 * Segment Contract from docs/ARCHITECTURE.md.
 *
 * UTC values are Unix epoch milliseconds; duration is measured media duration in milliseconds,
 * not the nominal segment length or a duration inferred from neighbouring files. endUtc is null
 * while recording. UUIDs are canonical strings to retain the existing SQLite/API representation.
 * internalName is an app-generated basename; path is local storage metadata, never a viewer URL.
 * audio describes the recorded track; it does not enable microphone capture.
 * RECORDING metadata is provisional: READY replaces startUtc with observed recorder Start UTC
 * and width/height/codec/audio with metadata inspected from the finalized file. codec is its
 * video MIME type (for example video/avc), rather than an assumption based on requested quality.
 */
@Entity(
    tableName = "segments",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["start_utc"]),
        Index(value = ["status", "start_utc"]),
        Index(value = ["protected", "end_utc"])
    ]
)
data class SegmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "start_utc")
    val startUtc: Long,

    @ColumnInfo(name = "end_utc")
    val endUtc: Long? = null,

    @ColumnInfo(name = "duration")
    val duration: Long = 0L,

    @ColumnInfo(name = "codec")
    val codec: String = "video/avc",

    @ColumnInfo(name = "width")
    val width: Int = 1280,

    @ColumnInfo(name = "height")
    val height: Int = 720,

    @ColumnInfo(name = "audio")
    val audio: Boolean = false,

    @ColumnInfo(name = "bytes")
    val bytes: Long = 0L,

    @ColumnInfo(name = "internal_name")
    val internalName: String,

    /**
     * Legacy local-only mapping retained from schema v1. Never serialize this field in viewer
     * responses: the external contract exposes only IDs and opaque internalName metadata.
     * Storage code must resolve/validate internalName under its app-private root instead of
     * trusting this stored absolute path. This model does not implement that storage boundary.
     */
    @ColumnInfo(name = "path")
    val path: String,

    @ColumnInfo(name = "status")
    val status: SegmentStatus = SegmentStatus.RECORDING,

    @ColumnInfo(name = "protected")
    val isProtected: Boolean = false,

    @ColumnInfo(name = "revision")
    val revision: Int = 1
) {
    init {
        requireCanonicalUuid(id)
        require(sessionId.isNotBlank())
        require(endUtc == null || endUtc >= startUtc)
        require(duration >= 0L && bytes >= 0L)
        require(codec.isNotBlank() && width > 0 && height > 0)
        requireInternalName(internalName)
        require(path.isNotBlank())
        require(revision > 0)
        require(status != SegmentStatus.READY ||
            (endUtc != null && endUtc > startUtc && duration > 0L && bytes > 0L)) {
            "READY requires finalized media metadata"
        }
    }
}

/**
 * A measured interval with no usable recording, using the same half-open UTC interval [start, end)
 * as timeline queries. Store observed boundaries, never invented padding to reach 60 seconds.
 * sessionId may be null for downtime between sessions; reason is local diagnostic metadata.
 */
@Entity(
    tableName = "gaps",
    indices = [Index(value = ["start_utc"]), Index(value = ["session_id"])]
)
data class GapEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    @ColumnInfo(name = "start_utc")
    val startUtc: Long,

    @ColumnInfo(name = "end_utc")
    val endUtc: Long,

    @ColumnInfo(name = "reason")
    val reason: String,

    @ColumnInfo(name = "revision")
    val revision: Int = 1
) {
    init {
        requireCanonicalUuid(id)
        require(sessionId == null || sessionId.isNotBlank())
        require(endUtc > startUtc)
        require(reason.isNotBlank())
        require(revision > 0)
    }
}

class SegmentConverters {
    @TypeConverter
    fun encodeStatus(status: SegmentStatus): String = status.name

    @TypeConverter
    fun decodeStatus(value: String): SegmentStatus = SegmentStatus.valueOf(value)
}

internal fun requireCanonicalUuid(value: String) {
    require(UUID.fromString(value).toString() == value) { "Expected a canonical UUID" }
}

internal fun requireInternalName(value: String) {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")) &&
        value != "." && value != "..") { "Expected an app-generated file basename" }
}

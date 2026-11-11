package com.phamdu.flipcamera.data

import android.content.Context

enum class RecordingMode {
    CONTINUOUS,
    MOTION_ONLY,
}

/**
 * SMOOTH: camera 30 fps khi thiết bị hỗ trợ (mặc định). SAVER: khóa khoảng ~15 fps cho cả pipeline
 * (ghi hình và live), đổi lấy dung lượng thấp hơn. Áp dụng ở lần kết nối camera kế tiếp.
 */
enum class RecordingFpsMode {
    SMOOTH,
    SAVER,
}

/** Giá trị lạ hoặc thiếu đều về SMOOTH, đúng mặc định an toàn của app. */
internal fun parseRecordingFpsMode(raw: String?): RecordingFpsMode =
    raw?.let { value -> RecordingFpsMode.entries.firstOrNull { it.name == value } } ?: RecordingFpsMode.SMOOTH

/** Storage limits. A zero limit means unlimited; the defaults leave current behaviour unchanged. */
data class StoragePolicyConfig(
    val mode: RecordingMode = RecordingMode.CONTINUOUS,
    val maxDays: Int = 0,
    val maxBytes: Long = 0L,
) {
    init {
        require(maxDays >= 0)
        require(maxBytes >= 0L)
    }
}

/** The fields a storage policy needs about one segment. */
data class StorageSegment(
    val id: String,
    val startUtc: Long,
    val bytes: Long,
    val isProtected: Boolean,
) {
    init {
        require(startUtc >= 0L && bytes >= 0L)
    }
}

private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * Chooses segment ids to evict, oldest first (by startUtc, then id). Protected segments are never
 * returned, but their bytes still count toward the total. Age is measured from startUtc.
 */
fun segmentsToEvict(
    segments: List<StorageSegment>,
    config: StoragePolicyConfig,
    nowUtc: Long,
): List<String> {
    require(segments.map { it.id }.distinct().size == segments.size) { "Segment ids must be unique" }
    val ordered = segments.sortedWith(compareBy({ it.startUtc }, { it.id }))
    val evicted = HashSet<String>()

    if (config.maxDays > 0) {
        val cutoffUtc = nowUtc - config.maxDays.toLong() * DAY_MS
        for (segment in ordered) {
            if (!segment.isProtected && segment.startUtc < cutoffUtc) evicted.add(segment.id)
        }
    }

    if (config.maxBytes > 0L) {
        var total = ordered.sumOf { if (it.id in evicted) 0L else it.bytes }
        for (segment in ordered) {
            if (total <= config.maxBytes) break
            if (segment.isProtected || segment.id in evicted) continue
            evicted.add(segment.id)
            total -= segment.bytes
        }
    }

    return ordered.filter { it.id in evicted }.map { it.id }
}

/**
 * Persists [StoragePolicyConfig] in SharedPreferences, following CameraQualityPreference. Invalid
 * stored values fall back to the safe defaults.
 */
class StoragePolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): StoragePolicyConfig {
        val mode = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
            ?: RecordingMode.CONTINUOUS
        val maxDays = prefs.getInt(KEY_MAX_DAYS, 0).coerceAtLeast(0)
        val maxBytes = if (prefs.contains(KEY_MAX_BYTES)) {
            prefs.getLong(KEY_MAX_BYTES, DEFAULT_MAX_STORAGE_BYTES).coerceAtLeast(0L)
        } else {
            DEFAULT_MAX_STORAGE_BYTES
        }
        return StoragePolicyConfig(mode, maxDays, maxBytes)
    }

    fun save(config: StoragePolicyConfig) {
        prefs.edit()
            .putString(KEY_MODE, config.mode.name)
            .putInt(KEY_MAX_DAYS, config.maxDays)
            .putLong(KEY_MAX_BYTES, config.maxBytes)
            .apply()
    }

    /** Chế độ FPS lưu riêng với chính sách lưu trữ; mặc định SMOOTH. */
    fun loadRecordingFpsMode(): RecordingFpsMode =
        parseRecordingFpsMode(prefs.getString(KEY_FPS_MODE, null))

    fun saveRecordingFpsMode(mode: RecordingFpsMode) {
        prefs.edit().putString(KEY_FPS_MODE, mode.name).apply()
    }

    /** Codec ghi hình; mặc định H264. Giá trị lạ hoặc sai kiểu đều về H264. */
    fun loadVideoCodecMode(): VideoCodecMode =
        parseVideoCodecMode(runCatching { prefs.getString(KEY_CODEC, null) }.getOrNull())

    fun saveVideoCodecMode(mode: VideoCodecMode) {
        prefs.edit().putString(KEY_CODEC, mode.name).apply()
    }

    /** Bật tối ưu encoder (thử nghiệm); mặc định tắt. Giá trị sai kiểu được coi là tắt. */
    fun loadEncoderTuning(): Boolean =
        runCatching { prefs.getBoolean(KEY_ENCODER_TUNING, false) }.getOrDefault(false)

    fun saveEncoderTuning(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENCODER_TUNING, enabled).apply()
    }

    /** Ghi âm thanh vào MP4; mặc định tắt, chỉ áp dụng ở lần kết nối camera kế tiếp. */
    fun loadAudioEnabled(): Boolean =
        runCatching { prefs.getBoolean(KEY_AUDIO, false) }.getOrDefault(false)

    fun saveAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO, enabled).apply()
    }

    /** Phát âm thanh qua loa máy từ viewer (Talkback); mặc định bật. */
    fun loadSpeakerEnabled(): Boolean =
        runCatching { prefs.getBoolean(KEY_SPEAKER, true) }.getOrDefault(true)

    fun saveSpeakerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAKER, enabled).apply()
    }

    companion object {
        const val PREFERENCES = "storage-policy"
        const val KEY_MODE = "recording-mode"
        const val KEY_MAX_DAYS = "max-days"
        const val KEY_MAX_BYTES = "max-bytes"
        const val KEY_FPS_MODE = "recording-fps-mode"
        const val KEY_CODEC = "recording-codec"
        const val KEY_ENCODER_TUNING = "recording-encoder-tuning"
        const val KEY_AUDIO = "recording-audio"
        const val KEY_SPEAKER = "recording-speaker"
        const val DEFAULT_MAX_STORAGE_GB = 40L
        const val DEFAULT_MAX_STORAGE_BYTES = 40L * 1024L * 1024L * 1024L
    }
}

/** H264 là mặc định; HEVC chỉ có hiệu lực khi máy có encoder HEVC (kiểm tra lúc bind camera). */
enum class VideoCodecMode {
    H264,
    HEVC,
}

internal fun parseVideoCodecMode(raw: String?): VideoCodecMode =
    raw?.let { value -> VideoCodecMode.entries.firstOrNull { it.name == value } } ?: VideoCodecMode.H264

const val MIME_AVC = "video/avc"
const val MIME_HEVC = "video/hevc"

/** Thông số khi bật tối ưu encoder: encoder chạy 15 fps trong khi camera/live vẫn 30 fps. */
const val TUNED_ENCODER_FRAME_RATE = 15
const val TUNED_I_FRAME_INTERVAL_SECONDS = 2

/** Kế hoạch cấu hình encoder. Trường null nghĩa là giữ nguyên mặc định của CameraX. */
data class TuningPlan(
    val mime: String,
    val frameRate: Int?,
    val iFrameSeconds: Int?,
) {
    val isDefault: Boolean
        get() = mime == MIME_AVC && frameRate == null && iFrameSeconds == null
}

/**
 * Quyết định cấu hình encoder lúc bind camera. HEVC chỉ được chọn khi [hasHevcEncoder] là true;
 * nếu không thì giữ H264. Tối ưu fps/I-frame chỉ áp dụng khi [tuning] bật.
 */
fun resolveEncoderTuning(codec: VideoCodecMode, tuning: Boolean, hasHevcEncoder: Boolean): TuningPlan {
    val mime = if (codec == VideoCodecMode.HEVC && hasHevcEncoder) MIME_HEVC else MIME_AVC
    return if (tuning) {
        TuningPlan(mime, TUNED_ENCODER_FRAME_RATE, TUNED_I_FRAME_INTERVAL_SECONDS)
    } else {
        TuningPlan(mime, null, null)
    }
}

const val CONTINUOUS_SEGMENT_DURATION_MS = 5L * 60L * 1_000L
const val MOTION_SEGMENT_DURATION_MS = 15_000L

/**
 * Ghi theo chuyển động dùng đoạn 15 giây để giữ đúng cửa sổ ±10s/15s. Chế độ liên tục dùng đoạn 5 phút
 * (người dùng yêu cầu tối thiểu 5 phút): ít lần finalize/khởi động lại muxer hơn nên ít khoảng hở giữa
 * các đoạn hơn và nhẹ phần cứng hơn; bitrate/độ phân giải không đổi. Đánh đổi: nếu app/máy sập giữa
 * đoạn thì mất tối đa 5 phút của đoạn đang ghi (tệp MP4 chưa finalize), thay vì 1 phút.
 */
fun segmentDurationMs(mode: RecordingMode): Long = when (mode) {
    RecordingMode.CONTINUOUS -> CONTINUOUS_SEGMENT_DURATION_MS
    RecordingMode.MOTION_ONLY -> MOTION_SEGMENT_DURATION_MS
}

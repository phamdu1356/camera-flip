package com.phamdu.flipcamera

/** Shared, bounded clock mapping for both effect and analysis frames in one camera binding. */
internal class WatermarkFrameClock(
    realtimeSource: Boolean,
    private val anchorElapsedNanos: Long,
    private val anchorUtcMs: Long,
) {
    private val lastTimestamp = LongArray(2)
    init {
        require(realtimeSource) { "Camera không có timestamp REALTIME; chưa thể đóng dấu thời gian khung hình." }
        require(anchorElapsedNanos > 0 && anchorUtcMs > 0)
    }

    @Synchronized
    fun utcMs(timestamp: Long, stream: Int, nowElapsedNanos: Long, nowUtcMs: Long): Long {
        require(stream in 0..1)
        check(timestamp > 0 && timestamp >= lastTimestamp[stream]) { "Timestamp camera bị thiếu hoặc quay lùi." }
        val age = nowElapsedNanos - timestamp
        check(age in -250_000_000L..10_000_000_000L) { "Timestamp camera không cùng clock hoặc khung hình quá cũ." }
        val expectedNow = anchorUtcMs + (nowElapsedNanos - anchorElapsedNanos) / 1_000_000L
        check(nowUtcMs - expectedNow in -2_000L..2_000L) { "Đồng hồ UTC đã thay đổi; cần kết nối lại camera để đóng dấu đúng." }
        lastTimestamp[stream] = timestamp
        return anchorUtcMs + (timestamp - anchorElapsedNanos) / 1_000_000L
    }
}

/** Inverse of CameraX's crop -> clockwise rotation -> horizontal mirroring. */
internal class WatermarkTransform(
    left: Int, top: Int, right: Int, bottom: Int, rotation: Int, mirrored: Boolean,
) {
    private val width = right - left
    private val height = bottom - top
    val displayWidth: Int = if (rotation % 180 == 0) width else height
    val displayHeight: Int = if (rotation % 180 == 0) height else width
    val matrixValues: FloatArray
    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0 && rotation in listOf(0, 90, 180, 270))
        fun inverse(x: Float, y: Float): Pair<Float, Float> {
            val u = if (mirrored) displayWidth - x else x
            val p = when (rotation) {
                0 -> u to y
                90 -> y to (height - u)
                180 -> (width - u) to (height - y)
                else -> (width - y) to u
            }
            return (p.first + left) to (p.second + top)
        }
        val origin = inverse(0f, 0f)
        val x = inverse(1f, 0f)
        val y = inverse(0f, 1f)
        matrixValues = floatArrayOf(x.first - origin.first, y.first - origin.first, origin.first,
            x.second - origin.second, y.second - origin.second, origin.second, 0f, 0f, 1f)
    }
    fun bufferPoint(x: Float, y: Float): Pair<Float, Float> =
        (matrixValues[0] * x + matrixValues[1] * y + matrixValues[2]) to
            (matrixValues[3] * x + matrixValues[4] * y + matrixValues[5])
}

/** Sticky first failure: callbacks from the same binding must never re-enable recording. */
internal class WatermarkHealth {
    @Volatile var failure: String? = null
        private set
    @Synchronized fun fail(message: String): Boolean {
        if (failure != null) return false
        failure = message
        return true
    }
}

/**
 * One segment, one binding. seal() at encoder Finalize is the linearization point:
 * a failure delivered before sealing rejects publication; later faults belong to
 * the remaining pipeline and cannot revoke this already closed frame set.
 * No monitor is held during media inspection or Room writes.
 */
internal class WatermarkSegmentPublication(val segmentId: String, val generation: Int) {
    private var invalid = false
    private var sealed = false
    private var issuedPermit: Permit? = null
    @Synchronized fun invalidate(): Boolean {
        if (sealed) return false
        invalid = true
        return true
    }
    @Synchronized fun seal(): Permit? {
        check(!sealed) { "Segment publication already sealed" }
        sealed = true
        return if (invalid) null else Permit(segmentId, generation).also { issuedPermit = it }
    }
    suspend fun <T> publish(permit: Permit?, commit: suspend () -> T): T {
        synchronized(this) {
            check(permit != null && permit === issuedPermit && sealed && !invalid) {
                "Segment watermark chưa được encoder Finalize xác nhận."
            }
        }
        // This ticket represents the CLOSED frame set. Subsequent callbacks must not
        // revoke it while Room suspends; commit failure still leaves recovery guards.
        return commit()
    }
    data class Permit(val segmentId: String, val generation: Int)
}

package com.phamdu.flipcamera

internal class CameraBindingToken(val generation: Int)
internal enum class CameraHealthPhase { OPENING, REOPENING, OPEN, ERROR, CLOSED }
internal enum class LiveHealth { IDLE, UNAVAILABLE, WAITING, FRESH, STALE }
internal data class CameraHealthSnapshot(
    val phase: CameraHealthPhase,
    val errorCode: Int?,
    val live: LiveHealth,
    val rawFresh: Boolean,
) {
    val canStart: Boolean get() = phase == CameraHealthPhase.OPEN && errorCode == null
    fun description(): String {
        val camera = when (phase) {
            CameraHealthPhase.OPENING -> "Đang mở camera"
            CameraHealthPhase.REOPENING -> "Camera đang thử mở lại"
            CameraHealthPhase.ERROR -> "Camera lỗi"
            CameraHealthPhase.CLOSED -> "Camera đã đóng"
            CameraHealthPhase.OPEN -> "Camera đã mở"
        }
        val preview = when (live) {
            LiveHealth.IDLE -> "chưa yêu cầu live"
            LiveHealth.UNAVAILABLE -> "live không khả dụng"
            LiveHealth.WAITING -> "chờ JPEG mới"
            LiveHealth.STALE -> "chưa có JPEG mới"
            LiveHealth.FRESH -> "JPEG mới đã sẵn sàng"
        }
        return "$camera${errorCode?.let { " (mã $it)" } ?: ""} · $preview"
    }
}

/** Diagnostic/readiness only. Never owns recorder intent, stop, rotation or READY publication. */
internal class CameraHealthMonitor {
    private var token: CameraBindingToken? = null
    private var phase = CameraHealthPhase.CLOSED
    private var errorCode: Int? = null
    private var epoch = 0L
    private var openedAtNanos = 0L
    private var rawAtNanos = 0L
    private var jpegAtNanos = 0L
    private var analysisAvailable = false

    @Synchronized fun bind(generation: Int): CameraBindingToken {
        token = CameraBindingToken(generation)
        phase = CameraHealthPhase.OPENING
        errorCode = null
        analysisAvailable = false
        resetFrames()
        return token!!
    }

    @Synchronized fun current(binding: CameraBindingToken): Boolean = token === binding

    @Synchronized fun setAnalysis(binding: CameraBindingToken, available: Boolean) {
        if (token === binding) analysisAvailable = available
    }

    @Synchronized fun update(binding: CameraBindingToken, state: CameraHealthPhase, error: Int?, nowNanos: Long): Boolean {
        if (token !== binding) return false
        val changed = phase != state || errorCode != error
        if (changed) {
            phase = state
            errorCode = error
            resetFrames()
            if (state == CameraHealthPhase.OPEN && error == null) openedAtNanos = nowNanos
        }
        return changed
    }

    data class FrameTicket(val binding: CameraBindingToken, val epoch: Long, val captureNanos: Long)

    @Synchronized fun frame(binding: CameraBindingToken, captureNanos: Long, nowNanos: Long): FrameTicket? {
        if (token !== binding || phase != CameraHealthPhase.OPEN || errorCode != null ||
            captureNanos < openedAtNanos || captureNanos <= 0 || nowNanos - captureNanos !in 0..FRESH_NS) return null
        rawAtNanos = nowNanos
        return FrameTicket(binding, epoch, captureNanos)
    }

    @Synchronized fun publish(ticket: FrameTicket, nowNanos: Long, publishJpeg: () -> Boolean): Boolean {
        if (token !== ticket.binding || ticket.epoch != epoch || phase != CameraHealthPhase.OPEN ||
            errorCode != null || nowNanos - ticket.captureNanos !in 0..FRESH_NS) return false
        // Only a bounded in-memory hub publication; never encode/IO/Room under this monitor.
        if (!publishJpeg()) return false
        jpegAtNanos = nowNanos
        return true
    }

    @Synchronized fun snapshot(nowNanos: Long, hasViewer: Boolean): CameraHealthSnapshot {
        val rawFresh = rawAtNanos > 0 && nowNanos - rawAtNanos in 0..FRESH_NS
        val live = when {
            !analysisAvailable -> LiveHealth.UNAVAILABLE
            !hasViewer -> LiveHealth.IDLE
            jpegAtNanos == 0L -> LiveHealth.WAITING
            nowNanos - jpegAtNanos in 0..FRESH_NS -> LiveHealth.FRESH
            else -> LiveHealth.STALE
        }
        return CameraHealthSnapshot(phase, errorCode, live, rawFresh)
    }

    @Synchronized fun detach() {
        token = null
        phase = CameraHealthPhase.CLOSED
        errorCode = null
        analysisAvailable = false
        resetFrames()
    }

    private fun resetFrames() {
        epoch++
        openedAtNanos = 0L
        rawAtNanos = 0L
        jpegAtNanos = 0L
    }
    private companion object { const val FRESH_NS = 2_000_000_000L }
}

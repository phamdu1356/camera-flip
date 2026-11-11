package com.phamdu.flipcamera.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Đếm khung hình và byte đã gửi cho luồng xem trực tiếp của từng phiên, trong cửa sổ trượt ngắn.
 * Số liệu là tốc độ gửi thực tế: khi mạng chậm, ghi vào kênh bị chặn nên fps và tốc độ tụt theo.
 * Chỉ nằm trong bộ nhớ, khóa theo token phiên và bị xóa khi luồng kết thúc.
 */
internal class LiveStreamStats(private val windowMs: Long = WINDOW_MS) {
    data class Snapshot(val fps: Float, val kbps: Int)

    private class Window {
        val times = ArrayDeque<Long>()
        val sizes = ArrayDeque<Int>()
        var bytes = 0L
    }

    private val windows = ConcurrentHashMap<String, Window>()

    fun record(token: String, bytes: Int, nowMs: Long) {
        val w = windows.getOrPut(token) { Window() }
        synchronized(w) {
            w.times.addLast(nowMs); w.sizes.addLast(bytes); w.bytes += bytes
            trim(w, nowMs)
        }
    }

    fun snapshot(token: String, nowMs: Long): Snapshot? {
        val w = windows[token] ?: return null
        synchronized(w) {
            trim(w, nowMs)
            if (w.times.size < 2) return null
            val spanMs = (w.times.last() - w.times.first()).coerceAtLeast(1L)
            val fps = (w.times.size - 1) * 1000f / spanMs
            val kbps = (w.bytes * 8L * 1000L / spanMs / 1000L).toInt()
            return Snapshot(fps, kbps)
        }
    }

    fun clear(token: String) { windows.remove(token) }

    private fun trim(w: Window, nowMs: Long) {
        while (w.times.isNotEmpty() && nowMs - w.times.first() > windowMs) {
            w.times.removeFirst(); w.bytes -= w.sizes.removeFirst()
        }
    }

    companion object { const val WINDOW_MS = 3_000L }
}

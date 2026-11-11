package com.phamdu.flipcamera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Watermark overlay utilities for Flip Camera.
 * Burned directly into pixels on:
 * 1. VideoCapture (MP4 files) via CameraX OverlayEffect
 * 2. ViewerFrameHub (live MJPEG stream and motion thumbnails) via encodeViewerFrame
 *
 * Fixed timezone: Asia/Bangkok (UTC+07:00).
 * Format: "FLIP CAMERA | dd/MM/yyyy HH:mm:ss | UTC+07:00"
 */
object WatermarkOverlay {
    val TIME_ZONE_UTC7: ZoneId = ZoneId.of("Asia/Bangkok")
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        .withZone(TIME_ZONE_UTC7)

    @Volatile private var cachedSecond = -1L
    @Volatile private var cachedText = ""

    private class PainterPaints {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val backgroundRect = RectF()
    }

    private val threadPaints = ThreadLocal.withInitial { PainterPaints() }

    /** Format watermark text from UTC timestamp in milliseconds. Caches per epoch second to eliminate redundant allocations. */
    fun formatText(utcMs: Long): String {
        val second = utcMs / 1000L
        val cached = cachedText
        if (second == cachedSecond && cached.isNotEmpty()) {
            return cached
        }
        val instant = Instant.ofEpochMilli(utcMs)
        val text = "FLIP CAMERA | ${DATE_FORMATTER.format(instant)} | UTC+07:00"
        cachedText = text
        cachedSecond = second
        return text
    }

    /**
     * Draw watermark onto the provided canvas.
     * Positions text in a rounded semi-transparent dark rectangle near the bottom-left corner.
     */
    fun draw(canvas: Canvas, width: Int, height: Int, text: String) {
        require(width >= 50 && height >= 50) { "Khung hình quá nhỏ cho watermark." }
        val paints = checkNotNull(threadPaints.get())
        val minDim = minOf(width, height)

        var textSize = (minDim * 0.032f).coerceIn(12f, 32f)
        paints.textPaint.textSize = textSize

        var textWidth = paints.textPaint.measureText(text)

        val paddingX = textSize * 0.5f
        val paddingY = textSize * 0.35f
        val marginX = (minDim * 0.035f).coerceAtLeast(12f)
        val marginY = (minDim * 0.035f).coerceAtLeast(12f)

        val maxAvailableWidth = width - (2 * marginX) - (2 * paddingX)
        if (textWidth > maxAvailableWidth && maxAvailableWidth > 20f) {
            val scale = maxAvailableWidth / textWidth
            textSize = (textSize * scale).coerceAtLeast(10f)
            paints.textPaint.textSize = textSize
            textWidth = paints.textPaint.measureText(text)
        }
        check(textWidth <= maxAvailableWidth) { "Watermark không vừa vùng hiển thị." }
        val fontMetrics = paints.textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent

        val boxLeft = marginX
        val boxRight = boxLeft + textWidth + (2 * paddingX)
        val boxBottom = height - marginY
        val boxTop = boxBottom - textHeight - (2 * paddingY)
        val cornerRadius = textSize * 0.25f

        paints.backgroundRect.set(boxLeft, boxTop, boxRight, boxBottom)
        canvas.drawRoundRect(paints.backgroundRect, cornerRadius, cornerRadius, paints.backgroundPaint)

        val textX = boxLeft + paddingX
        val textY = boxBottom - paddingY - fontMetrics.descent
        canvas.drawText(text, textX, textY, paints.textPaint)
    }
}

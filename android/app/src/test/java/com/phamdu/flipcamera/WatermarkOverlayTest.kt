package com.phamdu.flipcamera

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class WatermarkOverlayTest {

    @Test
    fun formatsWatermarkWithFixedUtc7RegardlessOfDefaultTimezone() {
        val originalDefault = TimeZone.getDefault()
        try {
            // Test with different machine default time zones
            for (tzName in listOf("UTC", "America/New_York", "Asia/Tokyo", "Europe/London")) {
                TimeZone.setDefault(TimeZone.getTimeZone(tzName))

                // 2026-10-31 14:30:45 UTC -> 2026-10-31 21:30:45 UTC+7
                val instant = Instant.parse("2026-10-31T14:30:45Z")
                val text = WatermarkOverlay.formatText(instant.toEpochMilli())

                assertEquals(
                    "FLIP CAMERA | 31/10/2026 21:30:45 | UTC+07:00",
                    text,
                )
            }
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test
    fun formatsSingleDigitDaysAndMonthsWithLeadingZero() {
        // 2026-05-09 01:05:07 UTC -> 2026-05-09 08:05:07 UTC+7
        val instant = Instant.parse("2026-05-09T01:05:07Z")
        val text = WatermarkOverlay.formatText(instant.toEpochMilli())

        assertEquals(
            "FLIP CAMERA | 09/05/2026 08:05:07 | UTC+07:00",
            text,
        )
    }

    @Test
    fun cachesWatermarkTextWithinSameSecondAndUpdatesOnNextSecond() {
        val t0 = 1793411855000L // arbitrary second boundary
        val text0 = WatermarkOverlay.formatText(t0)
        val text1 = WatermarkOverlay.formatText(t0 + 200L) // +200ms
        val text2 = WatermarkOverlay.formatText(t0 + 999L) // +999ms

        // Exact same cached instance within same epoch second
        org.junit.Assert.assertSame(text0, text1)
        org.junit.Assert.assertSame(text0, text2)

        // Next second generates updated watermark
        val textNextSec = WatermarkOverlay.formatText(t0 + 1000L)
        org.junit.Assert.assertNotEquals(text0, textNextSec)
    }
}

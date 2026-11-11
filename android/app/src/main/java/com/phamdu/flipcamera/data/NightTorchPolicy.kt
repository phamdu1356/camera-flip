package com.phamdu.flipcamera.data

/**
 * Chính sách đèn flash (torch) ban đêm theo nhiệt độ.
 *
 * - Ban đêm ([NIGHT_START_HOUR]..[NIGHT_END_HOUR) giờ địa phương): bật flash.
 * - Nhiệt độ vượt [OFF_ABOVE_C] thì tắt flash và giữ tắt cho đến khi nhiệt độ
 *   xuống tới [ON_BELOW_OR_EQUAL_C] (trễ 2 °C để không bật/tắt liên tục quanh ngưỡng).
 * - Ban ngày: không tự bật; nếu chính sách đã bật thì tắt khi sang ngày.
 */
object NightTorchPolicy {
    const val NIGHT_START_HOUR = 18
    const val NIGHT_END_HOUR = 6
    const val OFF_ABOVE_C = 45.0f
    const val ON_BELOW_OR_EQUAL_C = 43.0f

    data class Decision(val torchOn: Boolean, val heatLocked: Boolean)

    fun isNight(hourOfDay: Int): Boolean =
        hourOfDay >= NIGHT_START_HOUR || hourOfDay < NIGHT_END_HOUR

    fun decide(hourOfDay: Int, tempC: Float?, heatLocked: Boolean): Decision {
        val locked = when {
            tempC == null -> heatLocked
            tempC > OFF_ABOVE_C -> true
            tempC <= ON_BELOW_OR_EQUAL_C -> false
            else -> heatLocked
        }
        return Decision(torchOn = isNight(hourOfDay) && !locked, heatLocked = locked)
    }
}

package com.phamdu.flipcamera

import android.content.Context
import androidx.camera.video.Quality

/** The capture profile is changed locally while the camera service is stopped. */
internal object CameraQualityPreference {
    const val PREFERENCES = "camera-settings"
    const val KEY_QUALITY = "video-quality"
    const val HD = "hd"
    const val SD = "sd"

    fun read(context: Context): Quality =
        if (context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_QUALITY, HD) == SD) {
            Quality.SD
        } else {
            Quality.HD
        }

    fun store(context: Context, quality: Quality) {
        val value = if (quality == Quality.SD) SD else HD
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUALITY, value)
            .apply()
    }
}

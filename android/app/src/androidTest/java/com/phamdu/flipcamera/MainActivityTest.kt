package com.phamdu.flipcamera

import android.content.Context
import android.widget.Spinner
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test
    fun launchDoesNotConnectCameraOrEnableRecordingControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(R.id.btnConnect).isEnabled)
                assertFalse(activity.findViewById<android.view.View>(R.id.btnStartRecording).isEnabled)
                assertFalse(activity.findViewById<android.view.View>(R.id.btnStopRecording).isEnabled)
                assertFalse(activity.findViewById<android.view.View>(R.id.btnStopCamera).isEnabled)
                val viewerNotice = activity.findViewById<android.widget.TextView>(R.id.tvViewerNotice).text.toString()
                assertTrue(viewerNotice.contains("ghép đôi"))
                assertFalse(viewerNotice.contains("đang được tích hợp"))
                assertFalse(viewerNotice.contains("chưa hoàn thành"))
            }
        }
    }

    @Test
    fun qualitySelectionPersistsAcrossActivityRecreationBeforeCameraConnect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(CameraQualityPreference.PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getString(CameraQualityPreference.KEY_QUALITY, null)
        try {
            CameraQualityPreference.store(context, androidx.camera.video.Quality.HD)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val spinner = activity.findViewById<Spinner>(R.id.spinnerCameraQuality)
                    assertTrue(spinner.isEnabled)
                    assertEquals(0, spinner.selectedItemPosition)
                    spinner.setSelection(1)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(CameraQualityPreference.SD, preferences.getString(CameraQualityPreference.KEY_QUALITY, null))

                scenario.recreate()
                scenario.onActivity { activity ->
                    val spinner = activity.findViewById<Spinner>(R.id.spinnerCameraQuality)
                    assertTrue(spinner.isEnabled)
                    assertEquals(1, spinner.selectedItemPosition)
                }
            }
        } finally {
            if (previous == null) preferences.edit().remove(CameraQualityPreference.KEY_QUALITY).commit()
            else preferences.edit().putString(CameraQualityPreference.KEY_QUALITY, previous).commit()
        }
    }
}

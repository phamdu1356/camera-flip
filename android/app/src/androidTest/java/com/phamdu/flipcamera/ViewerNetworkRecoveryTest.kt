package com.phamdu.flipcamera

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerNetworkRecoveryTest {
    private var previousQuality: String? = null
    private var previousWifiEnabled: Int? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test
    fun viewerServerDropsStaleEndpointOnWifiLossAndRestartsWhenWifiReturns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val preferences = context.getSharedPreferences(CameraQualityPreference.PREFERENCES, Context.MODE_PRIVATE)
        previousQuality = preferences.getString(CameraQualityPreference.KEY_QUALITY, null)
        previousWifiEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0)
        CameraQualityPreference.store(context, androidx.camera.video.Quality.SD)

        try {
            executeShell("svc wifi enable && echo wifi-on")
            assertTrue("Emulator did not connect to Wi-Fi", awaitCondition(30_000L) { hasWifiNetwork(connectivity) })

            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activeScenario = checkNotNull(scenario)
            activeScenario.onActivity { activity -> activity.findViewById<android.view.View>(R.id.btnConnect).performClick() }
            val cameraState = awaitRecorderStatus(activeScenario, 45_000L) {
                it.contains("sẵn sàng", ignoreCase = true) || it.contains("lỗi", ignoreCase = true)
            }
            assertTrue("Camera was not ready: $cameraState", cameraState.contains("sẵn sàng", ignoreCase = true))

            activeScenario.onActivity { activity ->
                val startViewer = activity.findViewById<android.view.View>(R.id.btnStartViewer)
                assertTrue("Viewer start should be available after camera connect", startViewer.isEnabled)
                startViewer.performClick()
            }
            val initial = awaitViewerState(activeScenario, 30_000L) {
                it.status.contains("HTTPS đang chạy", ignoreCase = true) || it.status.contains("lỗi", ignoreCase = true)
            }
            assertTrue("Viewer did not start: ${initial.status}", initial.status.contains("HTTPS đang chạy", ignoreCase = true))
            assertTrue("Viewer did not advertise a LAN endpoint: ${initial.endpoints}", initial.endpoints.contains("https://"))

            executeShell("svc wifi disable && echo wifi-off")
            assertTrue("Wi-Fi network did not disappear", awaitCondition(30_000L) { !hasWifiNetwork(connectivity) })
            val afterLoss = awaitViewerState(activeScenario, 30_000L) { state ->
                state.status.contains("Không tìm thấy địa chỉ LAN", ignoreCase = true) ||
                    (state.status.contains("HTTPS đang chạy", ignoreCase = true) && state.endpoints != initial.endpoints)
            }
            assertTrue(
                "Viewer kept advertising a stale Wi-Fi endpoint after Wi-Fi loss: $afterLoss",
                afterLoss.status.contains("Không tìm thấy địa chỉ LAN", ignoreCase = true) ||
                    afterLoss.endpoints != initial.endpoints,
            )

            executeShell("svc wifi enable && echo wifi-on")
            assertTrue("Wi-Fi network did not return", awaitCondition(30_000L) { hasWifiNetwork(connectivity) })
            val recovered = awaitViewerState(activeScenario, 30_000L) {
                it.status.contains("HTTPS đang chạy", ignoreCase = true) && it.endpoints.contains("https://")
            }
            assertTrue("Viewer did not restart after Wi-Fi returned: $recovered", recovered.endpoints.isNotBlank())
            assertEquals("Viewer endpoints did not return to the Wi-Fi address", initial.endpoints, recovered.endpoints)
        } finally {
            previousWifiEnabled?.let { wasEnabled ->
                runCatching {
                    executeShell(if (wasEnabled == 1) "svc wifi enable && echo restored" else "svc wifi disable && echo restored")
                }
            }
            scenario?.let { activeScenario ->
                runCatching {
                    activeScenario.onActivity { activity ->
                        val stopViewer = activity.findViewById<android.view.View>(R.id.btnStopViewer)
                        if (stopViewer.isEnabled) stopViewer.performClick()
                        val stopCamera = activity.findViewById<android.view.View>(R.id.btnStopCamera)
                        if (stopCamera.isEnabled) stopCamera.performClick()
                    }
                }
                runCatching { activeScenario.close() }
            }
            val prefs = context.getSharedPreferences(CameraQualityPreference.PREFERENCES, Context.MODE_PRIVATE)
            if (previousQuality == null) prefs.edit().remove(CameraQualityPreference.KEY_QUALITY).commit()
            else prefs.edit().putString(CameraQualityPreference.KEY_QUALITY, previousQuality).commit()
        }
    }

    private fun hasWifiNetwork(connectivity: ConnectivityManager): Boolean =
        connectivity.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } == true

    private fun awaitRecorderStatus(
        activity: ActivityScenario<MainActivity>,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var status = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            activity.onActivity { current ->
                status = current.findViewById<TextView>(R.id.tvRecorderStatus).text.toString()
            }
            if (predicate(status)) return status
            SystemClock.sleep(200L)
        }
        return status
    }

    private fun awaitViewerState(
        activity: ActivityScenario<MainActivity>,
        timeoutMs: Long,
        predicate: (ViewerUiState) -> Boolean,
    ): ViewerUiState {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var state = ViewerUiState("", "")
        while (SystemClock.elapsedRealtime() < deadline) {
            activity.onActivity { current ->
                state = ViewerUiState(
                    current.findViewById<TextView>(R.id.tvViewerStatus).text.toString(),
                    current.findViewById<TextView>(R.id.tvViewerEndpoints).text.toString(),
                )
            }
            if (predicate(state)) return state
            SystemClock.sleep(200L)
        }
        return state
    }

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(200L)
        }
        return predicate()
    }

    private fun executeShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    private data class ViewerUiState(val status: String, val endpoints: String)
}

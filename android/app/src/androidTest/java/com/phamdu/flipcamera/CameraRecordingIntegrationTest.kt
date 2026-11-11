package com.phamdu.flipcamera

import android.Manifest
import android.media.MediaExtractor
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.phamdu.flipcamera.data.CameraDatabase
import com.phamdu.flipcamera.data.CONTINUOUS_SEGMENT_DURATION_MS
import com.phamdu.flipcamera.data.SegmentEntity
import com.phamdu.flipcamera.data.SegmentFileStore
import com.phamdu.flipcamera.data.SegmentRecovery
import com.phamdu.flipcamera.data.SegmentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CameraRecordingIntegrationTest {
    private var previousQuality: String? = null
    private var selectedQualityLabel: String = "SD 480p"

    @Before
    fun selectRequestedProfile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences(CameraQualityPreference.PREFERENCES, android.content.Context.MODE_PRIVATE)
        previousQuality = preferences.getString(CameraQualityPreference.KEY_QUALITY, null)
        val requestedQuality = InstrumentationRegistry.getArguments().getString("recordingQuality", "SD").uppercase()
        val quality = when (requestedQuality) {
            "HD" -> androidx.camera.video.Quality.HD
            "SD" -> androidx.camera.video.Quality.SD
            else -> error("recordingQuality must be HD or SD")
        }
        selectedQualityLabel = if (quality == androidx.camera.video.Quality.HD) "HD 720p" else "SD 480p"
        CameraQualityPreference.store(context, quality)
    }

    @After
    fun restoreQualityProfile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences(CameraQualityPreference.PREFERENCES, android.content.Context.MODE_PRIVATE)
        if (previousQuality == null) preferences.edit().remove(CameraQualityPreference.KEY_QUALITY).commit()
        else preferences.edit().putString(CameraQualityPreference.KEY_QUALITY, previousQuality).commit()
    }

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test
    fun explicitConnectRecordsAndFinalizesPlayableSilentMp4() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = CameraDatabase.getInstance(context)
        runBlocking(Dispatchers.IO) { database.clearAllTables() }
        var screenWasTurnedOff = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { it.findViewById<android.view.View>(R.id.btnConnect).performClick() }
                val cameraStatus = awaitStatus(scenario, 30_000L) {
                    it.contains("sẵn sàng", ignoreCase = true) || it.contains("lỗi", ignoreCase = true)
                }
                var cameraReady = false
                var cameraMessage = ""
                scenario.onActivity {
                    cameraReady = it.findViewById<android.view.View>(R.id.btnStartRecording).isEnabled
                    cameraMessage = it.findViewById<android.widget.TextView>(R.id.tvStateMessage).text.toString()
                    assertFalse(it.findViewById<android.widget.Spinner>(R.id.spinnerCameraQuality).isEnabled)
                }
                assertTrue("Emulator camera should be ready; status=$cameraStatus; message=$cameraMessage", cameraReady)
                assertTrue("Service should apply the selected $selectedQualityLabel profile: $cameraMessage", cameraMessage.contains(selectedQualityLabel))

                scenario.onActivity { it.findViewById<android.view.View>(R.id.btnStartRecording).performClick() }
                val recordingState = awaitStatus(scenario, 30_000L) {
                    it.contains("Đang ghi hình", ignoreCase = true) || it.contains("lỗi", ignoreCase = true)
                }
                assertTrue("Recording did not start: $recordingState", recordingState.contains("Đang ghi hình", ignoreCase = true))
                val recordingDurationMs = requestedRecordingDurationMs()
                if (screenOffDuringRecording()) {
                    executeShell("input keyevent 26")
                    screenWasTurnedOff = true
                    assertTrue("Emulator display did not turn off", awaitInteractive(context, expected = false))
                    SystemClock.sleep(recordingDurationMs)
                    executeShell("input keyevent 26")
                    screenWasTurnedOff = false
                    assertTrue("Emulator display did not wake", awaitInteractive(context, expected = true))
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                } else {
                    val recordingDeadline = SystemClock.elapsedRealtime() + recordingDurationMs
                    while (SystemClock.elapsedRealtime() < recordingDeadline) {
                        SystemClock.sleep(minOf(5_000L, recordingDeadline - SystemClock.elapsedRealtime()))
                        var currentStatus = ""
                        scenario.onActivity {
                            currentStatus = it.findViewById<android.widget.TextView>(R.id.tvRecorderStatus).text.toString()
                        }
                        assertTrue(
                            "Recording stopped before ${recordingDurationMs}ms: $currentStatus",
                            currentStatus.contains("ghi hình", ignoreCase = true) ||
                                currentStatus.contains("bắt đầu", ignoreCase = true) ||
                                currentStatus.contains("hoàn tất", ignoreCase = true) ||
                                currentStatus.contains("lưu phân đoạn", ignoreCase = true),
                        )
                    }
                }
                val readyToStopStatus = awaitStatus(scenario, 15_000L) {
                    it.contains("Đang ghi hình", ignoreCase = true) || it.contains("lỗi", ignoreCase = true)
                }
                assertTrue(
                    "Recording did not remain active through the interval or reach a stable segment before stop: $readyToStopStatus",
                    readyToStopStatus.contains("Đang ghi hình", ignoreCase = true),
                )
                SystemClock.sleep(2_000L)
                val stableRecordingStatus = awaitStatus(scenario, 10_000L) {
                    it.contains("Đang ghi hình", ignoreCase = true) || it.contains("lỗi", ignoreCase = true)
                }
                assertTrue("Recorder stopped while the test was waiting to finalize: $stableRecordingStatus", stableRecordingStatus.contains("Đang ghi hình", ignoreCase = true))
                scenario.onActivity { it.findViewById<android.view.View>(R.id.btnStopRecording).performClick() }

                val sessionRows = runBlocking {
                    withTimeout(30_000L) {
                        withContext(Dispatchers.IO) {
                            val firstReady = database.segmentDao().getAllSegmentsFlow()
                                .first { rows -> rows.any { it.status == SegmentStatus.READY } }
                                .first { it.status == SegmentStatus.READY }
                            database.segmentDao().getSegmentsBySession(firstReady.sessionId)
                                .first { rows -> rows.isNotEmpty() && rows.none { it.status == SegmentStatus.RECORDING } }
                        }
                    }
                }
                val readyRows = sessionRows.filter { it.status == SegmentStatus.READY }
                assertTrue("Recording session contains no finalized segments: $sessionRows", readyRows.isNotEmpty())
                assertTrue("Recording session contains failed segments: $sessionRows", sessionRows.none { it.status == SegmentStatus.FAILED })
                val minimumSegments = (recordingDurationMs / CONTINUOUS_SEGMENT_DURATION_MS).coerceAtLeast(1L)
                assertTrue(
                    "Expected at least $minimumSegments finalized segments for ${recordingDurationMs}ms; found ${readyRows.size}",
                    readyRows.size.toLong() >= minimumSegments,
                )
                val measuredDurationMs = readyRows.sumOf { it.duration }
                assertTrue(
                    "Only ${measuredDurationMs}ms of finalized media for ${recordingDurationMs}ms requested",
                    measuredDurationMs >= (recordingDurationMs - 30_000L).coerceAtLeast(1L),
                )
                val readyRow = readyRows.first()
                assertTrue(readyRow.bytes > 0L)
                assertTrue(readyRow.duration > 0L)
                assertFalse("G1 must not record audio", readyRow.audio)
                val file = File(readyRow.path)
                assertTrue(file.isFile)
                assertEquals(readyRow.bytes, file.length())

                val dao = database.segmentDao()
                val segmentsDirectory = file.parentFile ?: error("Segment file has no parent")
                val finalCrashId = UUID.randomUUID().toString()
                val finalCrashFile = File(segmentsDirectory, "$finalCrashId.mp4")
                file.copyTo(finalCrashFile)
                runBlocking(Dispatchers.IO) {
                    dao.insert(recoveryRow(finalCrashId, readyRow.sessionId, readyRow.startUtc, File(segmentsDirectory, "$finalCrashId.pending.mp4")))
                }

                val pendingCrashId = UUID.randomUUID().toString()
                val pendingCrashFile = SegmentFileStore(context).newTempFile(pendingCrashId)
                file.copyTo(pendingCrashFile, overwrite = true)
                runBlocking(Dispatchers.IO) { dao.insert(recoveryRow(pendingCrashId, readyRow.sessionId, readyRow.startUtc, pendingCrashFile)) }

                val recovery = SegmentRecovery(context, dao)
                val recovered = runBlocking { recovery.reconcile() }
                assertEquals(2, recovered.recoveredReady)
                assertEquals(0, recovered.markedFailed)
                assertEquals(SegmentStatus.READY, runBlocking(Dispatchers.IO) { dao.getById(finalCrashId)?.status })
                assertEquals(SegmentStatus.READY, runBlocking(Dispatchers.IO) { dao.getById(pendingCrashId)?.status })
                assertTrue(File(segmentsDirectory, "$pendingCrashId.mp4").isFile)
                assertEquals(0, runBlocking { recovery.reconcile() }.recoveredReady)

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val videoTracks = (0 until extractor.trackCount).filter { index ->
                        extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                    val audioTracks = (0 until extractor.trackCount).filter { index ->
                        extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    assertTrue("Final MP4 must contain video", videoTracks.isNotEmpty())
                    assertTrue("Final MP4 must remain silent", audioTracks.isEmpty())
                    extractor.selectTrack(videoTracks.first())
                    assertTrue("Final MP4 must contain a readable sample", extractor.readSampleData(java.nio.ByteBuffer.allocate(1024 * 1024), 0) > 0)
                } finally {
                    extractor.release()
                }
            } finally {
                if (screenWasTurnedOff) {
                    runCatching {
                        executeShell("input keyevent 26")
                        awaitInteractive(context, expected = true)
                    }
                    screenWasTurnedOff = false
                }
                runCatching {
                    scenario.onActivity {
                        val stop = it.findViewById<android.view.View>(R.id.btnStopCamera)
                        if (stop.isEnabled) stop.performClick()
                    }
                    awaitStatus(scenario, 10_000L) { it.contains("camera đã tắt", ignoreCase = true) }
                }
            }
        }
    }

    private fun awaitStatus(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var status = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                status = activity.findViewById<android.widget.TextView>(R.id.tvRecorderStatus).text.toString()
            }
            if (predicate(status)) return status
            SystemClock.sleep(200L)
        }
        return status
    }

    private fun requestedRecordingDurationMs(): Long {
        val duration = InstrumentationRegistry.getArguments()
            .getString("recordingDurationMs")
            ?.toLongOrNull()
            ?: 3_000L
        require(duration in 3_000L..1_800_000L) {
            "recordingDurationMs must be between 3000 and 1800000 milliseconds"
        }
        return duration
    }

    private fun screenOffDuringRecording(): Boolean = InstrumentationRegistry.getArguments()
        .getString("screenOffDuringRecording")
        .equals("true", ignoreCase = true)

    private fun awaitInteractive(context: android.content.Context, expected: Boolean): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (powerManager.isInteractive == expected) return true
            SystemClock.sleep(100L)
        }
        return powerManager.isInteractive == expected
    }

    private fun executeShell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> input.readBytes() }
    }

    private fun recoveryRow(id: String, sessionId: String, startUtc: Long, pendingFile: File) = SegmentEntity(
        id = id,
        sessionId = sessionId,
        startUtc = startUtc,
        internalName = "pending-$id.mp4",
        path = pendingFile.absolutePath,
    )
}

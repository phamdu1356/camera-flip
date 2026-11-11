package com.phamdu.flipcamera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.util.Range
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraState
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.phamdu.flipcamera.data.CameraDatabase
import com.phamdu.flipcamera.data.MotionDetector
import com.phamdu.flipcamera.data.MotionEventEntity
import com.phamdu.flipcamera.data.MotionEventDao
import com.phamdu.flipcamera.data.MotionThumbnailStore
import com.phamdu.flipcamera.data.RetentionManager
import com.phamdu.flipcamera.data.RecordingMode
import com.phamdu.flipcamera.data.SegmentPruner
import com.phamdu.flipcamera.data.RecordingFpsMode
import com.phamdu.flipcamera.data.StoragePolicyStore
import com.phamdu.flipcamera.data.VideoCodecMode
import com.phamdu.flipcamera.data.resolveEncoderTuning
import com.phamdu.flipcamera.data.segmentDurationMs
import com.phamdu.flipcamera.data.readAllReadySegments
import com.phamdu.flipcamera.data.storageEvictionIds
import com.phamdu.flipcamera.data.FinalizedSegmentFile
import com.phamdu.flipcamera.data.GapEntity
import com.phamdu.flipcamera.data.SegmentEntity
import com.phamdu.flipcamera.data.SegmentFileStore
import com.phamdu.flipcamera.data.SegmentRecovery
import com.phamdu.flipcamera.data.WatermarkSegmentGuard
import com.phamdu.flipcamera.data.SegmentStatus
import com.phamdu.flipcamera.security.PairingApproval
import com.phamdu.flipcamera.security.PairingCode
import com.phamdu.flipcamera.security.PairingRequestSummary
import com.phamdu.flipcamera.security.ViewerApi
import com.phamdu.flipcamera.security.ViewerAppUpdateApi
import com.phamdu.flipcamera.security.ViewerAudioApi
import com.phamdu.flipcamera.security.ViewerDeviceApi
import com.phamdu.flipcamera.security.ViewerDeviceControl
import com.phamdu.flipcamera.security.ViewerLiveApi
import com.phamdu.flipcamera.security.ViewerEventApi
import com.phamdu.flipcamera.security.ViewerMediaApi
import com.phamdu.flipcamera.security.ViewerSpeakerApi
import com.phamdu.flipcamera.security.ViewerEndpointDiscovery
import com.phamdu.flipcamera.security.ViewerHttpsServer
import com.phamdu.flipcamera.security.ViewerPairingManager
import com.phamdu.flipcamera.security.PreferencesViewerPasswordStore
import com.phamdu.flipcamera.security.ViewerPassword
import com.phamdu.flipcamera.security.RoomViewerEventSource
import com.phamdu.flipcamera.security.ViewerWebAssets
import com.phamdu.flipcamera.viewer.ViewerAudioHub
import com.phamdu.flipcamera.viewer.ViewerFrameHub
import com.phamdu.flipcamera.viewer.ViewerSpeakerHub
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Khoảng FPS của camera; tách khỏi android.util.Range để test JVM không chạm stub Android. */
internal data class ViewerFpsRange(val lower: Int, val upper: Int)

private const val MIN_VIEWER_FPS = 20
/** Trần FPS khi so sánh: range trên 30 fps làm tăng tải watermark/encoder mà không cần cho live. */
private const val MAX_VIEWER_FPS = 30

/**
 * Chọn range FPS cho preview, ghi hình và AE.
 * 1) lower >= 20: ưu tiên upper lớn nhất (trần 30), rồi lower lớn hơn;
 * 2) không có: upper >= 20 lớn nhất theo cùng tiêu chí;
 * 3) vẫn không có: giữ fallbackFps cố định.
 */
internal fun selectViewerFpsRange(available: List<ViewerFpsRange>, fallbackFps: Int = 15): ViewerFpsRange {
    val byCappedUpper = compareBy<ViewerFpsRange> { minOf(it.upper, MAX_VIEWER_FPS) }.thenBy { it.lower }
    available.filter { it.lower >= MIN_VIEWER_FPS && it.upper >= it.lower }
        .maxWithOrNull(byCappedUpper)?.let { return it }
    available.filter { it.upper >= MIN_VIEWER_FPS && it.upper >= it.lower }
        .maxWithOrNull(byCappedUpper)?.let { return it }
    return ViewerFpsRange(fallbackFps, fallbackFps)
}

private const val SAVER_FPS = 15
/** Trần upper của range chế độ Tiết kiệm; range trên 20 fps không phải mục tiêu của chế độ này. */
private const val SAVER_FPS_CEILING = 20

/**
 * Chọn range FPS cho chế độ Tiết kiệm (~15 fps cho cả preview, ghi hình và AE).
 * Chỉ nhận range có upper trong 15..20; ưu tiên upper nhỏ nhất (gần 15 nhất), rồi lower lớn hơn,
 * nên [15,15] thắng [7,15]. Trả null khi camera không có range phù hợp để caller giữ chế độ SMOOTH.
 */
internal fun selectSaverFpsRange(available: List<ViewerFpsRange>): ViewerFpsRange? =
    available.filter { it.upper in SAVER_FPS..SAVER_FPS_CEILING && it.lower in 1..it.upper }
        .minWithOrNull(compareBy<ViewerFpsRange> { it.upper }.thenByDescending { it.lower })

/** Nhãn FPS hiển thị trên trạng thái, ví dụ "24-30" hoặc "30". */
internal fun viewerFpsLabel(range: ViewerFpsRange): String =
    if (range.lower == range.upper) "${range.upper}" else "${range.lower}-${range.upper}"

/**
 * Bitrate mục tiêu của encoder ghi hình (bps). Đặt thấp hơn mức cũ 2 Mbps / 1 Mbps để giảm dung lượng;
 * Samsung VQApply có thể nâng lên sàn theo pixel x fps, nên giá trị này chỉ là mục tiêu, chưa đo trên máy.
 * FPS không đổi: CameraX 1.4.1 không tách được fps encoder khỏi AE range chung của session.
 */
internal const val RECORDING_BITRATE_HD_BPS = 1_500_000
internal const val RECORDING_BITRATE_SD_BPS = 800_000

internal fun recordingBitrateFor(sd: Boolean): Int =
    if (sd) RECORDING_BITRATE_SD_BPS else RECORDING_BITRATE_HD_BPS

private fun clampToByte(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value

internal data class ViewerAdminState(
    val enabled: Boolean = false,
    val starting: Boolean = false,
    val running: Boolean = false,
    val endpoints: List<String> = emptyList(),
    val certificateFingerprint: String? = null,
    val errorMessage: String? = null,
)

internal enum class ThermalLoadState(val level: Int, val description: String) {
    NORMAL(0, "Mát"),
    LIGHT(1, "Ấm nhẹ"),
    MODERATE(2, "Ấm — hạ tải xử lý"),
    SEVERE(3, "Nóng — ngắt phân tích phụ"),
    CRITICAL(4, "Quá nóng — dừng an toàn")
}

/** CameraX, preview and segmented recording live in one camera foreground service. */
class RecordingService : LifecycleService() {
    private val localBinder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val viewerAnalysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val watermarkThread = HandlerThread("FlipWatermark").apply { start() }
    private val watermarkHandler = Handler(watermarkThread.looper)
    private var overlayEffect: OverlayEffect? = null
    @Volatile private var watermarkClock: WatermarkFrameClock? = null
    @Volatile private var watermarkHealth = WatermarkHealth()
    private val eventPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val motionCapture = MotionCaptureOrchestrator(persistEvent = ::persistMotionEvent)
    private val fileStore by lazy { SegmentFileStore(applicationContext) }
    private val dao by lazy { CameraDatabase.getInstance(applicationContext).segmentDao() }
    private val motionEventDao: MotionEventDao by lazy { CameraDatabase.getInstance(applicationContext).motionEventDao() }
    private val motionThumbnailStore by lazy { MotionThumbnailStore(applicationContext) }
    private val recovery by lazy { SegmentRecovery(applicationContext, dao) }
    private val watermarkSegmentGuard by lazy { WatermarkSegmentGuard(applicationContext) }
    private val viewerPairing by lazy {
        ViewerPairingManager(passwordStore = PreferencesViewerPasswordStore(applicationContext))
    }
    private val viewerFrameHub = ViewerFrameHub()
    private val viewerAudioHub = ViewerAudioHub(onStartCapture = ::ensureMicrophoneForegroundService) {
        cameraRequested && hasAudioPermission()
    }
    private val viewerSpeakerHub by lazy {
        ViewerSpeakerHub(
            isEnabled = StoragePolicyStore(applicationContext).loadSpeakerEnabled(),
        )
    }
    private val viewerCommands = Channel<Long>(Channel.CONFLATED)
    @Volatile private var viewerRequested = false
    @Volatile private var viewerAdminState = ViewerAdminState()
    @Volatile private var viewerGeneration = 0L
    @Volatile private var viewerRunningGeneration = -1L
    private var viewerServerWorker: Job? = null
    private var networkCallbackRegistered = false
    private val viewerNetworks = ConcurrentHashMap.newKeySet<Network>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewerNetworks += network
            requestViewerNetworkRefresh()
        }

        override fun onLost(network: Network) {
            viewerNetworks -= network
            requestViewerNetworkRefresh()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            viewerNetworks += network
            requestViewerNetworkRefresh()
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var torchCamera: androidx.camera.core.Camera? = null
    private var preview: Preview? = null
    private var viewerImageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private val cameraHealth = CameraHealthMonitor()
    private var cameraStateSource: LiveData<CameraState>? = null
    private var cameraStateObserver: Observer<CameraState>? = null
    private var lastHealth: CameraHealthSnapshot? = null
    private val healthWatchdog = object : Runnable {
        override fun run() {
            if (!cameraRequested || cameraStateObserver == null) return
            broadcastHealthIfChanged()
            mainHandler.postDelayed(this, 1_000L)
        }
    }
    private var cameraReady = false
    private var cameraRequested = false
    /** Quyết định một lần lúc kết nối camera: đã bật + đã có quyền micro. */
    private var audioActive = false
    @Volatile private var recordingRequested = false
    private var disconnectWhenFinalized = false
    private var connectGeneration = 0
    private var serviceState = STATE_IDLE
    private var message = "Camera đang tắt."
    private var foregroundStarted = false
    private var lastNotificationElapsed = 0L
    @Volatile private var sessionId: String? = null
    @Volatile private var currentSegment: SegmentRun? = null
    private var prepareJob: Job? = null
    private var previousEndElapsed: Long? = null
    private var previousEndUtc: Long? = null
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private val retentionManager by lazy { RetentionManager(applicationContext, dao) }
    private val storagePruner by lazy {
        SegmentPruner(
            segmentDao = dao,
            motionEventDao = motionEventDao,
            retentionManager = retentionManager,
            modeProvider = { StoragePolicyStore(applicationContext).load().mode },
        )
    }
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storagePolicyMutex = Mutex()
    private var storageJob: Job? = null
    @Volatile private var currentThermalState = ThermalLoadState.NORMAL
    @Volatile private var currentBatteryTemperatureC = 0.0f
    @Volatile private var currentBatteryLevel = -1
    @Volatile private var isBatteryCharging = false
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val tempC = rawTemp / 10.0f
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            currentBatteryTemperatureC = tempC
            currentBatteryLevel = level
            isBatteryCharging = plugged

            if (tempC >= 48.5f && recordingRequested) {
                Log.w("FlipCamera", "Emergency battery temp detected: $tempC°C. Safely halting recording.")
                stopRecording()
                publishState(
                    STATE_ERROR,
                    "Nhiệt độ pin quá cao ($tempC°C). Đã dừng ghi an toàn để bảo vệ pin thiết bị.",
                    active = cameraReady,
                )
            }
        }
    }
    private val viewerDeviceControl = object : ViewerDeviceControl {
        override fun batteryTemperatureC(): Float? = currentBatteryTemperatureC.takeIf { it > 0f }
        override fun torchAvailable(): Boolean = cameraReady && torchCamera?.cameraInfo?.hasFlashUnit() == true
        override fun torchOn(): Boolean =
            torchCamera?.cameraInfo?.torchState?.value == androidx.camera.core.TorchState.ON
        override fun setTorch(on: Boolean): Boolean {
            val camera = torchCamera ?: return false
            if (!cameraReady || !camera.cameraInfo.hasFlashUnit()) return false
            camera.cameraControl.enableTorch(on)
            return true
        }
    }
    private val viewerLiveApi by lazy { ViewerLiveApi(viewerPairing, viewerFrameHub) }
    private var torchHeatLocked = false
    private var lastAutoTorchOn: Boolean? = null
    private val nightTorchTick = object : Runnable {
        override fun run() {
            applyNightTorchPolicy()
            mainHandler.postDelayed(this, NIGHT_TORCH_INTERVAL_MS)
        }
    }

    /** Ban đêm bật flash, tắt khi nhiệt độ pin > 45 °C. Chỉ tác động khi trạng thái mong muốn đổi, để không đè thao tác tay. */
    private fun applyNightTorchPolicy() {
        if (!cameraReady) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val decision = com.phamdu.flipcamera.data.NightTorchPolicy.decide(
            hour, viewerDeviceControl.batteryTemperatureC(), torchHeatLocked,
        )
        torchHeatLocked = decision.heatLocked
        val previous = lastAutoTorchOn
        if (previous == decision.torchOn) return
        if (previous == null && !decision.torchOn) { lastAutoTorchOn = false; return }
        if (viewerDeviceControl.setTorch(decision.torchOn)) {
            lastAutoTorchOn = decision.torchOn
            Log.i("FlipCamera", "Torch tự động: ${if (decision.torchOn) "BẬT" else "TẮT"} (giờ=$hour, pin=${viewerDeviceControl.batteryTemperatureC()} °C)")
        }
    }
    private val frameAnalysisSchedule = FrameAnalysisSchedule()
    @Volatile private var motionResumeAtElapsed = 0L
    @Volatile private var cameraTargetRotation = Surface.ROTATION_0
    @Volatile private var analysisGeometryPending = true
    private var lastAnalysisErrorElapsed = 0L
    private val cameraOrientationListener by lazy {
        object : OrientationEventListener(applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (!cameraRequested || orientation == ORIENTATION_UNKNOWN) return
                val rotation = UseCase.snapToSurfaceRotation(orientation)
                if (rotation == cameraTargetRotation) return
                cameraTargetRotation = rotation
                // MP4 rotation is fixed for its current segment. Keep live in that same
                // coordinate system until the next segment applies the pending rotation.
                if (currentSegment != null || prepareJob != null) return
                analysisGeometryPending = true
                preview?.targetRotation = rotation
                viewerImageAnalysis?.targetRotation = rotation
                if (currentSegment == null && prepareJob == null) videoCapture?.targetRotation = rotation
                if (recordingRequested && motionSamplingReady) {
                    // Let the newly rotated frames settle before creating a motion baseline.
                    motionResumeAtElapsed = SystemClock.elapsedRealtime() + 1_000L
                    runCatching {
                        viewerAnalysisExecutor.execute {
                            if (recordingRequested && motionSamplingReady) {
                                motionCapture.resetBaseline()
                                frameAnalysisSchedule.resetMotion()
                            }
                        }
                    }.onFailure { motionSamplingReady = false }
                }
            }
        }
    }

    @Volatile private var motionSamplingReady = false
    @Volatile private var motionCaptureActive = false

    // Buffer tái sử dụng cho đường live. Chỉ encodeViewerFrame (gọi từ analyzer) dùng, nên không cần đồng bộ hóa.
    private var viewerNv21 = ByteArray(0)
    private var viewerChromaU = ByteArray(0)
    private var viewerChromaV = ByteArray(0)
    private var viewerArgb = IntArray(0)
    private var viewerBitmap: Bitmap? = null
    private val viewerJpegBuffer = ByteArrayOutputStream()

    // Thống kê live mỗi 5 giây, cũng chỉ chạm trên viewerAnalysisExecutor.
    private var liveStatsWindowStartMs = -1L
    private var liveStatsAnalysisFrames = 0
    private var liveStatsPublished = 0
    private var liveStatsJpegBytes = 0L
    private var liveStatsEncodeNanos = 0L

    private fun recordLiveAnalysisFrame(nowMs: Long) {
        if (liveStatsWindowStartMs < 0L) liveStatsWindowStartMs = nowMs
        liveStatsAnalysisFrames++
        val elapsedMs = nowMs - liveStatsWindowStartMs
        if (elapsedMs < LIVE_STATS_INTERVAL_MS) return
        if (liveStatsPublished > 0) {
            val seconds = elapsedMs / 1_000.0
            Log.i("FlipCamera", String.format(Locale.US,
                "LIVE_STATS fps=%.1f avgBytes=%d avgEncodeMs=%.1f analysisFps=%.1f",
                liveStatsPublished / seconds,
                liveStatsJpegBytes / liveStatsPublished,
                liveStatsEncodeNanos / 1_000_000.0 / liveStatsPublished,
                liveStatsAnalysisFrames / seconds))
        }
        liveStatsWindowStartMs = nowMs
        liveStatsAnalysisFrames = 0
        liveStatsPublished = 0
        liveStatsJpegBytes = 0L
        liveStatsEncodeNanos = 0L
    }

    private fun recordLivePublish(jpegBytes: Int, encodeNanos: Long) {
        liveStatsPublished++
        liveStatsJpegBytes += jpegBytes
        liveStatsEncodeNanos += encodeNanos
    }

    private fun onThermalStatusChanged(status: Int) {
        val newState = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLoadState.CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLoadState.SEVERE
            status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLoadState.MODERATE
            status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLoadState.LIGHT
            else -> ThermalLoadState.NORMAL
        }
        if (newState == currentThermalState) return
        currentThermalState = newState
        Log.i("FlipCamera", "Thermal status changed: $status -> ${newState.name} (${newState.description})")

        when (newState) {
            ThermalLoadState.CRITICAL -> {
                if (recordingRequested) {
                    Log.w("FlipCamera", "Critical thermal status ($status). Stopping recording safely to protect phone.")
                    stopRecording()
                    publishState(
                        STATE_ERROR,
                        "Thiết bị quá nhiệt (mức nhiệt $status). Đã dừng ghi an toàn để bảo vệ pin và phần cứng.",
                        active = cameraReady,
                    )
                }
            }
            ThermalLoadState.SEVERE -> {
                Log.w("FlipCamera", "Severe thermal status ($status). Shedding motion analysis to cool down SoC.")
                updateNotification(
                    getString(R.string.recording_notification_title),
                    "Cảnh báo nhiệt độ mức 3 — tạm dừng phân tích chuyển động để làm mát máy",
                )
            }
            else -> Unit
        }
    }

    private fun analyzeViewerImage(image: ImageProxy, binding: CameraBindingToken) {
        try {
            recordLiveAnalysisFrame(SystemClock.elapsedRealtime())
            val ticket = cameraHealth.frame(binding, image.imageInfo.timestamp, SystemClock.elapsedRealtimeNanos())
                ?: return
            if (analysisGeometryPending) {
                analysisGeometryPending = false
                Log.i("FlipCamera", "Analysis frame ${image.width}x${image.height}, " +
                    "crop=${image.cropRect}, rotation=${image.imageInfo.rotationDegrees}")
            }
            val hasViewers = viewerFrameHub.hasSubscribers
            val thermal = currentThermalState
            val thermalShedding = thermal == ThermalLoadState.SEVERE || thermal == ThermalLoadState.CRITICAL || currentBatteryTemperatureC >= 45.0f
            val shouldDetectMotion = recordingRequested && motionSamplingReady && motionCaptureActive && !thermalShedding
            if (!hasViewers && !shouldDetectMotion) return
            val now = SystemClock.elapsedRealtime()
            val motionInterval = if (thermal == ThermalLoadState.MODERATE) 1500L else null
            val work = frameAnalysisSchedule.plan(now, hasViewers, shouldDetectMotion && now >= motionResumeAtElapsed, motionInterval)
            if (!work.publishLive && !work.sampleMotion) return
            val motion = if (work.sampleMotion) {
                downsampleLuminance(image)?.let { luminance -> motionCapture.sample(now, luminance) }
            } else {
                null
            }

            if (work.publishLive || motion?.started == true) {
                val encodeStartNanos = System.nanoTime()
                val jpeg = encodeViewerFrame(image)
                val encodeNanos = System.nanoTime() - encodeStartNanos
                if (jpeg != null) {
                    if (work.publishLive) {
                        val utc = watermarkUtc(image.imageInfo.timestamp, 1)
                        val published = cameraHealth.publish(ticket, SystemClock.elapsedRealtimeNanos()) {
                            val previous = viewerFrameHub.latestFrame()?.sequence
                            viewerFrameHub.publishJpeg(jpeg, utc)
                            viewerFrameHub.latestFrame()?.sequence?.let { it != previous } == true
                        }
                        if (published) recordLivePublish(jpeg.size, encodeNanos)
                    }
                    if (motion?.started == true) motionCapture.setActiveThumbnail(jpeg)
                }
            }
        } catch (error: Exception) {
            // A dropped preview/motion sample must not interrupt the camera recorder.
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisErrorElapsed >= 5_000L) {
                lastAnalysisErrorElapsed = now
                Log.w("FlipCamera", "Dropped analysis frame", error)
            }
        } finally {
            image.close()
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val powerManager = getSystemService(PowerManager::class.java)
            recordingWakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FlipCamera:RecordingWakeLock",
            )?.apply {
                setReferenceCounted(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    onThermalStatusChanged(status)
                }
                thermalListener = listener
                powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
                val initialStatus = powerManager.currentThermalStatus
                onThermalStatusChanged(initialStatus)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        startStoragePolicyLoop()
        viewerServerWorker = lifecycleScope.launch(Dispatchers.IO) {
            var activeServer: ViewerHttpsServer? = null
            var activeHosts = emptySet<String>()
            var activeGeneration = -1L
            try {
                for (ignored in viewerCommands) {
                    while (viewerCommands.tryReceive().isSuccess) Unit
                    val generation = viewerGeneration
                    if (!viewerRequested) {
                        activeServer?.close()
                        activeServer = null
                        activeHosts = emptySet()
                        activeGeneration = -1L
                        withContext(Dispatchers.Main) {
                            if (!viewerRequested && generation == viewerGeneration) {
                                updateViewerAdminState(ViewerAdminState(), generation)
                            } else {
                                viewerCommands.trySend(viewerGeneration)
                            }
                        }
                        continue
                    }

                    val endpoints = ViewerEndpointDiscovery.snapshot(applicationContext, viewerNetworks.toList())
                    if (endpoints.lanAddresses.isEmpty()) {
                        if (activeServer != null) {
                            withContext(Dispatchers.Main) {
                                if (viewerRequested && generation == viewerGeneration) {
                                    updateViewerAdminState(ViewerAdminState(enabled = true, starting = true), generation)
                                }
                            }
                            viewerPairing.reset()
                        }
                        activeServer?.close()
                        activeServer = null
                        activeHosts = emptySet()
                        activeGeneration = -1L
                        withContext(Dispatchers.Main) {
                            if (viewerRequested && generation == viewerGeneration) {
                                updateViewerAdminState(
                                    ViewerAdminState(
                                        enabled = true,
                                        errorMessage = getString(R.string.viewer_no_lan_address),
                                    ),
                                    generation,
                                )
                            } else if (viewerRequested) {
                                viewerCommands.trySend(viewerGeneration)
                            } else {
                                updateViewerAdminState(ViewerAdminState(), generation)
                            }
                        }
                        continue
                    }
                    if (activeServer != null && activeGeneration == generation &&
                        activeHosts == endpoints.advertisedHosts
                    ) continue

                    withContext(Dispatchers.Main) {
                        if (viewerRequested && generation == viewerGeneration) {
                            updateViewerAdminState(ViewerAdminState(enabled = true, starting = true), generation)
                        }
                    }
                    if (activeServer != null && activeHosts != endpoints.advertisedHosts) {
                        // Host-only cookies belong to the old origin. Revoke them so they
                        // cannot occupy the viewer limit after Wi-Fi/Ethernet changes.
                        viewerPairing.reset()
                    }
                    activeServer?.close()
                    activeServer = null
                    activeHosts = emptySet()
                    activeGeneration = -1L
                    var candidate: ViewerHttpsServer? = null
                    try {
                        candidate = ViewerHttpsServer.forSnapshot(applicationContext, VIEWER_HTTPS_PORT, endpoints) {
                            ViewerApi(viewerPairing).install(this)
                            ViewerMediaApi(viewerPairing, dao, fileStore).install(this)
                            viewerLiveApi.install(this)
                            ViewerAudioApi(viewerPairing, viewerAudioHub).install(this)
                            ViewerSpeakerApi(viewerPairing, viewerSpeakerHub).install(this)
                            ViewerAppUpdateApi(viewerPairing, applicationContext).install(this)
                            ViewerDeviceApi(viewerPairing, viewerDeviceControl, viewerLiveApi.stats).install(this)
                            ViewerEventApi(
                                viewerPairing,
                                RoomViewerEventSource(motionEventDao, dao, motionThumbnailStore),
                            ).install(this)
                            ViewerWebAssets(applicationContext).install(this)
                        }
                        val port = candidate.start()
                        if (!viewerRequested || generation != viewerGeneration) {
                            candidate.close()
                            viewerCommands.trySend(viewerGeneration)
                            continue
                        }
                        activeHosts = candidate.advertisedHosts
                        activeServer = candidate
                        activeGeneration = generation
                        val fingerprint = candidate.certificateFingerprint
                        val urls = endpoints.lanAddresses.sorted().map { address ->
                            val host = if (address.contains(':')) "[$address]" else address
                            "https://$host:$port/"
                        }
                        withContext(Dispatchers.Main) {
                            if (viewerRequested && generation == viewerGeneration) {
                                updateViewerAdminState(
                                    ViewerAdminState(
                                        enabled = true,
                                        running = true,
                                        endpoints = urls,
                                        certificateFingerprint = fingerprint,
                                    ),
                                    generation,
                                )
                            } else {
                                viewerCommands.trySend(viewerGeneration)
                            }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        candidate?.close()
                        throw cancelled
                    } catch (error: Exception) {
                        candidate?.close()
                        withContext(Dispatchers.Main) {
                            if (viewerRequested && generation == viewerGeneration) {
                                updateViewerAdminState(
                                    ViewerAdminState(
                                        enabled = true,
                                        errorMessage = error.localizedMessage ?: getString(R.string.viewer_start_failed),
                                    ),
                                    generation,
                                )
                            } else if (viewerRequested) {
                                viewerCommands.trySend(viewerGeneration)
                            } else {
                                updateViewerAdminState(ViewerAdminState(), generation)
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { activeServer?.close() }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return localBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> connectCamera(startId)
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_STOP -> disconnectCamera()
            else -> if (!cameraRequested) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun getState(): String = serviceState

    fun getMessage(): String = message

    internal fun getCameraHealth(): CameraHealthSnapshot =
        cameraHealth.snapshot(SystemClock.elapsedRealtimeNanos(), viewerFrameHub.hasSubscribers)

    private fun broadcastHealthIfChanged() {
        val snapshot = getCameraHealth()
        if (snapshot == lastHealth) return
        lastHealth = snapshot
        // Preserve recorder state/intent, rotation, segment publication and the Stop action.
        publishState(serviceState, message)
    }

    private fun detachCameraHealth() {
        cameraHealth.detach()
        viewerFrameHub.clear()
        mainHandler.removeCallbacks(healthWatchdog)
        cameraStateObserver?.let { cameraStateSource?.removeObserver(it) }
        cameraStateSource = null
        cameraStateObserver = null
    }

    private fun observeCameraHealth(source: LiveData<CameraState>, binding: CameraBindingToken, analysis: Boolean) {
        cameraHealth.setAnalysis(binding, analysis)
        val observer = Observer<CameraState> { state ->
            if (!cameraRequested || binding.generation != connectGeneration || !cameraHealth.current(binding)) return@Observer
            val error = state.error
            // Error takes precedence even if CameraX reports type OPEN.
            val phase = when {
                error != null -> if (error.type == CameraState.ErrorType.RECOVERABLE)
                    CameraHealthPhase.REOPENING else CameraHealthPhase.ERROR
                state.type == CameraState.Type.OPEN -> CameraHealthPhase.OPEN
                state.type == CameraState.Type.OPENING || state.type == CameraState.Type.PENDING_OPEN -> CameraHealthPhase.OPENING
                else -> CameraHealthPhase.CLOSED
            }
            if (cameraHealth.update(binding, phase, error?.code, SystemClock.elapsedRealtimeNanos())) {
                if (phase != CameraHealthPhase.OPEN) viewerFrameHub.clear()
                broadcastHealthIfChanged()
            }
        }
        cameraStateSource = source
        cameraStateObserver = observer
        source.observe(this, observer)
        mainHandler.removeCallbacks(healthWatchdog)
        mainHandler.post(healthWatchdog)
    }

    internal fun getViewerAdminState(): ViewerAdminState = viewerAdminState

    internal fun viewerFrameHub(): ViewerFrameHub = viewerFrameHub

    internal fun startViewerServer(): Boolean {
        if (!cameraRequested) return false
        if (viewerRequested) return true
        if (!networkCallbackRegistered) {
            viewerGeneration += 1L
            updateViewerAdminState(
                ViewerAdminState(errorMessage = getString(R.string.viewer_network_monitor_failed)),
                viewerGeneration,
            )
            return false
        }
        viewerGeneration += 1L
        val generation = viewerGeneration
        viewerRequested = true
        viewerRunningGeneration = -1L
        updateViewerAdminState(ViewerAdminState(enabled = true, starting = true), generation)
        viewerCommands.trySend(generation)
        return true
    }

    internal fun stopViewerServer() {
        viewerGeneration += 1L
        val generation = viewerGeneration
        viewerRequested = false
        viewerRunningGeneration = -1L
        viewerPairing.reset()
        updateViewerAdminState(ViewerAdminState(), generation)
        viewerCommands.trySend(generation)
    }

    internal fun createViewerPairingCode(): PairingCode? =
        if (isCurrentViewerRunning()) viewerPairing.createPairingCode() else null

    internal fun hasViewerPassword(): Boolean = viewerPairing.hasPassword()

    internal fun setViewerPassword(password: ViewerPassword?) {
        viewerPairing.setPassword(password)
    }

    internal fun setAudioRecordingEnabled(enabled: Boolean) {
        audioActive = enabled && hasAudioPermission()
    }

    internal fun setSpeakerPlaybackEnabled(enabled: Boolean) {
        viewerSpeakerHub.isEnabled = enabled
        if (!enabled) viewerSpeakerHub.stopPlayback()
    }

    internal fun isSpeakerPlaybackEnabled(): Boolean = viewerSpeakerHub.isEnabled

    internal fun pendingViewerRequests(): List<PairingRequestSummary> =
        if (isCurrentViewerRunning()) viewerPairing.pendingRequests() else emptyList()

    internal fun approveViewerRequest(requestId: String): PairingApproval =
        if (isCurrentViewerRunning()) viewerPairing.approve(requestId) else PairingApproval.NOT_FOUND

    internal fun rejectViewerRequest(requestId: String): PairingApproval =
        if (isCurrentViewerRunning()) viewerPairing.reject(requestId) else PairingApproval.NOT_FOUND

    private fun isCurrentViewerRunning(): Boolean =
        viewerRequested && viewerAdminState.running && viewerRunningGeneration == viewerGeneration

    private fun updateViewerAdminState(state: ViewerAdminState, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != viewerGeneration) return
        if (state.running) {
            viewerRunningGeneration = expectedGeneration ?: viewerGeneration
        } else if (!state.enabled || state.starting || state.errorMessage != null) {
            viewerRunningGeneration = -1L
        }
        viewerAdminState = state
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_CAMERA_HEALTH, getCameraHealth().description())
                .putExtra(EXTRA_CAMERA_CAN_START, getCameraHealth().canStart)
                .putExtra(EXTRA_ACTIVE, cameraRequested)
                .putExtra(EXTRA_STATE, serviceState)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_VIEWER_ENABLED, state.enabled)
                .putExtra(EXTRA_VIEWER_STARTING, state.starting)
                .putExtra(EXTRA_VIEWER_RUNNING, state.running)
                .putExtra(EXTRA_VIEWER_ENDPOINTS, state.endpoints.joinToString("\n"))
                .putExtra(EXTRA_VIEWER_FINGERPRINT, state.certificateFingerprint)
                .putExtra(EXTRA_VIEWER_ERROR, state.errorMessage),
        )
    }

    private fun requestViewerNetworkRefresh() {
        if (viewerRequested) viewerCommands.trySend(viewerGeneration)
    }

    private fun registerNetworkCallback(): Boolean {
        if (networkCallbackRegistered) return true
        viewerNetworks.clear()
        return try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                // Tailscale is a VPN network; discovery keeps only its 100.64.0.0/10 address.
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
            true
        } catch (_: Exception) {
            viewerNetworks.clear()
            networkCallbackRegistered = false
            false
        }
    }

    private fun unregisterNetworkCallback() {
        if (networkCallbackRegistered) {
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            }
        }
        viewerNetworks.clear()
        networkCallbackRegistered = false
    }

    /** Called on the main thread while the Activity is bound; null detaches only the old surface. */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        preview?.setSurfaceProvider(provider)
    }

    fun startRecording() {
        if (recordingRequested || currentSegment != null) return
        if (!getCameraHealth().canStart) {
            broadcastHealthIfChanged()
            return
        }
        watermarkHealth.failure?.let {
            publishState(STATE_ERROR, "Watermark lỗi: $it Kết nối lại camera trước khi ghi.", active = cameraRequested)
            return
        }
        if (!cameraReady || videoCapture == null) {
            publishState(STATE_ERROR, "Camera chưa sẵn sàng. Chờ preview rồi thử lại.", active = true)
            return
        }
        if (recordingRequested || currentSegment != null) return
        beginMotionSampling()
        recordingRequested = true
        disconnectWhenFinalized = false
        runCatching { recordingWakeLock?.acquire(RECORDING_WAKELOCK_TIMEOUT_MS) }
        lifecycleScope.launch(Dispatchers.IO) { runCatching { retentionManager.retryPendingCleanup() } }
        sessionId = UUID.randomUUID().toString()
        previousEndElapsed = null
        previousEndUtc = null
        startNextSegment()
    }

    fun stopRecording() {
        if (!recordingRequested && currentSegment == null && prepareJob == null) return
        recordingRequested = false
        if (recordingWakeLock?.isHeld == true) runCatching { recordingWakeLock?.release() }
        finishMotionSampling()
        segmentHandler.removeCallbacks(rotateSegment)
        publishState(STATE_STOPPING, "Đang hoàn tất MP4 trước khi lưu…", active = true)
        val active = currentSegment
        if (active?.recording != null) {
            active.recording?.stop()
        } else if (active == null && prepareJob == null) {
            onRecordingStopped()
        }
    }

    private fun onRecordingStopped() {
        if (recordingWakeLock?.isHeld == true) runCatching { recordingWakeLock?.release() }
        if (watermarkHealth.failure != null) {
            publishWatermarkError()
            return
        }
        publishState(
            if (cameraReady) STATE_PREVIEW_READY else STATE_IDLE,
            if (cameraReady) "Camera sẵn sàng · chưa ghi video." else "Camera đã dừng.",
            active = cameraReady,
        )
    }

    private fun publishWatermarkError() {
        publishState(STATE_ERROR, "Watermark lỗi: ${watermarkHealth.failure}. Đã dừng ghi; tệp được giữ để hoàn tất/khôi phục. Kết nối lại camera.", active = cameraRequested)
    }

    private fun failWatermark(health: WatermarkHealth, generation: Int, error: Throwable,
        affectedSegment: WatermarkSegmentPublication? = currentSegment?.watermark) {
        if (health !== watermarkHealth || generation != connectGeneration || !cameraRequested) return
        affectedSegment?.takeIf { it.generation == generation }?.invalidate()
        if (!health.fail(error.localizedMessage ?: "Không thể đóng dấu khung hình.")) return
        mainHandler.post {
            if (health !== watermarkHealth || generation != connectGeneration || !cameraRequested) return@post
            cameraReady = false
            recordingRequested = false
            finishMotionSampling()
            segmentHandler.removeCallbacks(rotateSegment)
            viewerFrameHub.clear()
            // Also reject a start that raced the callback before currentSegment was assigned.
            currentSegment?.watermark?.invalidate()
            // Stop once and let CameraX Finalize publish a valid prefix or preserve the file.
            runCatching { currentSegment?.takeUnless { it.finalizing }?.recording?.stop() }
                .onFailure {
                    Log.w("FlipCamera", "Could not stop recording after watermark failure", it)
                    cameraProvider?.unbindAll()
                }
            publishWatermarkError()
        }
    }

    private fun watermarkUtc(timestamp: Long, stream: Int, health: WatermarkHealth = watermarkHealth,
        generation: Int = connectGeneration, clock: WatermarkFrameClock? = watermarkClock): Long {
        check(health === watermarkHealth && generation == connectGeneration && cameraRequested) { "Khung hình từ phiên camera cũ." }
        try {
            check(health.failure == null) { "Watermark đã lỗi." }
            return checkNotNull(clock) { "Clock watermark chưa sẵn sàng." }.utcMs(timestamp, stream,
                SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
        } catch (error: Exception) {
            failWatermark(health, generation, error)
            throw error
        }
    }

    private fun connectCamera(startId: Int) {
        if (!hasCameraPermission()) {
            publishState(STATE_ERROR, getString(R.string.status_camera_permission_missing), active = false)
            stopSelf(startId)
            return
        }
        if (cameraRequested) return
        cameraRequested = true
        disconnectWhenFinalized = false
        try {
            createNotificationChannel()
            val notification = buildNotification(
                getString(R.string.recording_notification_title),
                "Camera đang hoạt động · chưa ghi video.",
            )
            val hasMic = hasAudioPermission()
            audioActive = StoragePolicyStore(this).loadAudioEnabled() && hasMic
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    if (hasMic) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    },
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            if (!registerNetworkCallback()) {
                updateViewerAdminState(
                    ViewerAdminState(errorMessage = getString(R.string.viewer_network_monitor_failed)),
                )
            }
        } catch (error: Exception) {
            cameraRequested = false
            publishState(STATE_ERROR, "Android chặn dịch vụ camera: ${error.localizedMessage ?: "không rõ lỗi"}", active = false)
            stopSelf(startId)
            return
        }

        publishState(STATE_CONNECTING, "Đang kiểm tra video chưa hoàn tất rồi khởi tạo camera.", active = true)
        val generation = ++connectGeneration
        lifecycleScope.launch {
            try {
                val report = recovery.reconcile()
                withContext(Dispatchers.IO) {
                    RetentionManager(applicationContext, dao).retryPendingCleanup()
                }
                reconcileMotionThumbnails()
                if (report.recoveredReady > 0 || report.markedFailed > 0) {
                    publishState(
                        STATE_CONNECTING,
                        "Đã khôi phục ${report.recoveredReady} video; đánh dấu lỗi ${report.markedFailed} phân đoạn. Đang mở camera…",
                        active = true,
                    )
                }
            } catch (error: Exception) {
                if (!cameraRequested || generation != connectGeneration) return@launch
                cameraRequested = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                publishState(
                    STATE_ERROR,
                    "Không kiểm tra được video cũ nên chưa mở camera: ${error.localizedMessage ?: "lỗi lưu trữ"}",
                    active = false,
                )
                stopSelf(startId)
                return@launch
            }
            if (!cameraRequested || generation != connectGeneration) return@launch
            bindCamera(generation)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera(generation: Int) {
        if (!cameraRequested || generation != connectGeneration) return
        if (cameraOrientationListener.canDetectOrientation()) cameraOrientationListener.enable()
        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener({
            if (!cameraRequested || generation != connectGeneration) return@addListener
            detachCameraHealth()
            var binding = cameraHealth.bind(generation)
            var bindingAnalysis: ImageAnalysis? = null
            try {
                val provider = providerFuture.get()
                // Flip3 rear ultrawide is exposed separately. Never silently use 1x.
                val ultrawideSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .addCameraFilter { cameras ->
                        cameras.filter { Camera2CameraInfo.from(it).cameraId == "2" }
                    }
                    .build()
                check(provider.hasCamera(ultrawideSelector)) {
                    "Không truy cập được camera sau siêu rộng 0.5× (ID 2)."
                }
                val health = WatermarkHealth()
                watermarkHealth = health
                val selectedInfo = ultrawideSelector.filter(provider.availableCameraInfos).single()
                val realtime = Camera2CameraInfo.from(selectedInfo).getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                ) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                val frameClock = WatermarkFrameClock(realtime, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
                watermarkClock = frameClock
                cameraProvider = provider
                val preferredQuality = CameraQualityPreference.read(applicationContext)
                val fpsMode = StoragePolicyStore(applicationContext).loadRecordingFpsMode()
                val targetBitrate = recordingBitrateFor(preferredQuality == Quality.SD)
                // Cấu hình encoder đọc một lần lúc bind; thay đổi trong lúc ghi chỉ áp dụng lần kết nối sau.
                val encoderStore = StoragePolicyStore(applicationContext)
                val codecMode = encoderStore.loadVideoCodecMode()
                val encoderPlan = resolveEncoderTuning(
                    codec = codecMode,
                    tuning = encoderStore.loadEncoderTuning(),
                    hasHevcEncoder = codecMode == VideoCodecMode.HEVC && EncoderTuning.hasHevcEncoder(),
                )
                val recorderBuilder = Recorder.Builder()
                    .setTargetVideoEncodingBitRate(targetBitrate)
                    .setQualitySelector(
                        QualitySelector.from(
                            preferredQuality,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        ),
                    )
                if (!encoderPlan.isDefault) EncoderTuning.install(recorderBuilder, encoderPlan)
                EncoderTuning.installAudioTuning(recorderBuilder)
                val recorder = recorderBuilder.build()
                // Chọn FPS theo range camera thật (ID 2) thay vì khóa cứng 15 fps.
                val availableFpsRanges = (Camera2CameraInfo.from(selectedInfo).getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                ) ?: emptyArray<Range<Int>>()).map { ViewerFpsRange(it.lower, it.upper) }
                // Chế độ Tiết kiệm chỉ đổi range khi camera có range ~15 fps; nếu không thì giữ SMOOTH.
                val viewerFps = (if (fpsMode == RecordingFpsMode.SAVER) selectSaverFpsRange(availableFpsRanges) else null)
                    ?: selectViewerFpsRange(availableFpsRanges, RECORDING_FPS)
                val recordingRange = Range(viewerFps.lower, viewerFps.upper)
                val capture = VideoCapture.Builder(recorder)
                    .setTargetFrameRate(recordingRange)
                    .setTargetRotation(cameraTargetRotation)
                    .build()
                val previewUseCase = Preview.Builder()
                    .setTargetFrameRate(recordingRange)
                    .setTargetRotation(cameraTargetRotation)
                    .build().apply {
                    setSurfaceProvider(previewSurfaceProvider)
                }
                provider.unbindAll()
                analysisGeometryPending = true
                val preferredAnalysis = newViewerImageAnalysis(VIEWER_FRAME_WIDTH, VIEWER_FRAME_HEIGHT, binding)
                bindingAnalysis = preferredAnalysis
                var analysis: ImageAnalysis? = preferredAnalysis
                val effect = OverlayEffect(
                        CameraEffect.VIDEO_CAPTURE,
                        0,
                        watermarkHandler,
                    ) { error ->
                        failWatermark(health, generation, error)
                    }.apply {
                        setOnDrawListener { frame ->
                            val affectedSegment = currentSegment?.watermark
                            try {
                                check(health.failure == null) { "Watermark đã lỗi." }
                                val frameUtcMs = watermarkUtc(frame.timestampNanos, 0, health, generation, frameClock)
                                val text = WatermarkOverlay.formatText(frameUtcMs)
                                val crop = frame.cropRect
                                check(crop.left >= 0 && crop.top >= 0 && crop.right <= frame.size.width && crop.bottom <= frame.size.height)
                                val placement = WatermarkTransform(crop.left, crop.top, crop.right, crop.bottom,
                                    frame.rotationDegrees, frame.isMirroring)
                                val canvas = frame.overlayCanvas
                                val saved = canvas.save()
                                try {
                                    canvas.setMatrix(null)
                                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                    canvas.clipRect(crop)
                                    canvas.concat(Matrix().apply { setValues(placement.matrixValues) })
                                    WatermarkOverlay.draw(canvas, placement.displayWidth, placement.displayHeight, text)
                                } finally {
                                    canvas.restoreToCount(saved)
                                }
                                true
                            } catch (drawError: Exception) {
                                failWatermark(health, generation, drawError, affectedSegment)
                                false
                            }
                        }
                    }
                overlayEffect?.clearOnDrawListener()
                overlayEffect?.close()
                overlayEffect = effect
                val boundCamera = try {
                    provider.bindToLifecycle(this, ultrawideSelector, buildUseCaseGroup(previewUseCase, capture, preferredAnalysis, effect))
                } catch (unsupportedAtPreferredSize: IllegalArgumentException) {
                    preferredAnalysis.clearAnalyzer()
                    provider.unbindAll()
                    binding = cameraHealth.bind(generation)
                    val fallbackAnalysis = newViewerImageAnalysis(VIEWER_FALLBACK_WIDTH, VIEWER_FALLBACK_HEIGHT, binding)
                    bindingAnalysis = fallbackAnalysis
                    analysis = fallbackAnalysis
                    try {
                        provider.bindToLifecycle(this, ultrawideSelector, buildUseCaseGroup(previewUseCase, capture, fallbackAnalysis, effect))
                    } catch (unsupportedAtFallbackSize: IllegalArgumentException) {
                        fallbackAnalysis.clearAnalyzer()
                        provider.unbindAll()
                        analysis = null
                        binding = cameraHealth.bind(generation)
                        provider.bindToLifecycle(this, ultrawideSelector, buildUseCaseGroup(previewUseCase, capture, null, effect))
                    }
                }
                check(Camera2CameraInfo.from(boundCamera.cameraInfo).getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                ) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) { "Camera đã bind không có timestamp REALTIME." }
                check(health.failure == null) { "Watermark không khởi động được." }
                // Some Samsung sessions keep the template's 30fps despite the use-case
                // targets. Apply a supported AE range to the camera control so it also
                // remains in force when the Activity's preview surface is detached.
                val supportedRanges = Camera2CameraInfo.from(boundCamera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                if (supportedRanges?.contains(recordingRange) == true) {
                    val fpsRequest = Camera2CameraControl.from(boundCamera.cameraControl)
                        .setCaptureRequestOptions(
                            CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recordingRange)
                                .build(),
                        )
                    fpsRequest.addListener({
                        if (cameraRequested && generation == connectGeneration) {
                            runCatching { fpsRequest.get() }.onFailure {
                                Log.w("FlipCamera", "Camera did not apply the requested recording frame rate", it)
                            }
                        }
                    }, ContextCompat.getMainExecutor(this))
                }
                cameraProvider = provider
                preview = previewUseCase
                viewerImageAnalysis = analysis
                videoCapture = capture
                previewUseCase.targetRotation = cameraTargetRotation
                analysis?.targetRotation = cameraTargetRotation
                capture.targetRotation = cameraTargetRotation
                cameraReady = true
                torchCamera = boundCamera
                lastAutoTorchOn = null
                mainHandler.removeCallbacks(nightTorchTick)
                mainHandler.post(nightTorchTick)
                observeCameraHealth(boundCamera.cameraInfo.cameraState, binding, analysis != null)
                val qualityLabel = if (preferredQuality == Quality.SD) "SD 480p" else "HD 720p"
                val readinessMessage = if (analysis == null) {
                    getString(R.string.camera_ready_no_viewer)
                } else {
                    "Preview sẵn sàng."
                }
                publishState(
                    STATE_PREVIEW_READY,
                    "$readinessMessage Góc siêu rộng 0.5× · ưu tiên $qualityLabel · mục tiêu ${viewerFpsLabel(viewerFps)} fps / 2 Mbps (thực tế tùy thiết bị) · ${if (audioActive) "có thu âm" else "không thu âm"}.",
                    active = true,
                )
            } catch (error: Exception) {
                detachCameraHealth()
                cameraReady = false
                watermarkHealth.fail(error.localizedMessage ?: "Không khởi tạo được watermark/camera.")
                bindingAnalysis?.clearAnalyzer()
                viewerImageAnalysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
                overlayEffect?.close()
                overlayEffect = null
                watermarkClock = null
                publishState(STATE_ERROR, "Không mở được camera: ${error.localizedMessage ?: "kiểm tra quyền hoặc thiết bị"}", active = true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun newViewerImageAnalysis(width: Int, height: Int, binding: CameraBindingToken): ImageAnalysis =
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO),
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER),
                    )
                    // Resolution candidates use sensor coordinates, before output rotation.
                    // Enforce a ceiling: a target size alone may select a much larger stream.
                    .setResolutionFilter { sizes, _ ->
                        sizes.filter { size ->
                            maxOf(size.width, size.height) <= maxOf(width, height) &&
                                minOf(size.width, size.height) <= minOf(width, height)
                        }
                    }
                    .build(),
            )
            .setTargetRotation(cameraTargetRotation)
            .setOutputImageRotationEnabled(true)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(viewerAnalysisExecutor) { image -> analyzeViewerImage(image, binding) } }

    private fun buildUseCaseGroup(
        preview: Preview,
        capture: VideoCapture<Recorder>,
        analysis: ImageAnalysis?,
        effect: CameraEffect,
    ): UseCaseGroup = UseCaseGroup.Builder()
        .addUseCase(preview)
        .addUseCase(capture)
        .apply {
            if (analysis != null) addUseCase(analysis)
            addEffect(effect)
        }
        .build()

    /** Begin bounded on-device motion sampling on the same single-thread analyzer executor. */
    private fun beginMotionSampling() {
        motionSamplingReady = false
        motionCaptureActive = false
        runCatching {
            viewerAnalysisExecutor.execute {
                motionCapture.prepare()
                frameAnalysisSchedule.resetMotion()
                motionSamplingReady = true
            }
        }.onFailure {
            motionSamplingReady = false
        }
    }

    /** Stop accepting samples, then close and persist any confirmed interval on the analyzer thread. */
    private fun finishMotionSampling() {
        motionCaptureActive = false
        motionSamplingReady = false
        runCatching {
            viewerAnalysisExecutor.execute {
                motionCapture.finish()
            }
        }
    }

    /**
     * Periodic storage-policy pass on IO, independent of the recorder thread. Every failure is
     * logged and the loop continues; the only exit is cancellation from onDestroy.
     */
    private fun startStoragePolicyLoop() {
        storageJob?.cancel()
        storageJob = storageScope.launch {
            while (isActive) {
                delay(STORAGE_POLICY_INTERVAL_MS)
                try {
                    enforceStoragePolicyOnce()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("FlipCamera", "Storage policy pass failed; will retry", error)
                }
            }
        }
    }

    private suspend fun enforceStoragePolicyOnce() {
        if (!storagePolicyMutex.tryLock()) {
            Log.d("FlipCamera", "Storage policy pass skipped: already running")
            return
        }
        try {
            val config = StoragePolicyStore(applicationContext).load()
            val nowUtc = System.currentTimeMillis()
            if (config.mode == RecordingMode.MOTION_ONLY) {
                // Passing the in-progress interval start keeps a segment that overlaps ongoing motion.
                storagePruner.pruneBacklog(nowUtc, ongoingMotionStartUtc = motionCapture.activeIntervalStartUtc)
            }
            // With no age or size limit the policy selects nothing, so the default never deletes.
            if (config.maxDays <= 0 && config.maxBytes <= 0L) return
            val rows = dao.readAllReadySegments()
            val ids = storageEvictionIds(rows, config, nowUtc, excludeIds = setOfNotNull(currentSegment?.id))
            if (ids.isNotEmpty()) {
                Log.i("FlipCamera", "Storage policy evicting ${ids.size} segments (total ready=${rows.size})")
                storagePruner.evictSegments(ids)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("FlipCamera", "Lỗi thực thi Storage Policy (cửa sổ trượt/dung lượng 40GB): ${error.message}", error)
            throw error
        } finally {
            storagePolicyMutex.unlock()
        }
    }

    internal fun triggerStoragePolicyCheck() {
        storageScope.launch {
            try {
                enforceStoragePolicyOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e("FlipCamera", "Storage policy trigger failed: ${error.message}", error)
            }
        }
    }

    private fun persistMotionEvent(event: MotionEventEntity, thumbnail: ByteArray?) {
        eventPersistenceScope.launch {
            runCatching {
                val eventDao = motionEventDao
                if (!eventDao.insert(event)) return@runCatching
                if (thumbnail != null) {
                    val storedName = runCatching { motionThumbnailStore.write(event.id, thumbnail) }.getOrNull()
                    if (storedName != null && eventDao.attachThumbnail(event.id, storedName, event.revision) != 1) {
                        runCatching { motionThumbnailStore.delete(event.id) }
                    }
                }
            }
        }
    }

    /** Clear interrupted temp JPEGs and unreferenced files left by a crash between file/Room writes. */
    private suspend fun reconcileMotionThumbnails() = withContext(Dispatchers.IO) {
        val referencedNames = mutableSetOf<String>()
        var afterId: String? = null
        while (true) {
            val references = motionEventDao.getThumbnailReferences(afterId, limit = 500)
            if (references.isEmpty()) break
            references.forEach { reference ->
                if (reference.thumbnailName == "${reference.id}.jpg") {
                    referencedNames += reference.thumbnailName
                }
            }
            afterId = references.last().id
        }
        motionThumbnailStore.cleanupOrphans(referencedNames)
    }

    /** Convert arbitrary CameraX Y-plane strides/crop to the detector's fixed 80x45 input. */
    private fun downsampleLuminance(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val plane = image.planes.firstOrNull() ?: return null
        val crop = image.cropRect
        if (crop.width() < 2 || crop.height() < 2) return null
        val buffer = plane.buffer.duplicate()
        val base = buffer.position()
        val cropWidth = crop.width()
        val cropHeight = crop.height()
        val targetWidth = MotionDetector.DEFAULT_WIDTH
        val targetHeight = MotionDetector.DEFAULT_HEIGHT
        val output = ByteArray(targetWidth * targetHeight)

        // Precompute column mappings for the crop width to eliminate 3,600 inner-loop math operations per frame
        val columnMap = IntArray(targetWidth)
        for (x in 0 until targetWidth) {
            val localX = (((2L * x + 1L) * cropWidth) / (2L * targetWidth)).toInt().coerceIn(0, cropWidth - 1)
            columnMap[x] = (crop.left + localX) * plane.pixelStride
        }

        var target = 0
        val bufferLimit = buffer.limit()
        for (y in 0 until targetHeight) {
            val localY = (((2L * y + 1L) * cropHeight) / (2L * targetHeight)).toInt().coerceIn(0, cropHeight - 1)
            val rowOffset = base + (crop.top + localY) * plane.rowStride
            for (x in 0 until targetWidth) {
                val index = rowOffset + columnMap[x]
                if (index !in 0 until bufferLimit) return null
                output[target++] = buffer.get(index)
            }
        }
        return output
    }

    private fun startNextSegment() {
        if (!recordingRequested || !cameraReady || currentSegment != null || prepareJob != null) return
        val segmentId = UUID.randomUUID().toString()
        val activeSessionId = sessionId ?: return
        val initialUtc = System.currentTimeMillis()
        val initialElapsed = SystemClock.elapsedRealtime()
        // These are provisional values only; READY rows replace them with measured MP4 dimensions.
        val width = 1280
        val height = 720
        prepareJob = lifecycleScope.launch {
            var temporaryFile: File? = null
            try {
                var freeStorage = withContext(Dispatchers.IO) { fileStore.usableSpaceBytes() }
                if (freeStorage < AUTO_TRIM_TRIGGER_BYTES) {
                    val trimmed = withContext(Dispatchers.IO) { retentionManager.trimOldestUnprotected(AUTO_TRIM_TRIGGER_BYTES) }
                    if (trimmed > 0) {
                        freeStorage = withContext(Dispatchers.IO) { fileStore.usableSpaceBytes() }
                        Log.i("FlipCamera", "Auto-trimmed $trimmed old segments, free space now ${freeStorage / 1024 / 1024} MB")
                    }
                }
                if (freeStorage < MIN_RECORDING_STORAGE_BYTES) {
                    recordingRequested = false
                    finishMotionSampling()
                    publishState(
                        STATE_ERROR,
                        "Bộ nhớ trong sắp đầy (còn dưới 500 MB). Đã dừng ghi an toàn để bảo vệ thiết bị. Vui lòng dọn video cũ.",
                        active = cameraReady,
                    )
                    return@launch
                }
                val createdFile = withContext(Dispatchers.IO) {
                    fileStore.newTempFile(segmentId)
                }
                temporaryFile = createdFile
                val row = SegmentEntity(
                    id = segmentId,
                    sessionId = activeSessionId,
                    startUtc = initialUtc,
                    duration = 0,
                    codec = "video/avc",
                    width = width,
                    height = height,
                    audio = false,
                    bytes = 0,
                    internalName = createdFile.name,
                    path = createdFile.absolutePath,
                    status = SegmentStatus.RECORDING,
                )
                dao.insert(row)
                // Durable quarantine exists before prepare/start can write any camera data.
                // If creation/fsync fails, the catch path rejects this empty segment.
                withContext(Dispatchers.IO) { watermarkSegmentGuard.markUnverified(segmentId) }
                if (!recordingRequested || !cameraReady || watermarkHealth.failure != null) {
                    dao.failRecording(segmentId, expectedRevision = 1)
                    withContext(Dispatchers.IO) { fileStore.discardTempFile(createdFile) }
                    return@launch
                }
                val output = FileOutputOptions.Builder(createdFile).build()
                val capture = checkNotNull(videoCapture)
                preview?.targetRotation = cameraTargetRotation
                viewerImageAnalysis?.targetRotation = cameraTargetRotation
                analysisGeometryPending = true
                capture.targetRotation = cameraTargetRotation
                var pending = capture.output.prepareRecording(this@RecordingService, output)
                if (audioActive && hasAudioPermission()) {
                    pending = runCatching {
                        @android.annotation.SuppressLint("MissingPermission")
                        pending.withAudioEnabled()
                    }.getOrElse { error ->
                        Log.w("FlipCamera", "Không bật được ghi âm thanh cho segment; tiếp tục ghi video không tiếng", error)
                        pending
                    }
                }
                val active = SegmentRun(
                    id = segmentId,
                    sessionId = activeSessionId,
                    tempFile = createdFile,
                    requestStartUtc = initialUtc,
                    startElapsed = null,
                    recording = null,
                    watermark = WatermarkSegmentPublication(segmentId, connectGeneration),
                )
                currentSegment = active
                if (watermarkHealth.failure != null) {
                    active.watermark.invalidate()
                    throw IOException("Watermark lỗi trong lúc bắt đầu segment.")
                }
                publishState(STATE_STARTING_RECORDING, "Đang chờ CameraX bắt đầu phân đoạn…", active = true)
                active.recording = pending.start(cameraExecutor) { event ->
                    mainHandler.post { onVideoEvent(segmentId, event) }
                }
            } catch (error: Exception) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val failedStartUtc = previousEndUtc ?: initialUtc
                val failedStartElapsed = previousEndElapsed ?: initialElapsed
                val failedAtUtc = failedStartUtc + (nowElapsed - failedStartElapsed).coerceAtLeast(1L)
                runCatching {
                    dao.failRecordingWithGap(
                        id = segmentId,
                        expectedRevision = 1,
                        gap = GapEntity(
                            sessionId = activeSessionId,
                            startUtc = failedStartUtc,
                            endUtc = failedAtUtc,
                            reason = "Không thể khởi tạo phân đoạn ghi hình.",
                        ),
                    )
                }
                temporaryFile?.let { file ->
                    runCatching { withContext(Dispatchers.IO) { fileStore.discardTempFile(file) } }
                }
                if (currentSegment?.id == segmentId) currentSegment = null
                recordingRequested = false
                finishMotionSampling()
                publishState(STATE_SEGMENT_FAILED, "Không thể bắt đầu MP4: ${error.localizedMessage ?: "lỗi camera"}", active = cameraReady)
            } finally {
                prepareJob = null
                if (disconnectWhenFinalized && currentSegment == null) finishDisconnect()
                else if (serviceState == STATE_STOPPING && !recordingRequested && currentSegment == null && cameraReady) {
                    publishState(STATE_PREVIEW_READY, "Camera sẵn sàng · chưa ghi video.", active = true)
                }
            }
        }
    }

    private fun onVideoEvent(segmentId: String, event: VideoRecordEvent) {
        val active = currentSegment ?: return
        if (active.id != segmentId) return
        when (event) {
            is VideoRecordEvent.Start -> {
                active.startElapsed = SystemClock.elapsedRealtime()
                active.startUtc = System.currentTimeMillis()
                if (watermarkHealth.failure != null) {
                    active.recording?.stop()
                    publishWatermarkError()
                    return
                }
                if (!motionSamplingReady) beginMotionSampling()
                motionCaptureActive = false
                val startElapsed = active.startElapsed ?: SystemClock.elapsedRealtime()
                val startUtc = active.startUtc ?: System.currentTimeMillis()
                runCatching {
                    viewerAnalysisExecutor.execute {
                        motionCapture.recordingStarted(
                            MotionCaptureContext(
                                sessionId = active.sessionId,
                                segmentId = active.id,
                                utcOffsetMs = startUtc - startElapsed,
                            ),
                        )
                        motionCaptureActive = true
                    }
                }.onFailure {
                    motionCaptureActive = false
                }
                recordObservedGap(active)
                val analysisNote = if (viewerImageAnalysis == null) {
                    " · live và sự kiện chuyển động không khả dụng"
                } else {
                    " · sự kiện chuyển động phân tích cục bộ"
                }
                publishState(STATE_RECORDING, "Đang ghi phân đoạn ${active.id.take(8)}…$analysisNote", active = true)
                updateNotification(getString(R.string.recording_notification_title), if (audioActive) "Đang ghi MP4 · có âm thanh" else "Đang ghi MP4 · audio off")
                segmentHandler.removeCallbacks(rotateSegment)
                segmentHandler.postDelayed(
                    rotateSegment,
                    segmentDurationMs(StoragePolicyStore(applicationContext).load().mode),
                )
            }
            is VideoRecordEvent.Status -> {
                if (watermarkHealth.failure != null) return
                val durationSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                val minutes = durationSeconds / 60L
                val seconds = durationSeconds % 60L
                val timeFormatted = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                val thermalNote = if (currentThermalState != ThermalLoadState.NORMAL) " · ${currentThermalState.description}" else ""
                val tempNote = if (currentBatteryTemperatureC > 0f) " · ${String.format(Locale.US, "%.1f°C", currentBatteryTemperatureC)}" else ""
                updateNotification(getString(R.string.recording_notification_title), "Đang ghi MP4 · $timeFormatted$tempNote$thermalNote")
            }
            is VideoRecordEvent.Finalize -> {
                finishMotionSampling()
                finalizeSegment(active, event)
            }
        }
    }

    private fun recordObservedGap(active: SegmentRun) {
        val priorElapsed = previousEndElapsed ?: return
        val priorUtc = previousEndUtc ?: return
        val currentElapsed = active.startElapsed ?: return
        val currentUtc = active.startUtc ?: return
        previousEndElapsed = null
        previousEndUtc = null
        if (currentElapsed <= priorElapsed || currentUtc <= priorUtc) return
        // Boundaries reflect observed CameraX callbacks, not an assertion of frame-perfect timing.
        val gap = GapEntity(
            sessionId = active.sessionId,
            startUtc = priorUtc,
            endUtc = currentUtc,
            reason = "Khoảng chuyển tiếp giữa các phân đoạn (ước tính theo callback camera)",
        )
        lifecycleScope.launch(Dispatchers.IO) { runCatching { dao.insertGap(gap) } }
    }

    private fun finalizeSegment(active: SegmentRun, event: VideoRecordEvent.Finalize) {
        if (active.finalizing) return
        active.finalizing = true
        // Atomic segment decision BEFORE any suspension. Do not reclassify an old
        // finalized file because live/the next segment fails while inspection/Room runs.
        val publication = active.watermark.seal()
        val watermarkRejected = publication == null
        segmentHandler.removeCallbacks(rotateSegment)
        val finalizedElapsed = SystemClock.elapsedRealtime()
        val startUtc = active.startUtc ?: active.requestStartUtc
        val startElapsed = active.startElapsed ?: finalizedElapsed
        val endUtc = startUtc + (finalizedElapsed - startElapsed).coerceAtLeast(1L)

        // Clear currentSegment immediately so the Recorder is unblocked and can immediately
        // start preparing the next segment if recording is continuous.
        if (currentSegment?.id == active.id) currentSegment = null

        val keepRecording = recordingRequested && cameraReady && !disconnectWhenFinalized &&
            watermarkHealth.failure == null && !watermarkRejected && event.error == VideoRecordEvent.Finalize.ERROR_NONE

        if (keepRecording) {
            previousEndUtc = endUtc
            previousEndElapsed = finalizedElapsed
            startNextSegment()
        }

        lifecycleScope.launch {
            var metadata: FinalizedSegmentFile? = null
            try {
                if (watermarkRejected) {
                    // CameraX has finalized the output, but its watermark guarantee failed.
                    // Retain the original pending file; do not publish it READY or pass it
                    // through the normal invalid-file cleanup path.
                    throw IOException("Dừng do lỗi watermark; giữ tệp MP4 để kiểm tra/khôi phục.")
                }
                if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    throw IOException("CameraX finalize error ${event.error}: ${event.cause?.message ?: "không có thêm chi tiết"}")
                }
                val finalized = withContext(Dispatchers.IO) {
                    fileStore.finalizeTempFile(active.id, active.tempFile)
                }
                metadata = finalized
                check(publication.segmentId == active.id && publication.generation == active.watermark.generation)
                val updated = active.watermark.publish(publication) { dao.finalizeSegment(
                    id = active.id,
                    expectedRevision = 1,
                    startUtc = startUtc,
                    endUtc = endUtc,
                    duration = finalized.durationMs,
                    bytes = finalized.bytes,
                    width = finalized.width,
                    height = finalized.height,
                    codec = finalized.videoMime ?: "video/avc",
                    audio = finalized.audioTrackPresent,
                    finalInternalName = finalized.internalName,
                    finalPath = finalized.path,
                ) }
                if (updated != 1 && !finalizedFilePublished(active.id, finalized).getOrDefault(false)) {
                    throw IOException("Không thể xác nhận trạng thái segment trong database.")
                }
                clearWatermarkGuardAfterReady(active.id)
                triggerStoragePolicyCheck()
                if (!keepRecording) {
                    previousEndUtc = endUtc
                    previousEndElapsed = finalizedElapsed
                }
                if (watermarkHealth.failure == null) {
                    if (currentSegment == null && !recordingRequested) {
                        publishState(STATE_SEGMENT_READY, "Đã lưu MP4 ${finalized.width} × ${finalized.height} · ${finalized.bytes / 1024} KB", active = true, segmentId = active.id)
                    } else {
                        updateNotification(getString(R.string.recording_notification_title), "Đã lưu MP4 · đang ghi phân đoạn tiếp theo")
                        sendBroadcast(
                            Intent(ACTION_STATE)
                                .setPackage(packageName)
                                .putExtra(EXTRA_CAMERA_HEALTH, getCameraHealth().description())
                                .putExtra(EXTRA_CAMERA_CAN_START, getCameraHealth().canStart)
                                .putExtra(EXTRA_ACTIVE, cameraRequested)
                                .putExtra(EXTRA_STATE, serviceState)
                                .putExtra(EXTRA_MESSAGE, message)
                                .putExtra(EXTRA_SEGMENT_ID, active.id),
                        )
                    }
                } else publishWatermarkError()
            } catch (error: Exception) {
                val finalized = metadata
                val publicationCheck = finalized?.let { finalizedFilePublished(active.id, it) }
                val alreadyPublished = publicationCheck?.getOrDefault(false) == true
                if (alreadyPublished) {
                    if (publication != null) clearWatermarkGuardAfterReady(active.id)
                    if (!keepRecording) {
                        previousEndUtc = endUtc
                        previousEndElapsed = finalizedElapsed
                    }
                    if (watermarkHealth.failure == null) {
                        if (currentSegment == null && !recordingRequested) {
                            publishState(STATE_SEGMENT_READY, "Đã lưu MP4 ${finalized.width} × ${finalized.height} · ${finalized.bytes / 1024} KB", active = true, segmentId = active.id)
                        }
                    } else publishWatermarkError()
                } else {
                    // Keep the renamed file if Room cannot answer whether READY committed.
                    // Deleting it in that ambiguous case could break a valid READY row.
                    if (finalized != null && publicationCheck?.isSuccess == true && !watermarkRejected) {
                        runCatching {
                            withContext(Dispatchers.IO) { fileStore.discardFinalizedFile(finalized) }
                        }
                    }
                    val failedStartUtc = startUtc
                    val failedEndUtc = endUtc
                    runCatching {
                        dao.failRecordingWithGap(
                            id = active.id,
                            expectedRevision = 1,
                            gap = GapEntity(
                                sessionId = active.sessionId,
                                startUtc = failedStartUtc,
                                endUtc = failedEndUtc,
                                reason = "MP4 không thể hoàn tất; đoạn hình này không khả dụng.",
                            ),
                        )
                    }
                    if (!watermarkRejected) {
                        runCatching { withContext(Dispatchers.IO) { fileStore.discardTempFile(active.tempFile) } }
                    }
                    recordingRequested = false
                    finishMotionSampling()
                    publishState(STATE_SEGMENT_FAILED, "Phân đoạn chưa dùng được: ${error.localizedMessage ?: "lỗi finalize"}", active = true, segmentId = active.id)
                }
            } finally {
                if (!keepRecording) {
                    if (disconnectWhenFinalized) finishDisconnect()
                    else if (watermarkHealth.failure != null) publishWatermarkError()
                    else if (serviceState == STATE_SEGMENT_FAILED) Unit
                    else if (currentSegment == null) publishState(if (cameraReady) STATE_PREVIEW_READY else STATE_IDLE, if (cameraReady) "Camera sẵn sàng · chưa ghi video." else "Camera đã dừng.", active = cameraReady)
                }
            }
        }
    }

    private suspend fun clearWatermarkGuardAfterReady(segmentId: String) {
        runCatching { withContext(Dispatchers.IO) { watermarkSegmentGuard.clearAfterReady(segmentId) } }
            .onFailure {
                // A leftover guard on an already committed READY row is safe. Recovery
                // never downgrades READY or deletes the original media for this marker.
                Log.w("FlipCamera", "READY committed; watermark guard cleanup can be retried", it)
            }
    }

    private suspend fun finalizedFilePublished(
        segmentId: String,
        finalized: FinalizedSegmentFile,
    ): Result<Boolean> = runCatching {
        val row = dao.getById(segmentId)
        row?.status == SegmentStatus.READY &&
            row.internalName == finalized.internalName &&
            row.path == finalized.path
    }

    private val segmentHandler = Handler(Looper.getMainLooper())
    private val rotateSegment = Runnable {
        val active = currentSegment ?: return@Runnable
        if (!recordingRequested || active.finalizing) return@Runnable
        publishState(STATE_ROTATING_SEGMENT, "Đang hoàn tất phân đoạn…", active = true)
        active.recording?.stop()
    }

    private fun disconnectCamera() {
        recordingRequested = false
        finishMotionSampling()
        disconnectWhenFinalized = true
        segmentHandler.removeCallbacks(rotateSegment)
        if (currentSegment?.recording != null) {
            publishState(STATE_STOPPING, "Đang hoàn tất video trước khi tắt camera…", active = true)
            if (currentSegment?.finalizing != true) currentSegment?.recording?.stop()
        } else if (prepareJob == null) {
            finishDisconnect()
        } else {
            publishState(STATE_STOPPING, "Đang hủy khởi tạo camera…", active = true)
        }
    }

    private fun finishDisconnect() {
        detachCameraHealth()
        cameraOrientationListener.disable()
        stopViewerServer()
        unregisterNetworkCallback()
        viewerFrameHub.clear()
        viewerImageAnalysis?.clearAnalyzer()
        overlayEffect?.clearOnDrawListener()
        overlayEffect?.close()
        overlayEffect = null
        watermarkClock = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        viewerImageAnalysis = null
        videoCapture = null
        cameraReady = false
        cameraRequested = false
        disconnectWhenFinalized = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        publishState(STATE_IDLE, "Camera đã tắt. Tệp đã hoàn tất vẫn nằm trong thư viện.", active = false)
        stopSelf()
    }

    /**
     * Một lần chuyển YUV -> NV21 (tái sử dụng buffer), một lần chuyển NV21 -> ARGB đã xoay,
     * vẽ watermark, rồi nén JPEG một lần. Không còn vòng nén-giải mã-nén trung gian.
     * Chỉ gọi từ analyzer (viewerAnalysisExecutor), nên buffer field an toàn.
     */
    private fun encodeViewerFrame(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width < 2 || height < 2 || width % 2 != 0 || height % 2 != 0) return null
        val planes = image.planes
        if (planes.size < 3) return null
        val rotation = image.imageInfo.rotationDegrees.mod(360)
        check(rotation in listOf(0, 90, 180, 270)) { "Hướng ảnh live không hợp lệ." }

        val frameSize = width * height
        if (viewerNv21.size != frameSize * 3 / 2) viewerNv21 = ByteArray(frameSize * 3 / 2)
        if (viewerArgb.size != frameSize) viewerArgb = IntArray(frameSize)
        if (viewerChromaU.size != width) {
            viewerChromaU = ByteArray(width)
            viewerChromaV = ByteArray(width)
        }
        val nv21 = viewerNv21
        if (!copyYuvToNv21(planes[0], planes[1], planes[2], crop.left, crop.top, width, height, nv21)) return null

        val argb = viewerArgb
        nv21ToArgbRotated(nv21, width, height, rotation, argb)
        val outWidth = if (rotation == 90 || rotation == 270) height else width
        val outHeight = if (rotation == 90 || rotation == 270) width else height
        val bitmap = viewerBitmapFor(outWidth, outHeight)
        bitmap.setPixels(argb, 0, outWidth, 0, 0, outWidth, outHeight)
        WatermarkOverlay.draw(Canvas(bitmap), outWidth, outHeight,
            WatermarkOverlay.formatText(watermarkUtc(image.imageInfo.timestamp, 1)))
        viewerJpegBuffer.reset()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, VIEWER_JPEG_QUALITY, viewerJpegBuffer)) {
            "Không nén được frame live có watermark."
        }
        return viewerJpegBuffer.toByteArray()
    }

    private fun viewerBitmapFor(width: Int, height: Int): Bitmap {
        viewerBitmap?.let { if (it.width == width && it.height == height && !it.isRecycled) return it }
        viewerBitmap?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { viewerBitmap = it }
    }

    /** Chép vùng crop của Y/U/V sang NV21 (Y rồi VU xen kẽ). Trả false nếu buffer không đủ dữ liệu. */
    private fun copyYuvToNv21(
        yPlane: ImageProxy.PlaneProxy,
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        nv21: ByteArray,
    ): Boolean {
        val yBuffer = yPlane.buffer.duplicate()
        val yOrigin = yBuffer.position()
        var destination = 0
        for (row in 0 until height) {
            val rowStart = yOrigin + (top + row) * yPlane.rowStride + left * yPlane.pixelStride
            if (yPlane.pixelStride == 1) {
                yBuffer.position(rowStart)
                yBuffer.get(nv21, destination, width)
                destination += width
            } else {
                for (column in 0 until width) {
                    nv21[destination++] = yBuffer.get(rowStart + column * yPlane.pixelStride)
                }
            }
        }

        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uOrigin = uBuffer.position()
        val vOrigin = vBuffer.position()
        val uStride = uPlane.pixelStride
        val vStride = vPlane.pixelStride
        val half = width / 2
        val chromaOffset = width * height
        for (row in 0 until height / 2) {
            val chromaRow = top / 2 + row
            val uBase = uOrigin + chromaRow * uPlane.rowStride + (left / 2) * uStride
            val vBase = vOrigin + chromaRow * vPlane.rowStride + (left / 2) * vStride
            if (!readChromaRow(uBuffer, uBase, uStride, half, viewerChromaU)) return false
            if (!readChromaRow(vBuffer, vBase, vStride, half, viewerChromaV)) return false
            val index = chromaOffset + row * width
            for (column in 0 until half) {
                nv21[index + column * 2] = viewerChromaV[column * vStride]
                nv21[index + column * 2 + 1] = viewerChromaU[column * uStride]
            }
        }
        return true
    }

    /**
     * Đọc bulk một hàng chroma: cần `span` byte liên tiếp, mẫu thứ c nằm ở dest[c * pixelStride].
     * Với semi-planar, span = width - 1 nên không đọc vượt limit của buffer.
     */
    private fun readChromaRow(buffer: ByteBuffer, base: Int, pixelStride: Int, count: Int, dest: ByteArray): Boolean {
        if (pixelStride < 1 || count <= 0) return false
        val span = (count - 1) * pixelStride + 1
        if (base < 0 || span > dest.size || base + span > buffer.limit()) return false
        buffer.position(base)
        buffer.get(dest, 0, span)
        return true
    }

    /**
     * NV21 full-range (JFIF, giống cách YuvImage đã diễn giải trước đây) -> ARGB, xoay theo rotation
     * (chiều kim đồng hồ, giống Matrix.setRotate cũ). out có kích thước width*height.
     */
    private fun nv21ToArgbRotated(nv21: ByteArray, width: Int, height: Int, rotation: Int, out: IntArray) {
        val chromaOffset = width * height
        for (y in 0 until height) {
            val chromaBase = chromaOffset + (y shr 1) * width
            for (x in 0 until width) {
                val luma = nv21[y * width + x].toInt() and 0xFF
                val chroma = chromaBase + (x and 1.inv())
                val cr = (nv21[chroma].toInt() and 0xFF) - 128
                val cb = (nv21[chroma + 1].toInt() and 0xFF) - 128
                val r = clampToByte(luma + ((1436 * cr + 512) shr 10))
                val g = clampToByte(luma + ((-352 * cb - 731 * cr + 512) shr 10))
                val b = clampToByte(luma + ((1815 * cb + 512) shr 10))
                val pixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                val target = when (rotation) {
                    0 -> y * width + x
                    90 -> x * height + (height - 1 - y)
                    180 -> (height - 1 - y) * width + (width - 1 - x)
                    else -> (width - 1 - x) * height + y
                }
                out[target] = pixel
            }
        }
    }

    private fun createNotificationChannel() {
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), android.app.NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Trạng thái dịch vụ camera Flip Camera" },
        )
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            OPEN_REQUEST,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopRecording = PendingIntent.getService(
            this,
            RECORDING_STOP_REQUEST,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopCamera = PendingIntent.getService(
            this,
            CAMERA_STOP_REQUEST,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openApp)
        if (recordingRequested || currentSegment != null) {
            builder.addAction(0, "Dừng ghi", stopRecording)
        }
        return builder
            .addAction(0, "Tắt camera", stopCamera)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        if (!foregroundStarted) return
        val now = SystemClock.elapsedRealtime()
        if (serviceState == STATE_RECORDING && now - lastNotificationElapsed < NOTIFICATION_INTERVAL_MS) return
        lastNotificationElapsed = now
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensureMicrophoneForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            foregroundStarted &&
            hasAudioPermission()
        ) {
            runCatching {
                val notification = buildNotification(
                    getString(R.string.recording_notification_title),
                    if (currentSegment != null) {
                        if (audioActive) "Đang ghi MP4 · có âm thanh" else "Đang ghi MP4 · audio off"
                    } else {
                        "Camera đang hoạt động · chưa ghi video."
                    },
                )
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            }
        }
    }

    private fun publishState(
        state: String,
        message: String,
        active: Boolean = cameraRequested,
        segmentId: String? = null,
    ) {
        serviceState = state
        this.message = message
        if (foregroundStarted) {
            updateNotification(
                getString(R.string.recording_notification_title),
                "$message · ${getCameraHealth().description()}",
            )
        }
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_CAMERA_HEALTH, getCameraHealth().description())
                .putExtra(EXTRA_CAMERA_CAN_START, getCameraHealth().canStart)
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_SEGMENT_ID, segmentId)
                .putExtra(EXTRA_VIEWER_ENABLED, viewerAdminState.enabled)
                .putExtra(EXTRA_VIEWER_STARTING, viewerAdminState.starting)
                .putExtra(EXTRA_VIEWER_RUNNING, viewerAdminState.running)
                .putExtra(EXTRA_VIEWER_ENDPOINTS, viewerAdminState.endpoints.joinToString("\n"))
                .putExtra(EXTRA_VIEWER_FINGERPRINT, viewerAdminState.certificateFingerprint)
                .putExtra(EXTRA_VIEWER_ERROR, viewerAdminState.errorMessage),
        )
    }

    override fun onDestroy() {
        detachCameraHealth()
        cameraOrientationListener.disable()
        viewerGeneration += 1L
        viewerRequested = false
        viewerRunningGeneration = -1L
        viewerPairing.reset()
        unregisterNetworkCallback()
        viewerCommands.close()
        viewerServerWorker?.cancel()
        viewerFrameHub.clear()
        viewerAudioHub.close()
        viewerSpeakerHub.close()
        recordingRequested = false
        finishMotionSampling()
        segmentHandler.removeCallbacks(rotateSegment)
        if (recordingWakeLock?.isHeld == true) runCatching { recordingWakeLock?.release() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            val powerManager = getSystemService(PowerManager::class.java)
            runCatching { thermalListener?.let { powerManager?.removeThermalStatusListener(it) } }
            thermalListener = null
        }
        mainHandler.removeCallbacks(nightTorchTick)
        runCatching { unregisterReceiver(batteryReceiver) }
        viewerImageAnalysis?.clearAnalyzer()
        overlayEffect?.clearOnDrawListener()
        overlayEffect?.close()
        overlayEffect = null
        watermarkThread.quitSafely()
        storageJob?.cancel()
        storageJob = null
        storageScope.cancel()
        cameraProvider?.unbindAll()
        viewerImageAnalysis = null
        viewerAnalysisExecutor.shutdown()
        cameraExecutor.shutdown()
        cameraReady = false
        cameraRequested = false
        foregroundStarted = false
        publishState(STATE_IDLE, "Dịch vụ camera đã dừng. Phân đoạn đang ghi có thể chưa hoàn tất.", active = false)
        super.onDestroy()
    }

    private data class SegmentRun(
        val id: String,
        val sessionId: String,
        val tempFile: File,
        val requestStartUtc: Long,
        var startUtc: Long? = null,
        var startElapsed: Long? = null,
        var recording: Recording?,
        var finalizing: Boolean = false,
        val watermark: WatermarkSegmentPublication,
    )

    companion object {
        private const val NIGHT_TORCH_INTERVAL_MS = 30_000L
        /** Storage-policy pass cadence; the first pass runs one interval after the service starts. */
        const val STORAGE_POLICY_INTERVAL_MS = 45_000L
        const val ACTION_START = "com.phamdu.flipcamera.action.START_CAMERA"
        const val ACTION_STOP = "com.phamdu.flipcamera.action.STOP_CAMERA"
        const val ACTION_STOP_RECORDING = "com.phamdu.flipcamera.action.STOP_RECORDING"
        const val ACTION_STATE = "com.phamdu.flipcamera.action.SERVICE_STATE"
        const val EXTRA_CAMERA_HEALTH = "camera_health"
        const val EXTRA_CAMERA_CAN_START = "camera_can_start"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SEGMENT_ID = "segment_id"
        const val EXTRA_VIEWER_ENABLED = "viewer_enabled"
        const val EXTRA_VIEWER_STARTING = "viewer_starting"
        const val EXTRA_VIEWER_RUNNING = "viewer_running"
        const val EXTRA_VIEWER_ENDPOINTS = "viewer_endpoints"
        const val EXTRA_VIEWER_FINGERPRINT = "viewer_fingerprint"
        const val EXTRA_VIEWER_ERROR = "viewer_error"

        const val STATE_IDLE = "IDLE"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_PREVIEW_READY = "PREVIEW_READY"
        const val STATE_STARTING_RECORDING = "STARTING_RECORDING"
        const val STATE_RECORDING = "RECORDING"
        const val STATE_ROTATING_SEGMENT = "ROTATING_SEGMENT"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_SEGMENT_READY = "SEGMENT_READY"
        const val STATE_SEGMENT_FAILED = "SEGMENT_FAILED"
        const val STATE_ERROR = "ERROR"

        // Live 540p: 720p mã hóa ~69 ms/khung và ~90 KB/khung (quá nặng qua Tailscale relay ~6 Mbps), 360p thì mờ; 540p là mức giữa.
        private const val VIEWER_FRAME_WIDTH = 960
        private const val VIEWER_FRAME_HEIGHT = 540
        private const val VIEWER_FALLBACK_WIDTH = 640
        private const val VIEWER_FALLBACK_HEIGHT = 360
        private const val VIEWER_JPEG_QUALITY = 60
        private const val LIVE_STATS_INTERVAL_MS = 5_000L
        private const val CHANNEL_ID = "recorder_scaffold"
        private const val NOTIFICATION_ID = 1001
        private const val OPEN_REQUEST = 1002
        private const val RECORDING_STOP_REQUEST = 1003
        private const val CAMERA_STOP_REQUEST = 1004
        private const val MIN_RECORDING_STORAGE_BYTES = 500L * 1024L * 1024L
        private const val AUTO_TRIM_TRIGGER_BYTES = 2_000L * 1024L * 1024L
        private const val RECORDING_WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        /** Fallback khi camera không có range phù hợp (xem selectViewerFpsRange). */
        private const val RECORDING_FPS = 15
        private const val NOTIFICATION_INTERVAL_MS = 15_000L
        private const val VIEWER_HTTPS_PORT = 8_443
    }
}

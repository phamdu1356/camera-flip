package com.phamdu.flipcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.Formatter
import android.text.InputType
import android.text.InputFilter
import android.view.View
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phamdu.flipcamera.data.CameraDatabase
import com.phamdu.flipcamera.data.MotionEventEntity
import com.phamdu.flipcamera.data.MotionEventStatus
import com.phamdu.flipcamera.data.RecordingMode
import com.phamdu.flipcamera.data.RetentionManager
import com.phamdu.flipcamera.data.SegmentEntity
import com.phamdu.flipcamera.data.RecordingFpsMode
import com.phamdu.flipcamera.data.StoragePolicyConfig
import com.phamdu.flipcamera.data.StoragePolicyStore
import com.phamdu.flipcamera.data.VideoCodecMode
import com.phamdu.flipcamera.data.formatStorageGb
import com.phamdu.flipcamera.data.parseStoragePolicyInput
import com.phamdu.flipcamera.data.SegmentFileStore
import com.phamdu.flipcamera.data.SegmentStatus
import com.phamdu.flipcamera.security.PairingApproval
import com.phamdu.flipcamera.security.PairingRequestSummary
import com.phamdu.flipcamera.security.ViewerPassword
import com.phamdu.flipcamera.databinding.ActivityMainBinding
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opening this screen never requests permission, starts a service, or starts recording. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var service: RecordingService? = null
    private var bindingRequested = false
    private var receiverRegistered = false
    private var screenStarted = false
    private var serviceActive = false
    private var cameraState = "IDLE"
    private var stateMessage: String? = null
    private var cameraHealthMessage: String? = null
    private var cameraCanStart = false
    private var permissionsPending = false
    private var recordRequestPending = false
    private var notificationWarning = false
    private var readySegments = emptyList<SegmentEntity>()
    private var historyLoaded = false
    private var historyFailed = false
    private var historyDialog: AlertDialog? = null
    private var viewerPasswordDialog: AlertDialog? = null
    private var historyList: ListView? = null
    private var historyStatus: TextView? = null
    private var historyAdapter: ArrayAdapter<String>? = null
    private var viewerAdminState = ViewerAdminState()
    private var segmentPendingExport: SegmentEntity? = null

    private val exportSegmentDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { destination ->
        val segment = segmentPendingExport
        segmentPendingExport = null
        if (destination != null && segment != null) copySegmentToUri(segment, destination)
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        permissionsPending = false
        if (!hasPermission(Manifest.permission.CAMERA)) {
            serviceActive = false
            cameraState = "IDLE"
            stateMessage = getString(R.string.camera_permission_missing)
            renderState()
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_dialog_title)
                .setMessage(R.string.camera_permission_missing)
                .setPositiveButton(R.string.open_app_settings) { _, _ -> openAppSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            notificationWarning = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            connectCamera()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = (binder as? RecordingService.LocalBinder)?.getService()
            if (connectedService == null) {
                releaseBinding()
                showConnectionFailure()
                return
            }
            service = connectedService
            connectedService.setPreviewSurfaceProvider(binding.previewView.surfaceProvider)
            cameraState = connectedService.getState()
            serviceActive = cameraState != "IDLE"
            stateMessage = connectedService.getMessage()
            cameraHealthMessage = connectedService.getCameraHealth().description()
            cameraCanStart = connectedService.getCameraHealth().canStart
            viewerAdminState = connectedService.getViewerAdminState()
            renderState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            cameraHealthMessage = null
            cameraCanStart = false
            releaseBinding()
            serviceActive = false
            recordRequestPending = false
            cameraState = "ERROR"
            stateMessage = getString(R.string.camera_service_disconnected)
            renderState()
        }

        override fun onBindingDied(name: ComponentName) {
            releaseBinding()
            if (screenStarted) bindToCamera(create = false)
        }

        override fun onNullBinding(name: ComponentName) {
            releaseBinding()
            showConnectionFailure()
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != RecordingService.ACTION_STATE) return
            serviceActive = intent.getBooleanExtra(RecordingService.EXTRA_ACTIVE, false)
            cameraState = intent.getStringExtra(RecordingService.EXTRA_STATE)
                ?: if (serviceActive) "CONNECTING" else "IDLE"
            stateMessage = intent.getStringExtra(RecordingService.EXTRA_MESSAGE)
            cameraHealthMessage = intent.getStringExtra(RecordingService.EXTRA_CAMERA_HEALTH)
            cameraCanStart = intent.getBooleanExtra(RecordingService.EXTRA_CAMERA_CAN_START, false)
            viewerAdminState = ViewerAdminState(
                enabled = intent.getBooleanExtra(RecordingService.EXTRA_VIEWER_ENABLED, false),
                starting = intent.getBooleanExtra(RecordingService.EXTRA_VIEWER_STARTING, false),
                running = intent.getBooleanExtra(RecordingService.EXTRA_VIEWER_RUNNING, false),
                endpoints = intent.getStringExtra(RecordingService.EXTRA_VIEWER_ENDPOINTS)
                    ?.lines()?.filter(String::isNotBlank).orEmpty(),
                certificateFingerprint = intent.getStringExtra(RecordingService.EXTRA_VIEWER_FINGERPRINT),
                errorMessage = intent.getStringExtra(RecordingService.EXTRA_VIEWER_ERROR),
            )
            recordRequestPending = false
            if (serviceActive && !bindingRequested) bindToCamera(create = false)
            if (!serviceActive) releaseBinding()
            renderState()
            // READY history is driven by Room, including every finalized segment broadcast.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupCameraQuality()
        setupStoragePolicy()
        window.statusBarColor = Color.rgb(20, 24, 24)
        window.navigationBarColor = Color.rgb(20, 24, 24)
        binding.btnConnect.setOnClickListener { requestConnectPermissions() }
        binding.btnStartRecording.setOnClickListener {
            val connectedService = service ?: return@setOnClickListener
            if (cameraState != "PREVIEW_READY" || recordRequestPending) return@setOnClickListener
            recordRequestPending = true
            stateMessage = getString(R.string.camera_record_request)
            renderState()
            try {
                connectedService.startRecording()
            } catch (_: Exception) {
                recordRequestPending = false
                stateMessage = getString(R.string.camera_command_failed)
                renderState()
            }
        }
        binding.btnStopRecording.setOnClickListener {
            val connectedService = service ?: return@setOnClickListener
            if (cameraState !in setOf("STARTING_RECORDING", "RECORDING", "ROTATING_SEGMENT")) return@setOnClickListener
            cameraState = "STOPPING"
            stateMessage = null
            renderState()
            try {
                connectedService.stopRecording()
            } catch (_: Exception) {
                cameraState = connectedService.getState()
                stateMessage = getString(R.string.camera_command_failed)
                renderState()
            }
        }
        binding.btnStopCamera.setOnClickListener { stopCamera() }
        binding.btnStartViewer.setOnClickListener {
            val connectedService = service ?: return@setOnClickListener
            val started = connectedService.startViewerServer()
            viewerAdminState = connectedService.getViewerAdminState()
            if (!started) {
                Toast.makeText(
                    this,
                    viewerAdminState.errorMessage ?: getString(R.string.viewer_disabled),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            renderViewerAdmin()
        }
        binding.btnStopViewer.setOnClickListener {
            service?.stopViewerServer()
            viewerAdminState = service?.getViewerAdminState() ?: ViewerAdminState()
            renderViewerAdmin()
        }
        binding.btnPairingCode.setOnClickListener { showViewerPairingCode() }
        binding.btnViewerPassword.setOnClickListener { showViewerPasswordDialog() }
        binding.btnPendingViewers.setOnClickListener { showPendingViewerRequests() }
        binding.btnHistory.setOnClickListener { showHistory() }
        binding.btnRetention.setOnClickListener { showRetentionOptions() }
        binding.btnMotionEvents.setOnClickListener { showMotionEvents() }
        renderState()
        observeHistory()
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
        ContextCompat.registerReceiver(
            this,
            serviceReceiver,
            IntentFilter(RecordingService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        // Flags=0 reconnects to an existing started service without creating one on launch.
        bindToCamera(create = false)
    }

    override fun onStop() {
        screenStarted = false
        if (receiverRegistered) {
            unregisterReceiver(serviceReceiver)
            receiverRegistered = false
        }
        releaseBinding()
        // The user-started foreground service owns capture until an explicit Stop camera action.
        super.onStop()
    }

    override fun onDestroy() {
        viewerPasswordDialog?.dismiss()
        viewerPasswordDialog = null
        historyDialog?.dismiss()
        super.onDestroy()
    }

    private fun requestConnectPermissions() {
        if (permissionsPending || serviceActive) return
        val permissions = buildList {
            if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) {
            notificationWarning = false
            connectCamera()
        } else {
            permissionsPending = true
            stateMessage = getString(R.string.camera_permissions_waiting)
            renderState()
            permissionRequest.launch(permissions.toTypedArray())
        }
    }

    private fun connectCamera() {
        serviceActive = true
        cameraState = "CONNECTING"
        stateMessage = null
        renderState()
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START),
            )
            bindToCamera(create = true)
        } catch (_: Exception) {
            showConnectionFailure()
        }
    }

    private fun bindToCamera(create: Boolean) {
        if (bindingRequested || !screenStarted) return
        bindingRequested = try {
            bindService(
                Intent(this, RecordingService::class.java),
                serviceConnection,
                if (create) Context.BIND_AUTO_CREATE else 0,
            )
        } catch (_: Exception) {
            false
        }
        if (!bindingRequested) {
            if (create) {
                showConnectionFailure()
            } else {
                serviceActive = false
                cameraState = "IDLE"
                stateMessage = null
                renderState()
            }
        }
    }

    private fun releaseBinding() {
        service?.setPreviewSurfaceProvider(null)
        service = null
        if (bindingRequested) {
            unbindService(serviceConnection)
            bindingRequested = false
        }
    }

    private fun stopCamera() {
        val previousState = cameraState
        cameraState = "STOPPING"
        stateMessage = null
        recordRequestPending = false
        renderState()
        try {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        } catch (_: Exception) {
            cameraState = previousState
            stateMessage = getString(R.string.camera_command_failed)
            renderState()
        }
    }

    private fun showConnectionFailure() {
        serviceActive = false
        cameraState = "ERROR"
        stateMessage = getString(R.string.camera_connection_failed)
        renderState()
    }

    private fun renderState() {
        val title = when (cameraState) {
            "CONNECTING" -> R.string.camera_state_connecting
            "PREVIEW_READY" -> R.string.camera_state_ready
            "STARTING_RECORDING" -> R.string.camera_state_starting
            "RECORDING" -> R.string.camera_state_recording
            "ROTATING_SEGMENT" -> R.string.camera_state_stopping
            "STOPPING" -> R.string.camera_state_stopping
            "SEGMENT_READY" -> R.string.camera_state_segment_saved
            "SEGMENT_FAILED" -> R.string.camera_state_error
            "ERROR" -> R.string.camera_state_error
            else -> R.string.camera_state_idle
        }
        val message = when (cameraState) {
            "CONNECTING" -> R.string.camera_connecting_message
            "PREVIEW_READY" -> R.string.camera_ready_message
            "STARTING_RECORDING" -> R.string.camera_record_request
            "RECORDING" -> R.string.camera_recording_message
            "ROTATING_SEGMENT" -> R.string.camera_stopping_message
            "STOPPING" -> R.string.camera_stopping_message
            "SEGMENT_READY" -> R.string.camera_ready_message
            "SEGMENT_FAILED" -> R.string.camera_error_message
            "ERROR" -> R.string.camera_error_message
            else -> R.string.camera_idle_message
        }
        binding.tvRecorderStatus.setText(title)
        binding.tvStateMessage.text = listOfNotNull(stateMessage ?: getString(message), cameraHealthMessage).joinToString("\n")
        binding.btnConnect.isEnabled = !serviceActive && !permissionsPending
        binding.btnStartRecording.isEnabled = service != null && cameraCanStart &&
            cameraState in setOf("PREVIEW_READY", "SEGMENT_FAILED") &&
            !recordRequestPending
        binding.btnStopRecording.isEnabled = service != null && cameraState in
            setOf("STARTING_RECORDING", "RECORDING", "ROTATING_SEGMENT")
        binding.btnStopCamera.isEnabled = serviceActive
        binding.spinnerCameraQuality.isEnabled = !serviceActive && !permissionsPending
        binding.tvPreviewHint.visibility = if (cameraState in setOf(
                "PREVIEW_READY", "STARTING_RECORDING", "RECORDING", "ROTATING_SEGMENT", "STOPPING",
                "SEGMENT_READY", "SEGMENT_FAILED",
            )
        ) View.GONE else View.VISIBLE
        binding.tvPermissionWarning.visibility = if (notificationWarning) View.VISIBLE else View.GONE
        binding.tvPermissionWarning.setText(R.string.camera_notifications_denied)
        renderViewerAdmin()
    }

    private fun setupCameraQuality() {
        binding.spinnerCameraQuality.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.camera_quality_options,
            android.R.layout.simple_spinner_item,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerCameraQuality.setSelection(
            if (CameraQualityPreference.read(this) == androidx.camera.video.Quality.SD) 1 else 0,
        )
        binding.spinnerCameraQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                CameraQualityPreference.store(
                    this@MainActivity,
                    if (position == 1) androidx.camera.video.Quality.SD else androidx.camera.video.Quality.HD,
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Local settings only; the viewer and remote API cannot change storage policy. */
    // Numeric input values are not user-facing prose, so SetTextI18n does not apply here.
    @SuppressLint("SetTextI18n")
    private fun setupStoragePolicy() {
        binding.spinnerStorageMode.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.storage_mode_options,
            android.R.layout.simple_spinner_item,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val config = StoragePolicyStore(applicationContext).load()
        binding.spinnerStorageMode.setSelection(if (config.mode == RecordingMode.MOTION_ONLY) 1 else 0)
        binding.etStorageMaxDays.setText(config.maxDays.toString())
        binding.etStorageMaxGb.setText(formatStorageGb(config.maxBytes))
        renderStorageMotionWarning(config.mode == RecordingMode.MOTION_ONLY)
        binding.spinnerFpsMode.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.storage_fps_mode_options,
            android.R.layout.simple_spinner_item,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerFpsMode.setSelection(
            if (StoragePolicyStore(applicationContext).loadRecordingFpsMode() == RecordingFpsMode.SAVER) 1 else 0,
        )
        binding.spinnerCodec.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.storage_codec_options,
            android.R.layout.simple_spinner_item,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val encoderStore = StoragePolicyStore(applicationContext)
        binding.spinnerCodec.setSelection(
            if (encoderStore.loadVideoCodecMode() == VideoCodecMode.HEVC) 1 else 0,
        )
        binding.cbEncoderTuning.isChecked = encoderStore.loadEncoderTuning()
        binding.cbAudio.isChecked = encoderStore.loadAudioEnabled()
        binding.cbSpeaker.isChecked = encoderStore.loadSpeakerEnabled()
        binding.spinnerStorageMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderStorageMotionWarning(position == 1)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.btnStorageSave.setOnClickListener { saveStoragePolicy() }
    }

    private fun renderStorageMotionWarning(motionOnly: Boolean) {
        binding.tvStorageMotionWarning.visibility = if (motionOnly) View.VISIBLE else View.GONE
    }

    private fun saveStoragePolicy() {
        val mode = if (binding.spinnerStorageMode.selectedItemPosition == 1) {
            RecordingMode.MOTION_ONLY
        } else {
            RecordingMode.CONTINUOUS
        }
        val parsed: StoragePolicyConfig? = parseStoragePolicyInput(
            mode,
            binding.etStorageMaxDays.text.toString(),
            binding.etStorageMaxGb.text.toString(),
        )
        if (parsed == null) {
            binding.tvStoragePolicyStatus.text = getString(R.string.storage_policy_invalid)
            return
        }
        val fpsMode = if (binding.spinnerFpsMode.selectedItemPosition == 1) {
            RecordingFpsMode.SAVER
        } else {
            RecordingFpsMode.SMOOTH
        }
        val codecMode = if (binding.spinnerCodec.selectedItemPosition == 1) {
            VideoCodecMode.HEVC
        } else {
            VideoCodecMode.H264
        }
        val store = StoragePolicyStore(applicationContext)
        store.save(parsed)
        store.saveRecordingFpsMode(fpsMode)
        store.saveVideoCodecMode(codecMode)
        store.saveEncoderTuning(binding.cbEncoderTuning.isChecked)
        store.saveAudioEnabled(binding.cbAudio.isChecked)
        store.saveSpeakerEnabled(binding.cbSpeaker.isChecked)
        service?.setAudioRecordingEnabled(binding.cbAudio.isChecked)
        service?.setSpeakerPlaybackEnabled(binding.cbSpeaker.isChecked)
        service?.triggerStoragePolicyCheck()
        binding.tvStoragePolicyStatus.text = getString(R.string.storage_policy_saved)
    }

    private fun renderViewerAdmin() {
        binding.tvViewerStatus.text = when {
            viewerAdminState.errorMessage != null -> viewerAdminState.errorMessage
            viewerAdminState.running -> getString(R.string.viewer_running)
            viewerAdminState.starting -> getString(R.string.viewer_starting)
            !serviceActive -> getString(R.string.viewer_disabled)
            else -> getString(R.string.viewer_stopped)
        }
        binding.tvViewerEndpoints.text = viewerAdminState.endpoints
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?: getString(R.string.viewer_no_endpoint)
        binding.tvViewerFingerprint.text = viewerAdminState.certificateFingerprint
            ?.let { "SHA-256: $it" }
            ?: getString(R.string.viewer_no_fingerprint)
        binding.btnStartViewer.isEnabled = service != null && serviceActive && !viewerAdminState.enabled
        binding.btnStopViewer.isEnabled = service != null && viewerAdminState.enabled
        binding.btnPairingCode.isEnabled = service != null && viewerAdminState.running
        binding.btnPendingViewers.isEnabled = service != null && viewerAdminState.running
        binding.btnViewerPassword.isEnabled = service != null && serviceActive
        binding.tvViewerPasswordStatus.text = getString(
            if (service?.hasViewerPassword() == true) R.string.viewer_password_configured else R.string.viewer_password_unset,
        )
    }

    private fun showViewerPasswordDialog() {
        val connectedService = service ?: return
        viewerPasswordDialog?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun passwordField(resourceId: Int, label: String) = EditText(this).apply {
            id = resourceId
            hint = label
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(ViewerPassword.MAX_LENGTH))
            isSaveEnabled = false
            setSingleLine(true)
            content.addView(this)
        }
        val password = passwordField(R.id.viewerPasswordInput, getString(R.string.viewer_password_new))
        val confirmation = passwordField(R.id.viewerPasswordConfirmation, getString(R.string.viewer_password_confirm))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.viewer_password_title)
            .setMessage(R.string.viewer_password_message)
            .setView(content)
            .setPositiveButton(R.string.viewer_password_save, null)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (connectedService.hasViewerPassword()) {
                    setNeutralButton(R.string.viewer_password_remove) { _, _ ->
                        showRemoveViewerPasswordDialog(connectedService)
                    }
                }
            }
            .create()
        viewerPasswordDialog = dialog
        dialog.setOnDismissListener {
            password.text?.clear()
            confirmation.text?.clear()
            if (viewerPasswordDialog === dialog) viewerPasswordDialog = null
        }
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val value = password.text.toString()
                if (value.length !in ViewerPassword.MIN_LENGTH..ViewerPassword.MAX_LENGTH) {
                    password.error = getString(R.string.viewer_password_length)
                    return@setOnClickListener
                }
                if (value != confirmation.text.toString()) {
                    confirmation.error = getString(R.string.viewer_password_mismatch)
                    return@setOnClickListener
                }
                save.isEnabled = false
                password.isEnabled = false
                confirmation.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
                dialog.setCancelable(false)
                lifecycleScope.launch {
                    try {
                        val verifier = withContext(Dispatchers.Default) { ViewerPassword.create(value) }
                        check(service === connectedService)
                        withContext(Dispatchers.IO) { connectedService.setViewerPassword(verifier) }
                        renderViewerAdmin()
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, R.string.viewer_password_saved, Toast.LENGTH_LONG).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        password.error = getString(R.string.viewer_password_failed)
                    } finally {
                        password.text?.clear()
                        confirmation.text?.clear()
                        save.isEnabled = true
                        password.isEnabled = true
                        confirmation.isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                        dialog.setCancelable(true)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showRemoveViewerPasswordDialog(connectedService: RecordingService) {
        if (service !== connectedService) return
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.viewer_password_remove)
            .setMessage(R.string.viewer_password_remove_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.viewer_password_remove, null)
            .create()
        viewerPasswordDialog = dialog
        dialog.setOnDismissListener {
            if (viewerPasswordDialog === dialog) viewerPasswordDialog = null
        }
        dialog.setOnShowListener {
            val remove = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            remove.setOnClickListener {
                remove.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                lifecycleScope.launch {
                    try {
                        check(service === connectedService)
                        withContext(Dispatchers.IO) { connectedService.setViewerPassword(null) }
                        renderViewerAdmin()
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, R.string.viewer_password_removed, Toast.LENGTH_LONG).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, R.string.viewer_password_failed, Toast.LENGTH_LONG).show()
                    } finally {
                        remove.isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.setCancelable(true)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showViewerPairingCode() {
        val code = service?.createViewerPairingCode()
        if (code == null) {
            Toast.makeText(this, R.string.viewer_pairing_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val codeView = TextView(this).apply {
            text = code.digits
            textSize = 34f
            setTextColor(Color.rgb(194, 220, 172))
            setPadding(dp(24), dp(20), dp(24), dp(16))
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.16f
        }
        val expires = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(code.expiresAtUtc))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.viewer_pair_code_title)
            .setMessage("${getString(R.string.viewer_pair_code_message)}\n\nHết hạn lúc $expires")
            .setView(codeView)
            .setPositiveButton("Sao chép") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Flip Camera pairing code", code.digits))
                Toast.makeText(this, R.string.viewer_pair_code_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    private fun showPendingViewerRequests() {
        val pending = service?.pendingViewerRequests().orEmpty()
        if (pending.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.viewer_request_title)
                .setMessage(R.string.viewer_no_requests)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = pending.map { request ->
            getString(
                R.string.viewer_request_details,
                request.viewerName,
                request.remoteAddress,
                dateFormat.format(Date(request.expiresAtUtc)),
            )
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.viewer_request_title)
            .setItems(labels) { _, index ->
                pending.getOrNull(index)?.let(::showViewerRequestDecision)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showViewerRequestDecision(request: PairingRequestSummary) {
        AlertDialog.Builder(this)
            .setTitle(request.viewerName)
            .setMessage("Địa chỉ yêu cầu: ${request.remoteAddress}")
            .setPositiveButton(R.string.viewer_request_approve) { _, _ ->
                val result = service?.approveViewerRequest(request.requestId)
                handleViewerRequestResult(result)
            }
            .setNegativeButton(R.string.viewer_request_reject) { _, _ ->
                val result = service?.rejectViewerRequest(request.requestId)
                handleViewerRequestResult(result)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun handleViewerRequestResult(result: PairingApproval?) {
        val message = when (result) {
            PairingApproval.APPROVED, PairingApproval.REJECTED -> R.string.viewer_request_handled
            else -> R.string.viewer_request_error
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun observeHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    CameraDatabase.getInstance(applicationContext).segmentDao()
                        .getAllSegmentsFlow().collect { segments ->
                            readySegments = segments.filter { it.status == SegmentStatus.READY }
                            historyLoaded = true
                            historyFailed = false
                            updateHistoryDialog()
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    readySegments = emptyList()
                    historyFailed = true
                    updateHistoryDialog()
                }
            }
        }
    }

    private fun showHistory() {
        if (historyDialog?.isShowing == true) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.history_dialog_hint)
            setTextColor(Color.rgb(171, 184, 169))
            textSize = 13f
        })
        historyStatus = TextView(this).also { content.addView(it) }
        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        historyList = ListView(this).apply {
            adapter = historyAdapter
            setOnItemClickListener { _, _, position, _ ->
                readySegments.getOrNull(position)?.let { openSegment(it) }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                readySegments.getOrNull(position)?.let(::showSegmentActions)
                true
            }
        }.also { content.addView(it, LinearLayout.LayoutParams(-1, dp(320))) }
        historyDialog = AlertDialog.Builder(this)
            .setTitle(R.string.history_dialog_title)
            .setView(content)
            .setPositiveButton(R.string.close, null)
            .create().also { dialog ->
                dialog.setOnDismissListener {
                    historyDialog = null
                    historyStatus = null
                    historyList = null
                    historyAdapter = null
                }
                dialog.show()
            }
        updateHistoryDialog()
    }

    private fun updateHistoryDialog() {
        val adapter = historyAdapter ?: return
        val status = historyStatus ?: return
        val emptyMessage = when {
            historyFailed -> R.string.camera_history_failed
            !historyLoaded -> R.string.camera_history_loading
            else -> R.string.camera_history_empty
        }
        status.setText(emptyMessage)
        status.visibility = if (readySegments.isEmpty()) View.VISIBLE else View.GONE
        historyList?.visibility = if (readySegments.isEmpty()) View.GONE else View.VISIBLE
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        adapter.clear()
        adapter.addAll(readySegments.map { segment ->
            val seconds = (segment.duration / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            getString(
                R.string.camera_history_item,
                segment.internalName,
                dateFormat.format(Date(segment.startUtc)),
                resources.getQuantityString(
                    R.plurals.camera_history_duration,
                    seconds,
                    seconds / 60,
                    seconds % 60,
                ),
                Formatter.formatShortFileSize(this, segment.bytes),
            )
        })
        adapter.notifyDataSetChanged()
    }

    private fun openSegment(segment: SegmentEntity) {
        val authority = packageName + ".segments"
        @Suppress("DEPRECATION")
        val provider = packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
        if (provider == null || provider.packageName != packageName || provider.exported ||
            !provider.grantUriPermissions
        ) {
            showPlaybackMessage(R.string.camera_playback_pending)
            return
        }
        try {
            val root = File(filesDir, "segments").canonicalFile
            val file = File(segment.path).canonicalFile
            if (file.parentFile != root || file.name != segment.internalName ||
                !file.isFile || file.length() <= 0
            ) {
                showPlaybackMessage(R.string.camera_playback_unavailable)
                return
            }
            val uri = FileProvider.getUriForFile(this, authority, file)
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (_: ActivityNotFoundException) {
            showPlaybackMessage(R.string.camera_playback_no_player)
        } catch (_: IllegalArgumentException) {
            showPlaybackMessage(R.string.camera_playback_pending)
        } catch (_: Exception) {
            showPlaybackMessage(R.string.camera_playback_unavailable)
        }
    }

    private fun showSegmentActions(segment: SegmentEntity) {
        val actionIds = buildList {
            add(if (segment.isProtected) R.string.segment_action_unprotect else R.string.segment_action_protect)
            add(R.string.segment_action_export)
            if (!segment.isProtected) add(R.string.segment_action_delete)
        }
        AlertDialog.Builder(this)
            .setTitle(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(segment.startUtc)))
            .setItems(actionIds.map { getString(it) }.toTypedArray()) { _, selected ->
                when (actionIds.getOrNull(selected)) {
                    R.string.segment_action_protect, R.string.segment_action_unprotect -> setSegmentProtected(segment)
                    R.string.segment_action_export -> beginSegmentExport(segment)
                    R.string.segment_action_delete -> confirmDeleteSegment(segment)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun setSegmentProtected(segment: SegmentEntity) {
        lifecycleScope.launch {
            val changed = runCatching {
                withContext(Dispatchers.IO) {
                    val dao = CameraDatabase.getInstance(applicationContext).segmentDao()
                    val latest = dao.getById(segment.id) ?: return@withContext false
                    latest.status == SegmentStatus.READY &&
                        dao.setProtected(
                            latest.id,
                            !latest.isProtected,
                            latest.status,
                            latest.revision,
                        ) == 1
                }
            }.getOrDefault(false)
            Toast.makeText(
                this@MainActivity,
                if (changed) R.string.segment_protection_changed else R.string.segment_protection_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun confirmDeleteSegment(segment: SegmentEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.segment_delete_title)
            .setMessage(R.string.segment_delete_message)
            .setPositiveButton(R.string.segment_action_delete) { _, _ -> deleteSegment(segment) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteSegment(segment: SegmentEntity) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dao = CameraDatabase.getInstance(applicationContext).segmentDao()
                    val latest = dao.getById(segment.id) ?: return@runCatching DeleteResult.NOT_FOUND
                    if (latest.status != SegmentStatus.READY || latest.isProtected) {
                        return@runCatching DeleteResult.NOT_ALLOWED
                    }
                    if (dao.markDeletedWithEvents(latest.id, latest.status, latest.revision) != 1) {
                        return@runCatching DeleteResult.NOT_ALLOWED
                    }
                    val linkedEventsCleanupPending = runCatching {
                        RetentionManager(applicationContext, dao).deleteEventsForSegment(latest.id)
                            .thumbnailDeleteFailures > 0
                    }.getOrDefault(true)
                    try {
                        SegmentFileStore(applicationContext)
                            .deleteReferencedFile(latest.id, latest.internalName, latest.path)
                        if (linkedEventsCleanupPending) DeleteResult.CLEANUP_PENDING else DeleteResult.DELETED
                    } catch (_: Exception) {
                        // The DB tombstone prevents playback; a later retention run safely retries the file cleanup.
                        DeleteResult.CLEANUP_PENDING
                    }
                }.getOrDefault(DeleteResult.FAILED)
            }
            val message = when (result) {
                DeleteResult.DELETED -> R.string.segment_deleted
                DeleteResult.CLEANUP_PENDING -> R.string.segment_delete_cleanup_pending
                DeleteResult.NOT_FOUND, DeleteResult.NOT_ALLOWED, DeleteResult.FAILED -> R.string.segment_delete_failed
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun beginSegmentExport(segment: SegmentEntity) {
        segmentPendingExport = segment
        exportSegmentDocument.launch(
            java.text.SimpleDateFormat("'flip-'yyyy-MM-dd_HH-mm-ss'.mp4'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("GMT+07:00") }
                .format(Date(segment.startUtc)),
        )
    }

    private fun copySegmentToUri(segment: SegmentEntity, destination: Uri) {
        lifecycleScope.launch {
            exportDebugLog("copy start $destination")
            val exported = withContext(Dispatchers.IO) {
                try {
                    val latest = CameraDatabase.getInstance(applicationContext).segmentDao().getById(segment.id)
                        ?: error("Segment no longer exists")
                    check(latest.status == SegmentStatus.READY)
                    val input = SegmentFileStore(applicationContext)
                        .openReadySegment(latest.id, latest.internalName)
                    input.use { source ->
                        exportDebugLog("opening destination")
                        contentResolver.openOutputStream(destination, "w")?.use { output ->
                            exportDebugLog("destination opened; copying")
                            source.copyTo(output)
                        } ?: error("Could not open export destination")
                    }
                    ExportResult.SUCCESS
                } catch (cancelled: CancellationException) {
                    exportDebugLog("export cancelled")
                    deleteExportDestination(destination)
                    throw cancelled
                } catch (failure: Exception) {
                    exportDebugLog("export failed", failure)
                    val deleted = deleteExportDestination(destination)
                    exportDebugLog("cleanup deleted=$deleted")
                    if (deleted) ExportResult.FAILED
                    else ExportResult.PARTIAL_FILE_MAY_REMAIN
                }
            }
            Toast.makeText(
                this@MainActivity,
                when (exported) {
                    ExportResult.SUCCESS -> R.string.segment_exported
                    ExportResult.FAILED -> R.string.segment_export_failed
                    ExportResult.PARTIAL_FILE_MAY_REMAIN -> R.string.segment_export_failed_partial
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun deleteExportDestination(destination: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }.getOrDefault(false) ||
            runCatching { contentResolver.delete(destination, null, null) > 0 }.getOrDefault(false)

    private fun exportDebugLog(message: String, failure: Throwable? = null) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (failure == null) Log.d("CameraExport", message)
        else Log.e("CameraExport", message, failure)
    }

    private fun showRetentionOptions() {
        val choices = arrayOf("1 ngày", "3 ngày", "7 ngày", "14 ngày", "30 ngày")
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val description = TextView(this).apply {
            text = getString(R.string.retention_message)
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val options = ListView(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_1,
                choices,
            )
        }
        content.addView(
            description,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            options,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.retention_title)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .create()
        options.setOnItemClickListener { _, _, index, _ ->
            dialog.dismiss()
            listOf(1, 3, 7, 14, 30).getOrNull(index)?.let(::previewRetention)
        }
        dialog.show()
    }

    private fun showMotionEvents() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val events = runCatching {
                withContext(Dispatchers.IO) {
                    CameraDatabase.getInstance(applicationContext).motionEventDao()
                        .getTimeline((now - 7L * 24L * 60L * 60L * 1_000L).coerceAtLeast(0L), now, limit = 200)
                }
            }.getOrElse {
                Toast.makeText(this@MainActivity, R.string.camera_history_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (events.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.motion_events_title)
                    .setMessage(R.string.motion_events_empty)
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@launch
            }
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val labels = events.map { event ->
                getString(
                    R.string.motion_event_item,
                    dateFormat.format(Date(event.startUtc)),
                    event.peakScore * 100f,
                )
            }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.motion_events_title)
                .setItems(labels) { _, position ->
                    events.getOrNull(position)?.let(::openMotionEventSegment)
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun openMotionEventSegment(event: MotionEventEntity) {
        lifecycleScope.launch {
            val segment = withContext(Dispatchers.IO) {
                val segments = CameraDatabase.getInstance(applicationContext).segmentDao()
                    .getReadyTimeline(event.startUtc, event.endUtc, limit = 200)
                segments.firstOrNull { it.id == event.segmentId } ?: segments.firstOrNull()
            }
            if (event.status != MotionEventStatus.READY || segment == null) {
                Toast.makeText(this@MainActivity, R.string.motion_event_no_segment, Toast.LENGTH_SHORT).show()
            } else {
                openSegment(segment)
            }
        }
    }

    private fun previewRetention(days: Int) {
        lifecycleScope.launch {
            val candidateCounts = withContext(Dispatchers.IO) {
                runCatching {
                    val cutoffUtc = retentionCutoff(days)
                    val database = CameraDatabase.getInstance(applicationContext)
                    val videoCount = database.segmentDao().getExpiredSegments(cutoffUtc).size
                    val eventCount = database.motionEventDao().getExpired(cutoffUtc, limit = 500).size
                    videoCount to eventCount
                }.getOrNull()
            }
            if (candidateCounts == null) {
                Toast.makeText(this@MainActivity, R.string.retention_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val videoCountLabel = if (candidateCounts.first >= 500) "500+" else candidateCounts.first.toString()
            val eventCountLabel = if (candidateCounts.second >= 500) "500+" else candidateCounts.second.toString()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.retention_title)
                .setMessage(getString(R.string.retention_confirm_message, videoCountLabel, eventCountLabel, days))
                .setPositiveButton(R.string.retention_run) { _, _ -> applyRetention(days) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun applyRetention(days: Int) {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    RetentionManager(
                        applicationContext,
                        CameraDatabase.getInstance(applicationContext).segmentDao(),
                    ).enforceAge(retentionCutoff(days))
                }
            }
            val message = report.fold(
                onSuccess = {
                    getString(
                        R.string.retention_complete,
                        it.filesDeleted,
                        it.tombstonedEvents,
                        it.fileDeleteFailures + it.thumbnailDeleteFailures,
                    )
                },
                onFailure = { getString(R.string.retention_failed) },
            )
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun retentionCutoff(days: Int): Long =
        System.currentTimeMillis() - days * 24L * 60L * 60L * 1_000L

    private fun showPlaybackMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private enum class DeleteResult { DELETED, CLEANUP_PENDING, NOT_FOUND, NOT_ALLOWED, FAILED }

    private enum class ExportResult { SUCCESS, FAILED, PARTIAL_FILE_MAY_REMAIN }
}

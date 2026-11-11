package com.phamdu.flipviewer

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.phamdu.flipviewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy {
        getSharedPreferences("flip_viewer_prefs", MODE_PRIVATE)
    }

    /** Chứng chỉ tự ký của Flip3 mà WebView đã nhận; tải tệp chỉ tin đúng chứng chỉ này. */
    @Volatile private var pinnedServerCert: ByteArray? = null

    private val updater by lazy {
        AppUpdater(
            activity = this,
            baseUrl = {
                runCatching { java.net.URL(binding.webView.url ?: "").let { "${it.protocol}://${it.authority}/" } }.getOrNull()
            },
            pinnedCert = { pinnedServerCert },
            cookieFor = { CookieManager.getInstance().getCookie(it) },
        )
    }
    private var lastAutoUpdateCheck = 0L

    private var currentStatus = Status.CONNECTING
    private var isFullscreenMode = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private enum class Status {
        CONNECTING,
        CONNECTED,
        ERROR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on for continuous camera monitoring
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable Chrome DevTools inspection for debug builds
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setupWebView()
        setupListeners()
        setupBackNavigation()

        loadServerUrl(getServerUrl())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        // Enable cookies and session tracking
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            displayZoomControls = false
            builtInZoomControls = false
            setSupportZoom(false)
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadFile(url, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // Crucial: accept self-signed certificates from the Flip3 Ktor HTTPS server
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    pinnedServerCert = error?.certificate?.x509Certificate?.encoded
                }
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutErrorOverlay.visibility = View.GONE
                updateStatus(Status.CONNECTING)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                if (currentStatus != Status.ERROR) {
                    updateStatus(Status.CONNECTED)
                }
                CookieManager.getInstance().flush()
                val now = android.os.SystemClock.elapsedRealtime()
                if (currentStatus != Status.ERROR && now - lastAutoUpdateCheck > 10 * 60_000L) {
                    lastAutoUpdateCheck = now
                    updater.check(userInitiated = false)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    updateStatus(Status.ERROR)
                    binding.layoutErrorOverlay.visibility = View.VISIBLE
                    binding.etServerUrl.setText(getServerUrl())
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                // Grant media/audio permissions if requested by web elements
                request?.grant(request.resources)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback

                view?.let {
                    binding.fullscreenContainer.addView(
                        it,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.contentFrame.visibility = View.GONE
                binding.topBar.visibility = View.GONE
                applyImmersiveSystemBars(true)
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                binding.contentFrame.visibility = View.VISIBLE

                if (!isFullscreenMode) {
                    binding.topBar.visibility = View.VISIBLE
                    applyImmersiveSystemBars(false)
                }

                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }
        }
    }

    /**
     * WebView không tự tải tệp. Tải bằng kết nối riêng, mang theo cookie phiên, chỉ tới đúng
     * máy chủ Flip3 đang xem và chỉ tin đúng chứng chỉ tự ký mà WebView đã nhận (không dùng
     * DownloadManager vì nó không tin chứng chỉ tự ký). Video lưu vào Movies/Flip Camera (hiện trong Thư viện).
     */
    private fun downloadFile(url: String, contentDisposition: String?, mimeType: String?) {
        val target = try { java.net.URL(url) } catch (_: Exception) { return }
        val pageHost = try { java.net.URL(binding.webView.url ?: "").host } catch (_: Exception) { "" }
        if (target.protocol != "https" || target.host != pageHost) {
            toast("Chỉ tải từ máy chủ Flip3 đang xem.")
            return
        }
        val pinned = pinnedServerCert
        if (pinned == null) {
            toast("Chưa có chứng chỉ máy chủ. Bấm tải lại trang rồi thử lại.")
            return
        }
        val cookie = CookieManager.getInstance().getCookie(url)
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType ?: "video/mp4")
        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("Đang tải video về máy")
            setMessage(fileName)
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            max = 100
            setCancelable(false)
            show()
        }
        Thread {
            val message = try {
                val connection = PinnedHttps.open(target, pinned, pageHost, cookie, readTimeoutMs = 60_000)
                try {
                    if (connection.responseCode != 200) {
                        "Tải thất bại (mã ${connection.responseCode}). Hãy đăng nhập lại."
                    } else {
                        val total = connection.contentLengthLong
                        saveToDownloads(fileName, connection.contentType ?: mimeType ?: "video/mp4", connection.inputStream) { done ->
                            runOnUiThread {
                                if (total > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = (done * 100 / total).toInt().coerceIn(0, 100)
                                    progress.setMessage("$fileName\n${done / 1_000_000} / ${total / 1_000_000} MB")
                                } else {
                                    progress.setMessage("$fileName\n${done / 1_000_000} MB")
                                }
                            }
                        }
                        "Đã tải xong: $fileName (Thư viện > Movies/Flip Camera)."
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (failure: Exception) {
                "Tải thất bại: ${failure.javaClass.simpleName}"
            }
            runOnUiThread {
                progress.dismiss()
                toast(message)
            }
        }.start()
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun saveToDownloads(
        fileName: String,
        mime: String,
        input: java.io.InputStream,
        onProgress: (Long) -> Unit,
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Video lưu vào Movies/Flip Camera để hiện trong Thư viện/Ảnh; tệp khác vào Download.
            val isVideo = mime.startsWith("video/")
            val collection = if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) "Movies/Flip Camera" else "Download")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values)
                ?: error("Không tạo được tệp")
            try {
                contentResolver.openOutputStream(uri)?.use { out -> input.use { copyWithProgress(it, out, onProgress) } }
                    ?: error("Không mở được tệp")
                contentResolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            } catch (failure: Exception) {
                contentResolver.delete(uri, null, null)
                throw failure
            }
        } else {
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: error("Không có bộ nhớ")
            java.io.File(dir, fileName).outputStream().use { out -> input.use { copyWithProgress(it, out, onProgress) } }
        }
    }

    private fun copyWithProgress(input: java.io.InputStream, out: java.io.OutputStream, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            done += read
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastReport >= 200) {
                lastReport = now
                onProgress(done)
            }
        }
        onProgress(done)
    }

    private fun setupListeners() {
        binding.btnUpdate.setOnClickListener { updater.check(userInitiated = true) }

        binding.btnReload.setOnClickListener {
            binding.webView.reload()
        }

        binding.btnEditServer.setOnClickListener {
            showEditServerDialog()
        }

        binding.tvServerBadge.setOnClickListener {
            showEditServerDialog()
        }

        binding.btnFullscreen.setOnClickListener {
            setFullscreen(true)
        }

        binding.btnFloatingExitFullscreen.setOnClickListener {
            setFullscreen(false)
        }

        // Error overlay actions
        binding.btnRetry.setOnClickListener {
            val edited = binding.etServerUrl.text.toString().trim()
            if (edited.isNotEmpty()) {
                saveServerUrl(edited)
            }
            loadServerUrl(getServerUrl())
        }

        binding.btnResetDefault.setOnClickListener {
            val defaultUrl = ServerUrlUtils.DEFAULT_SERVER_URL
            binding.etServerUrl.setText(defaultUrl)
            saveServerUrl(defaultUrl)
            loadServerUrl(defaultUrl)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> {
                        binding.webView.webChromeClient?.onHideCustomView()
                    }
                    isFullscreenMode -> {
                        setFullscreen(false)
                    }
                    binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun setFullscreen(enabled: Boolean) {
        isFullscreenMode = enabled
        binding.topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.btnFloatingExitFullscreen.visibility = if (enabled) View.VISIBLE else View.GONE
        applyImmersiveSystemBars(enabled)
    }

    private fun applyImmersiveSystemBars(hide: Boolean) {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateStatus(status: Status) {
        currentStatus = status
        when (status) {
            Status.CONNECTING -> {
                binding.viewStatusDot.background = ContextCompat.getDrawable(this, R.drawable.bg_status_dot)?.apply {
                    setTint(ContextCompat.getColor(this@MainActivity, R.color.viewer_status_connecting))
                }
                binding.tvStatusText.text = getString(R.string.status_connecting)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.viewer_status_connecting))
            }
            Status.CONNECTED -> {
                binding.viewStatusDot.background = ContextCompat.getDrawable(this, R.drawable.bg_status_dot)?.apply {
                    setTint(ContextCompat.getColor(this@MainActivity, R.color.viewer_status_connected))
                }
                binding.tvStatusText.text = getString(R.string.status_connected)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.viewer_status_connected))
            }
            Status.ERROR -> {
                binding.viewStatusDot.background = ContextCompat.getDrawable(this, R.drawable.bg_status_dot)?.apply {
                    setTint(ContextCompat.getColor(this@MainActivity, R.color.viewer_status_error))
                }
                binding.tvStatusText.text = getString(R.string.status_error)
                binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.viewer_status_error))
            }
        }
    }

    private fun getServerUrl(): String {
        return prefs.getString("server_url", ServerUrlUtils.DEFAULT_SERVER_URL)
            ?: ServerUrlUtils.DEFAULT_SERVER_URL
    }

    private fun saveServerUrl(url: String) {
        val normalized = ServerUrlUtils.normalizeUrl(url)
        prefs.edit().putString("server_url", normalized).apply()
    }

    private fun loadServerUrl(url: String) {
        val normalized = ServerUrlUtils.normalizeUrl(url)
        binding.tvServerBadge.text = ServerUrlUtils.formatBadge(normalized)
        binding.layoutErrorOverlay.visibility = View.GONE
        binding.webView.loadUrl(normalized)
    }

    private fun showEditServerDialog() {
        val dialog = Dialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_server, null)
        dialog.setContentView(dialogView)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt().coerceAtMost(600),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etUrl = dialogView.findViewById<EditText>(R.id.dialogEtServerUrl)
        val btnCancel = dialogView.findViewById<Button>(R.id.dialogBtnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.dialogBtnSave)

        etUrl.setText(getServerUrl())

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val input = etUrl.text.toString().trim()
            if (input.isNotEmpty()) {
                saveServerUrl(input)
                loadServerUrl(getServerUrl())
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}

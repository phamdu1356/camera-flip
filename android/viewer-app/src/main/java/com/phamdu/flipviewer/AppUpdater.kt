package com.phamdu.flipviewer

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** HTTPS tới đúng máy chủ Flip3: chỉ tin đúng chứng chỉ tự ký mà WebView đã nhận, không tin mọi chứng chỉ. */
internal object PinnedHttps {
    fun open(url: URL, pinnedCert: ByteArray, expectedHost: String, cookie: String?, readTimeoutMs: Int = 30_000): HttpsURLConnection {
        require(url.protocol == "https" && url.host == expectedHost) { "unexpected target" }
        val trust = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) =
                throw CertificateException("no client auth")
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {
                if (c.isNullOrEmpty() || !c[0].encoded.contentEquals(pinnedCert)) {
                    throw CertificateException("certificate mismatch")
                }
            }
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trust, null) }
        return (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ssl.socketFactory
            hostnameVerifier = HostnameVerifier { host, _ -> host == expectedHost }
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
        }
    }
}

internal data class RemoteVersion(val versionCode: Long, val versionName: String, val sha256: String, val size: Long)

/** Pure decision so it can be unit tested: never downgrade; same versionCode updates only if the bytes differ. */
internal fun isUpdateAvailable(installedCode: Long, installedSha256: String?, remote: RemoteVersion): Boolean =
    remote.versionCode > installedCode ||
        (remote.versionCode == installedCode && !installedSha256.equals(remote.sha256, ignoreCase = true))

internal fun parseRemoteVersion(json: String): RemoteVersion? = runCatching {
    val o = JSONObject(json)
    val sha = o.getString("sha256")
    require(Regex("[0-9a-fA-F]{64}").matches(sha))
    RemoteVersion(o.getLong("versionCode"), o.optString("versionName"), sha.lowercase(), o.optLong("size"))
}.getOrNull()

internal fun sha256Hex(stream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Tự cập nhật không dùng ADB: hỏi Flip3 bản mới (/api/viewer-app/version, cần phiên đăng nhập),
 * tải /api/viewer-app.apk qua HTTPS ghim chứng chỉ, kiểm SHA-256, rồi mở trình cài của Android.
 */
internal class AppUpdater(
    private val activity: Activity,
    private val baseUrl: () -> String?,
    private val pinnedCert: () -> ByteArray?,
    private val cookieFor: (String) -> String?,
) {
    @Volatile private var busy = false

    fun check(userInitiated: Boolean) {
        if (busy) return
        val base = baseUrl() ?: return
        val cert = pinnedCert()
        if (cert == null) {
            if (userInitiated) toast("Chưa kết nối an toàn tới Flip3. Tải lại trang rồi thử lại.")
            return
        }
        busy = true
        Thread {
            var dialog: (() -> Unit)? = null
            try {
                val target = URL(URL(base), "/api/viewer-app/version")
                val connection = PinnedHttps.open(target, cert, target.host, cookieFor(target.toString()))
                try {
                    when (connection.responseCode) {
                        200 -> {
                            val remote = parseRemoteVersion(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                            if (remote == null) {
                                if (userInitiated) post { toast("Phản hồi cập nhật không hợp lệ.") }
                            } else {
                                val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                                val installedCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                                val installedSha = runCatching {
                                    File(activity.applicationInfo.sourceDir).inputStream().use { sha256Hex(it) }
                                }.getOrNull()
                                if (isUpdateAvailable(installedCode, installedSha, remote)) {
                                    dialog = { askToUpdate(base, cert, remote) }
                                } else if (userInitiated) {
                                    post { toast("Bạn đang dùng bản mới nhất (${remote.versionName}).") }
                                }
                            }
                        }
                        401 -> if (userInitiated) post { toast("Hãy đăng nhập vào Flip3 trước rồi kiểm tra lại.") }
                        else -> if (userInitiated) post { toast("Flip3 chưa có bản cập nhật (mã ${connection.responseCode}).") }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (failure: Exception) {
                if (userInitiated) post { toast("Không kiểm tra được cập nhật: ${failure.javaClass.simpleName}") }
            } finally {
                busy = false
            }
            dialog?.let { post(it) }
        }.start()
    }

    private fun askToUpdate(base: String, cert: ByteArray, remote: RemoteVersion) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Có bản cập nhật Flip Viewer")
            .setMessage("Phiên bản ${remote.versionName} (${remote.size / 1_000_000} MB) có trên Flip3. Tải và cài đặt bây giờ?")
            .setPositiveButton("Cập nhật") { _, _ -> download(base, cert, remote) }
            .setNegativeButton("Để sau", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun download(base: String, cert: ByteArray, remote: RemoteVersion) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast("Hãy bật \"Cho phép cài ứng dụng từ nguồn này\" cho Flip Viewer, rồi bấm cập nhật lại.")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return
        }
        val progress = ProgressDialog(activity).apply {
            setTitle("Đang tải bản cập nhật")
            setMessage("Flip Viewer ${remote.versionName}")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            max = 100
            setCancelable(false)
            show()
        }
        busy = true
        Thread {
            var apk: File? = null
            val message: String? = try {
                val target = URL(URL(base), "/api/viewer-app.apk")
                val dir = File(activity.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "flip-viewer-update.apk")
                val connection = PinnedHttps.open(target, cert, target.host, cookieFor(target.toString()))
                try {
                    if (connection.responseCode != 200) {
                        "Tải cập nhật thất bại (mã ${connection.responseCode})."
                    } else {
                        val total = connection.contentLengthLong
                        val digest = MessageDigest.getInstance("SHA-256")
                        var done = 0L
                        var last = 0L
                        connection.inputStream.use { input ->
                            out.outputStream().use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    sink.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    done += read
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - last >= 200) {
                                        last = now
                                        val d = done
                                        post {
                                            if (total > 0) {
                                                progress.isIndeterminate = false
                                                progress.progress = (d * 100 / total).toInt().coerceIn(0, 100)
                                                progress.setMessage("${d / 1_000_000} / ${total / 1_000_000} MB")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(remote.sha256, ignoreCase = true)) {
                            out.delete()
                            "Tệp cập nhật không khớp mã SHA-256, đã hủy."
                        } else {
                            apk = out
                            null
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (failure: Exception) {
                "Tải cập nhật thất bại: ${failure.javaClass.simpleName}"
            } finally {
                busy = false
            }
            post {
                progress.dismiss()
                if (message != null) toast(message) else apk?.let { launchInstaller(it) }
            }
        }.start()
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        toast("Đã tải xong và đúng mã SHA-256. Bấm \"Cài đặt\" khi hệ thống hỏi.")
        activity.startActivity(intent)
    }

    private fun post(block: () -> Unit) = activity.runOnUiThread(block)
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}

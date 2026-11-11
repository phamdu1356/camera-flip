package com.phamdu.flipcamera.security

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.json.JSONObject

/**
 * Read-only, session-protected distribution of the Flip Viewer app APK that is bundled into this
 * APK at build time (assets viewer-app.apk + viewer-app.json). The viewer app compares the
 * metadata with its own install and downloads the file over the pinned HTTPS channel.
 */
internal class ViewerAppUpdateApi(
    private val pairing: ViewerPairingManager,
    private val appContext: Context,
) {
    fun install(application: Application) {
        application.routing {
            get("/api/viewer-app/version") {
                if (!call.requireViewerSession(pairing)) return@get
                val meta = readMetadata()
                call.noStore()
                if (meta == null) {
                    call.respondText("""{"error":"viewer_app_unavailable"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                } else {
                    call.respondText(meta.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
            get("/api/viewer-app.apk") {
                if (!call.requireViewerSession(pairing)) return@get
                val size = runCatching { appContext.assets.openFd(APK_ASSET).use { it.length } }
                    .getOrElse { runCatching { appContext.assets.open(APK_ASSET).use { s -> countBytes(s) } }.getOrNull() }
                if (size == null || size <= 0L) {
                    call.noStore()
                    call.respondText("""{"error":"viewer_app_unavailable"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@get
                }
                call.noStore()
                call.response.headers.append(HttpHeaders.ContentLength, size.toString())
                call.respondOutputStream(APK_CONTENT_TYPE, HttpStatusCode.OK, size) {
                    appContext.assets.open(APK_ASSET).use { it.copyTo(this, 64 * 1024) }
                }
            }
        }
    }

    private fun io.ktor.server.application.ApplicationCall.noStore() {
        response.headers.append(HttpHeaders.CacheControl, "private, no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        response.headers.append("Referrer-Policy", "no-referrer")
    }

    private fun readMetadata(): JSONObject? = runCatching {
        JSONObject(appContext.assets.open(META_ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrNull()

    private fun countBytes(stream: java.io.InputStream): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return total
            total += read
        }
    }

    private companion object {
        const val APK_ASSET = "viewer-app.apk"
        const val META_ASSET = "viewer-app.json"
        val APK_CONTENT_TYPE: ContentType = ContentType.parse("application/vnd.android.package-archive")
    }
}

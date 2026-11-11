package com.phamdu.flipcamera.security

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Serves a fixed allowlist of viewer assets from the APK; no client path reaches AssetManager. */
internal class ViewerWebAssets(context: Context) {
    private val appContext = context.applicationContext

    fun install(application: Application) {
        application.routing {
            get("/") { call.respondViewerAsset("index.html", "text/html; charset=utf-8") }
            get("/viewer.css") { call.respondViewerAsset("viewer.css", "text/css; charset=utf-8") }
            get("/viewer.js") { call.respondViewerAsset("viewer.js", "text/javascript; charset=utf-8") }
            get("/assets/fonts.css") { call.respondViewerAsset("assets/fonts.css", "text/css; charset=utf-8") }
            get("/assets/favicon.svg") { call.respondViewerAsset("assets/favicon.svg", "image/svg+xml") }
            get("/assets/manrope-0.ttf") { call.respondViewerAsset("assets/manrope-0.ttf", "font/ttf") }
            get("/assets/manrope-1.ttf") { call.respondViewerAsset("assets/manrope-1.ttf", "font/ttf") }
            get("/assets/manrope-2.ttf") { call.respondViewerAsset("assets/manrope-2.ttf", "font/ttf") }
            get("/assets/manrope-3.ttf") { call.respondViewerAsset("assets/manrope-3.ttf", "font/ttf") }
            get("/assets/manrope-4.ttf") { call.respondViewerAsset("assets/manrope-4.ttf", "font/ttf") }
        }
    }

    private suspend fun ApplicationCall.respondViewerAsset(path: String, mimeType: String) {
        val bytes = runCatching { appContext.assets.open(path).use { it.readBytes() } }
            .getOrNull()
        if (bytes == null) {
            respond(HttpStatusCode.NotFound)
            return
        }
        response.headers.append("Cache-Control", "no-store")
        response.headers.append("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        response.headers.append("X-Content-Type-Options", "nosniff")
        response.headers.append("X-Frame-Options", "DENY")
        response.headers.append("Referrer-Policy", "no-referrer")
        response.headers.append("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        respondBytes(bytes, ContentType.parse(mimeType), HttpStatusCode.OK)
    }

    private companion object {
        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; base-uri 'self'; form-action 'self'; script-src 'self'; style-src 'self'; " +
                "connect-src 'self'; img-src 'self' data:; font-src 'self'; media-src 'self' blob:; " +
                "object-src 'none'; frame-ancestors 'none'"
    }
}

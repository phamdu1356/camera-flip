package com.phamdu.flipcamera.security

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/** Trạng thái và điều khiển tối thiểu của Flip3 cho viewer: nhiệt độ pin và đèn flash (torch). */
internal interface ViewerDeviceControl {
    /** Nhiệt độ pin °C, null nếu chưa đọc được. */
    fun batteryTemperatureC(): Float?
    /** Camera đã bind có đèn flash hay không. */
    fun torchAvailable(): Boolean
    fun torchOn(): Boolean
    /** Yêu cầu bật/tắt flash; false nếu không có flash hoặc camera chưa sẵn sàng. */
    fun setTorch(on: Boolean): Boolean
}

internal fun deviceStatusJson(control: ViewerDeviceControl, live: LiveStreamStats.Snapshot? = null): String {
    val temp = control.batteryTemperatureC()
    val tempJson = if (temp == null || temp.isNaN() || temp <= 0f) "null" else String.format(java.util.Locale.US, "%.1f", temp)
    val liveJson = if (live == null) "" else String.format(java.util.Locale.US, ""","liveFps":%.1f,"liveKbps":%d""", live.fps, live.kbps)
    return """{"batteryTempC":$tempJson,"torchAvailable":${control.torchAvailable()},"torchOn":${control.torchOn()}$liveJson}"""
}

/**
 * API cho viewer đã đăng nhập: GET /api/device-status, POST /api/torch?on=1|0.
 * POST bắt buộc cùng origin HTTPS (chống CSRF) và phiên hợp lệ; không điều khiển gì khác.
 */
internal class ViewerDeviceApi(
    private val pairing: ViewerPairingManager,
    private val control: ViewerDeviceControl,
    private val liveStats: LiveStreamStats? = null,
) {
    fun install(application: Application) {
        application.routing {
            get("/api/device-status") {
                if (!call.requireViewerSession(pairing)) return@get
                call.noStore()
                val token = call.request.cookies[VIEWER_SESSION_COOKIE]
                val live = if (token != null) liveStats?.snapshot(token, android.os.SystemClock.elapsedRealtime()) else null
                call.respondText(deviceStatusJson(control, live), ContentType.Application.Json, HttpStatusCode.OK)
            }
            post("/api/torch") {
                if (!call.requireViewerSession(pairing)) return@post
                call.noStore()
                if (call.request.headers[HttpHeaders.Origin] == null || !call.hasSameSecureOrigin()) {
                    call.respondText("""{"error":"origin_rejected"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return@post
                }
                val on = when (call.request.queryParameters["on"]) {
                    "1" -> true
                    "0" -> false
                    else -> {
                        call.respondText("""{"error":"invalid_request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                }
                if (!control.setTorch(on)) {
                    call.respondText("""{"error":"torch_unavailable"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                call.respondText(deviceStatusJson(control), ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
    }

    private fun ApplicationCall.noStore() {
        response.header(HttpHeaders.CacheControl, "private, no-store, no-cache, max-age=0")
        response.header("X-Content-Type-Options", "nosniff")
    }
}

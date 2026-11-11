package com.phamdu.flipcamera.security

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveParameters
import io.ktor.server.plugins.origin
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pairing and session endpoints. Media routes are installed separately after pairing. */
internal class ViewerApi(private val pairing: ViewerPairingManager) {
    fun install(application: Application) {
        application.routing {
            get("/api/auth/options") {
                call.respondApiJson(HttpStatusCode.OK, """{"passwordEnabled":${pairing.hasPassword()}}""")
            }

            post("/api/login") {
                if (!call.requireSameOrigin()) return@post
                val form = call.receiveBoundedForm() ?: return@post
                val password = form.single("password", LOGIN_FORM_FIELDS) ?: return@post call.respondApiError(
                    HttpStatusCode.BadRequest, "invalid_request",
                )
                val name = form.single("deviceName", LOGIN_FORM_FIELDS) ?: return@post call.respondApiError(
                    HttpStatusCode.BadRequest, "invalid_request",
                )
                when (val result = withContext(Dispatchers.Default) { pairing.loginPassword(password, name) }) {
                    is PasswordLogin.Approved -> {
                        call.response.headers.append(
                            HttpHeaders.SetCookie,
                            "$SESSION_COOKIE=${result.sessionToken}; Max-Age=${SESSION_COOKIE_MAX_AGE_SECONDS}; Path=/; Secure; HttpOnly; SameSite=Strict",
                        )
                        call.respondApiJson(HttpStatusCode.OK, """{"status":"approved","readOnly":true}""")
                    }
                    PasswordLogin.InvalidPassword -> call.respondApiError(HttpStatusCode.Unauthorized, "invalid_password")
                    PasswordLogin.NotConfigured -> call.respondApiError(HttpStatusCode.Conflict, "password_not_configured")
                    PasswordLogin.Busy -> {
                        call.response.headers.append(HttpHeaders.RetryAfter, "1")
                        call.respondApiError(HttpStatusCode.TooManyRequests, "login_busy")
                    }
                    PasswordLogin.RateLimited -> call.respondApiError(HttpStatusCode.TooManyRequests, "rate_limited")
                    PasswordLogin.CapacityReached -> call.respondApiError(HttpStatusCode.Conflict, "viewer_capacity_reached")
                }
            }

            post("/api/pair/request") {
                if (!call.requireSameOrigin()) return@post
                val form = call.receiveBoundedForm() ?: return@post
                val code = form.single("code", REQUEST_FORM_FIELDS) ?: return@post call.respondApiError(
                    HttpStatusCode.BadRequest, "invalid_request",
                )
                val name = form.single("deviceName", REQUEST_FORM_FIELDS) ?: return@post call.respondApiError(
                    HttpStatusCode.BadRequest, "invalid_request",
                )
                when (val result = pairing.submitPairingCode(code, call.request.origin.remoteHost, name)) {
                    is PairingSubmission.Waiting -> call.respondApiJson(
                        HttpStatusCode.Accepted,
                        """{"status":"pending","requestId":"${result.requestId}"}""",
                    )
                    PairingSubmission.InvalidCode,
                    PairingSubmission.ExpiredCode -> call.respondApiError(HttpStatusCode.Unauthorized, "invalid_pairing_code")
                    PairingSubmission.RateLimited -> call.respondApiError(HttpStatusCode.TooManyRequests, "rate_limited")
                    PairingSubmission.CapacityReached -> call.respondApiError(HttpStatusCode(409, "Conflict"), "viewer_capacity_reached")
                }
            }

            post("/api/pair/status") {
                if (!call.requireSameOrigin()) return@post
                val form = call.receiveBoundedForm() ?: return@post
                val requestId = form.single("requestId", STATUS_FORM_FIELDS) ?: return@post call.respondApiError(
                    HttpStatusCode.BadRequest, "invalid_request",
                )
                when (val result = pairing.poll(requestId)) {
                    PairingPoll.Waiting -> call.respondApiJson(
                        HttpStatusCode.Accepted,
                        """{"status":"pending"}""",
                    )
                    is PairingPoll.Approved -> {
                        call.response.headers.append(
                            HttpHeaders.SetCookie,
                            "$SESSION_COOKIE=${result.sessionToken}; Max-Age=${SESSION_COOKIE_MAX_AGE_SECONDS}; Path=/; Secure; HttpOnly; SameSite=Strict",
                        )
                        call.respondApiJson(HttpStatusCode.OK, """{"status":"approved"}""")
                    }
                    PairingPoll.Completed -> call.respondApiError(HttpStatusCode.Conflict, "pairing_complete")
                    PairingPoll.Rejected -> call.respondApiError(HttpStatusCode.Forbidden, "pairing_rejected")
                    PairingPoll.Expired -> call.respondApiError(HttpStatusCode.Gone, "pairing_expired")
                    PairingPoll.Unknown -> call.respondApiError(HttpStatusCode.NotFound, "pairing_not_found")
                }
            }

            get("/api/status") {
                val token = call.request.cookies[SESSION_COOKIE]
                if (token == null || pairing.authenticate(token) == null) {
                    call.respondApiError(HttpStatusCode.Unauthorized, "unauthorized")
                } else {
                    call.respondApiJson(HttpStatusCode.OK, """{"status":"ok","readOnly":true}""")
                }
            }

            post("/api/logout") {
                if (!call.requireSameOrigin()) return@post
                if (call.request.queryString().isNotEmpty()) {
                    return@post call.respondApiError(HttpStatusCode.BadRequest, "secrets_must_not_be_in_url")
                }
                val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if ((contentLength ?: 0L) > 0L || HttpHeaders.TransferEncoding in call.request.headers) {
                    return@post call.respondApiError(HttpStatusCode(413, "Content Too Large"), "body_not_allowed")
                }
                call.request.cookies[SESSION_COOKIE]?.let(pairing::logout)
                call.response.headers.append(
                    HttpHeaders.SetCookie,
                    "$SESSION_COOKIE=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Strict",
                )
                call.respondApiJson(HttpStatusCode.NoContent, "")
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.requireSameOrigin(): Boolean {
        if (hasSameSecureOrigin()) return true
        respondApiError(HttpStatusCode.Forbidden, "origin_rejected")
        return false
    }

    private suspend fun io.ktor.server.application.ApplicationCall.receiveBoundedForm(): Map<String, List<String>>? {
        if (request.queryString().isNotEmpty()) {
            respondApiError(HttpStatusCode.BadRequest, "secrets_must_not_be_in_url")
            return null
        }
        val contentType = request.headers[HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?: run {
                respondApiError(HttpStatusCode.UnsupportedMediaType, "form_required")
                return null
            }
        if (!contentType.equals(ContentType.Application.FormUrlEncoded.toString(), ignoreCase = true)) {
            respondApiError(HttpStatusCode.UnsupportedMediaType, "form_required")
            return null
        }
        val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength == null || contentLength !in 1..MAX_FORM_BYTES) {
            respondApiError(HttpStatusCode(413, "Content Too Large"), "invalid_body_size")
            return null
        }
        val parameters = runCatching { receiveParameters() }.getOrElse {
            respondApiError(HttpStatusCode.BadRequest, "invalid_form")
            return null
        }
        return parameters.entries().associate { (key, values) -> key to values }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondApiError(
        status: HttpStatusCode,
        code: String,
    ) = respondApiJson(status, """{"error":"$code"}""")

    private suspend fun io.ktor.server.application.ApplicationCall.respondApiJson(
        status: HttpStatusCode,
        body: String,
    ) {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        response.headers.append("Referrer-Policy", "no-referrer")
        respondText(body, ContentType.Application.Json, status)
    }

    private fun Map<String, List<String>>.single(name: String, expectedFields: Set<String>): String? {
        if (keys != expectedFields) return null
        return this[name]?.singleOrNull()
    }

    private companion object {
        const val SESSION_COOKIE = VIEWER_SESSION_COOKIE
        const val SESSION_COOKIE_MAX_AGE_SECONDS = ViewerPairingManager.ABSOLUTE_SESSION_TTL_MS / 1_000
        const val MAX_FORM_BYTES = 2_048L
        val REQUEST_FORM_FIELDS = setOf("code", "deviceName")
        val STATUS_FORM_FIELDS = setOf("requestId")
        val LOGIN_FORM_FIELDS = setOf("password", "deviceName")
    }
}

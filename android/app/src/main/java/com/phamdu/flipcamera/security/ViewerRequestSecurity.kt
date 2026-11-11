package com.phamdu.flipcamera.security

import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import java.net.URI

internal data class HostAuthority(val host: String, val port: Int?)

internal const val VIEWER_SESSION_COOKIE = "__Host-flip_viewer_session"

/** Authenticates a read-only viewer route before it touches media or storage. */
internal suspend fun ApplicationCall.requireViewerSession(pairing: ViewerPairingManager): Boolean {
    val token = request.cookies[VIEWER_SESSION_COOKIE]
    if (token != null && pairing.authenticate(token) != null) return true
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
    respondText(
        """{"error":"unauthorized"}""",
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
    return false
}

/** Parses only a hostname/IP and optional decimal TCP port; rejects ambiguous authorities. */
internal fun parseHostAuthority(authority: String?): HostAuthority? {
    val value = authority?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.any { it.isWhitespace() || it in "/\\?#@" }) return null

    val parsedHost: String
    val parsedPort: Int?
    if (value.startsWith('[')) {
        val closingBracket = value.indexOf(']')
        if (closingBracket <= 1) return null
        parsedHost = value.substring(1, closingBracket)
        val suffix = value.substring(closingBracket + 1)
        parsedPort = if (suffix.isEmpty()) null else parsePortSuffix(suffix) ?: return null
    } else {
        val colon = value.lastIndexOf(':')
        if (colon < 0) {
            parsedHost = value
            parsedPort = null
        } else {
            if (value.indexOf(':') != colon) return null // IPv6 literals must be bracketed.
            parsedPort = parsePortSuffix(value.substring(colon)) ?: return null
            parsedHost = value.substring(0, colon)
        }
    }

    if (parsedHost.isBlank() || '%' in parsedHost) return null
    return HostAuthority(normalizeViewerHost(parsedHost), parsedPort)
}

internal fun normalizeViewerHost(host: String): String = host.trim().trimEnd('.').lowercase()

internal fun ApplicationCall.hasSameSecureOrigin(): Boolean {
    val originValue = request.headers[HttpHeaders.Origin]?.takeIf { it.isNotBlank() } ?: return false
    val origin = runCatching { URI(originValue) }.getOrNull() ?: return false
    if (!origin.scheme.equals("https", ignoreCase = true) || origin.rawUserInfo != null ||
        !origin.rawPath.isNullOrEmpty() || origin.rawQuery != null || origin.rawFragment != null
    ) return false

    val originHost = origin.host?.removePrefix("[")?.removeSuffix("]")?.let(::normalizeViewerHost)
        ?.takeIf { it.isNotBlank() } ?: return false
    val originPort = if (origin.port == -1) 443 else origin.port
    if (originPort !in 1..65_535) return false

    val requestAuthority = parseHostAuthority(request.headers[HttpHeaders.Host]) ?: return false
    val requestPort = requestAuthority.port ?: 443
    return originHost == requestAuthority.host && originPort == requestPort
}

private fun parsePortSuffix(suffix: String): Int? {
    if (!suffix.startsWith(':')) return null
    val port = suffix.substring(1).toIntOrNull() ?: return null
    return port.takeIf { it in 1..65_535 }
}

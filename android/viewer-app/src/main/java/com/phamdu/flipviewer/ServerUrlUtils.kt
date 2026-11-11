package com.phamdu.flipviewer

import java.net.URI

object ServerUrlUtils {

    const val DEFAULT_SERVER_URL = "https://192.168.0.106:8443/"

    /**
     * Normalizes a user-entered server URL or IP:port string.
     * Ensures scheme (defaults to https://), path (defaults to /), and handles trimming.
     */
    fun normalizeUrl(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return DEFAULT_SERVER_URL
        }

        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed = "https://$trimmed"
        }

        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host ?: ""
            val port = uri.port
            val path = uri.rawPath ?: ""
            val query = uri.rawQuery

            val normalizedPath = when {
                path.isEmpty() -> "/"
                else -> path
            }

            val portPart = if (port != -1) ":$port" else ""
            val queryPart = if (!query.isNullOrEmpty()) "?$query" else ""

            if (host.isNotEmpty()) {
                "$scheme://$host$portPart$normalizedPath$queryPart"
            } else {
                if (!trimmed.endsWith("/") && !trimmed.contains("?")) {
                    "$trimmed/"
                } else {
                    trimmed
                }
            }
        } catch (_: Exception) {
            if (!trimmed.endsWith("/") && !trimmed.contains("?")) {
                "$trimmed/"
            } else {
                trimmed
            }
        }
    }

    /**
     * Extracts host and port (e.g. "192.168.0.106:8443") for topbar badge.
     */
    fun formatBadge(url: String): String {
        return try {
            val uri = URI(url)
            val host = uri.host
            val port = uri.port
            if (!host.isNullOrEmpty()) {
                if (port != -1 && port != 443 && port != 80) {
                    "$host:$port"
                } else {
                    host
                }
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }
}

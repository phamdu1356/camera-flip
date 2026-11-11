package com.phamdu.flipcamera.security

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Storage adapter for event metadata and app-private thumbnails. No client path is accepted. */
internal interface ViewerEventSource {
    suspend fun listEvents(fromUtc: Long, toUtc: Long, limit: Int): List<ViewerEventRecord>

    /** Resolve by event UUID to a file already validated under app-private thumbnail storage. */
    suspend fun openThumbnail(eventId: String): ViewerEventThumbnail?
}

internal data class ViewerEventRecord(
    val id: String,
    val kind: String,
    val startUtc: Long,
    val endUtc: Long,
    val peakUtc: Long?,
    val motionScore: Double?,
    val segmentId: String?,
    val segmentStartUtc: Long?,
    val segmentDurationMs: Long?,
    val thumbnailAvailable: Boolean,
)

/** Caller owns [stream] and must close it, including when the HTTP client disconnects. */
internal data class ViewerEventThumbnail(
    val stream: InputStream,
    val byteLength: Long,
)

/** Authenticated, bounded, read-only motion event routes. */
internal class ViewerEventApi(
    private val pairing: ViewerPairingManager,
    private val source: ViewerEventSource,
) {
    fun install(application: Application) {
        application.routing {
            get("/api/events") {
                if (!call.requireViewerSession(pairing)) return@get
                val query = call.request.queryParameters
                if (query.names().any { it !in EVENT_QUERY_FIELDS } ||
                    EVENT_QUERY_FIELDS.any { (query.getAll(it)?.size ?: 0) > 1 }
                ) {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_query")
                }
                val now = System.currentTimeMillis()
                val fromUtc = query["from"]?.toLongOrNull() ?: if ("from" !in query.names()) {
                    now - DEFAULT_WINDOW_MS
                } else {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                }
                val toUtc = query["to"]?.toLongOrNull() ?: if ("to" !in query.names()) {
                    now
                } else {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_time")
                }
                val limit = query["limit"]?.toIntOrNull() ?: if ("limit" !in query.names()) {
                    DEFAULT_LIMIT
                } else {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_limit")
                }
                if (fromUtc < 0L || toUtc <= fromUtc || toUtc - fromUtc > MAX_WINDOW_MS ||
                    toUtc > now + MAX_FUTURE_SKEW_MS
                ) {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_time_window")
                }
                if (limit !in 1..MAX_LIMIT) {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_limit")
                }

                val events = source.listEvents(fromUtc, toUtc, limit)
                    .asSequence()
                    .filter { it.isValidForViewer(fromUtc, toUtc) }
                    .sortedWith(compareByDescending<ViewerEventRecord> { it.startUtc }.thenBy { it.id })
                    .take(limit)
                    .map(::toViewerJson)
                    .toList()
                call.respondJson(
                    HttpStatusCode.OK,
                    JSONObject()
                        .put("fromUtc", fromUtc)
                        .put("toUtc", toUtc)
                        .put("events", JSONArray(events))
                        .toString(),
                )
            }

            get("/api/events/{id}/thumbnail") {
                if (!call.requireViewerSession(pairing)) return@get
                val id = call.parameters["id"]
                if (!isCanonicalUuid(id)) return@get call.respondError(HttpStatusCode.BadRequest, "invalid_event_id")
                val thumbnail = source.openThumbnail(requireNotNull(id))
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "thumbnail_not_found")
                if (thumbnail.byteLength !in 1..MAX_THUMBNAIL_BYTES) {
                    thumbnail.stream.close()
                    return@get call.respondError(HttpStatusCode.Gone, "thumbnail_unavailable")
                }

                call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.response.headers.append("Referrer-Policy", "no-referrer")
                try {
                    call.respondOutputStream(
                        contentType = JPEG_CONTENT_TYPE,
                        status = HttpStatusCode.OK,
                        contentLength = thumbnail.byteLength,
                    ) {
                        thumbnail.stream.use { input ->
                            val buffer = ByteArray(THUMBNAIL_BUFFER_BYTES)
                            var remaining = thumbnail.byteLength
                            while (remaining > 0L) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read < 0) throw IOException("Thumbnail ended before its declared length")
                                write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                    }
                } finally {
                    runCatching { thumbnail.stream.close() }
                }
            }
        }
    }

    private fun toViewerJson(event: ViewerEventRecord): JSONObject {
        val json = JSONObject()
            .put("id", event.id)
            .put("kind", "MOTION")
            .put("startUtc", event.startUtc)
            .put("endUtc", event.endUtc)
            .put("peakUtc", event.peakUtc ?: JSONObject.NULL)
            .put("motionScore", event.motionScore?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: JSONObject.NULL)
        if (event.thumbnailAvailable) json.put("thumbnailUrl", "/api/events/${event.id}/thumbnail")

        val segmentId = event.segmentId
        val segmentStart = event.segmentStartUtc
        val segmentDuration = event.segmentDurationMs
        if (isCanonicalUuid(segmentId) && segmentStart != null && segmentDuration != null && segmentDuration > 0L) {
            json.put("segmentId", segmentId)
            json.put("videoUrl", "/api/segments/$segmentId/video")
            json.put("segmentStartUtc", segmentStart)
            json.put("segmentDurationMs", segmentDuration)
        }
        return json
    }

    private fun ViewerEventRecord.isValidForViewer(fromUtc: Long, toUtc: Long): Boolean =
        isCanonicalUuid(id) &&
            kind.matches(EVENT_KIND_PATTERN) &&
            startUtc >= 0L && endUtc >= startUtc && startUtc < toUtc && endUtc > fromUtc &&
            (peakUtc == null || peakUtc in startUtc..endUtc)

    private suspend fun io.ktor.server.application.ApplicationCall.respondError(
        status: HttpStatusCode,
        code: String,
    ) {
        respondJson(status, JSONObject().put("error", code).toString())
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
        status: HttpStatusCode,
        body: String,
    ) {
        response.headers.append(HttpHeaders.CacheControl, "private, no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        response.headers.append("Referrer-Policy", "no-referrer")
        respondText(body, ContentType.Application.Json, status)
    }

    private fun isCanonicalUuid(value: String?): Boolean {
        if (value == null || value.length != UUID_TEXT_LENGTH) return false
        return runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 200
        const val UUID_TEXT_LENGTH = 36
        const val MAX_THUMBNAIL_BYTES = 1_048_576L
        const val THUMBNAIL_BUFFER_BYTES = 8 * 1024
        const val MAX_WINDOW_MS = 31L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1_000L
        const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1_000L
        val EVENT_QUERY_FIELDS = setOf("from", "to", "limit")
        val EVENT_KIND_PATTERN = Regex("[A-Z][A-Z0-9_]{0,31}")
        val JPEG_CONTENT_TYPE = ContentType.parse("image/jpeg")
    }
}

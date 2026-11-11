package com.phamdu.flipcamera.security

import com.phamdu.flipcamera.data.SegmentDao
import com.phamdu.flipcamera.data.SegmentEntity
import com.phamdu.flipcamera.data.SegmentFileIOException
import com.phamdu.flipcamera.data.SegmentFileStore
import com.phamdu.flipcamera.data.SegmentStatus
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
import java.util.UUID

/** Authenticated, read-only timeline and finalized MP4 routes. */
internal class ViewerMediaApi(
    private val pairing: ViewerPairingManager,
    private val dao: SegmentDao,
    private val fileStore: SegmentFileStore,
) {
    fun install(application: Application) {
        application.routing {
            get("/api/timeline") {
                if (!call.requireViewerSession(pairing)) return@get
                val query = call.request.queryParameters
                if (query.names().any { it !in TIMELINE_QUERY_FIELDS } ||
                    TIMELINE_QUERY_FIELDS.any { (query.getAll(it)?.size ?: 0) > 1 }
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
                val cursorFields = listOf("afterStart", "afterId", "afterType")
                val hasCursor = cursorFields.any { it in query.names() }
                val afterStart = if (hasCursor) query["afterStart"]?.toLongOrNull() else -1L
                val afterId = query["afterId"] ?: ""
                val afterType = query["afterType"] ?: ""
                if (afterStart == null || (hasCursor && (afterStart < 0L ||
                        !isCanonicalUuid(afterId) || afterType !in setOf("gap", "segment")))) {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_cursor")
                }
                val segments = dao.getReadyTimelinePage(fromUtc, toUtc, afterStart, afterId, afterType, limit + 1)
                val gaps = dao.getGapTimelinePage(fromUtc, toUtc, afterStart, afterId, afterType, limit + 1)
                val candidates = buildList {
                    segments.forEach { add(TimelineEntry(it.startUtc, it.id, "segment", it.toViewerJson())) }
                    gaps.forEach { add(TimelineEntry(it.startUtc, it.id, "gap", it.toViewerJson())) }
                }.sortedWith(compareBy<TimelineEntry> { it.startUtc }.thenBy { it.id }.thenBy { it.type })
                val page = candidates.take(limit)
                val next = if (candidates.size > limit) page.last() else null
                call.respondJson(
                    HttpStatusCode.OK,
                    JSONObject()
                        .put("fromUtc", fromUtc)
                        .put("toUtc", toUtc)
                        .put("nextCursor", next?.let {
                            JSONObject().put("startUtc", it.startUtc).put("id", it.id).put("type", it.type)
                        } ?: JSONObject.NULL)
                        .put("segments", JSONArray().apply { page.filter { it.type == "segment" }.forEach { put(it.json) } })
                        .put("gaps", JSONArray().apply { page.filter { it.type == "gap" }.forEach { put(it.json) } })
                        .toString(),
                )
            }

            get("/api/segments/{id}/next") {
                if (!call.requireViewerSession(pairing)) return@get
                val id = call.parameters["id"]
                if (!isCanonicalUuid(id)) return@get call.respondError(HttpStatusCode.BadRequest, "invalid_segment_id")
                val current = dao.getById(requireNotNull(id))
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "segment_not_found")
                val next = dao.getNextReady(current.startUtc, current.id)
                call.respondJson(HttpStatusCode.OK, JSONObject().put("segment", next?.toViewerJson() ?: JSONObject.NULL).toString())
            }

            get("/api/segments/{id}/video") {
                if (!call.requireViewerSession(pairing)) return@get
                val id = call.parameters["id"]
                if (!isCanonicalUuid(id)) return@get call.respondError(HttpStatusCode.BadRequest, "invalid_segment_id")
                val segment = dao.getById(requireNotNull(id))
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "segment_not_found")
                when (segment.status) {
                    SegmentStatus.READY -> Unit
                    SegmentStatus.DELETED, SegmentStatus.FAILED ->
                        return@get call.respondError(HttpStatusCode.Gone, "segment_expired")
                    SegmentStatus.RECORDING ->
                        return@get call.respondError(HttpStatusCode.Conflict, "segment_not_finalized")
                }

                val media = try {
                    fileStore.openReadySegment(segment.id, segment.internalName)
                } catch (_: SegmentFileIOException) {
                    return@get call.respondError(HttpStatusCode.Gone, "segment_expired")
                } catch (_: IOException) {
                    return@get call.respondError(HttpStatusCode.Gone, "segment_expired")
                }
                val size = try {
                    media.channel.size()
                } catch (_: IOException) {
                    media.close()
                    return@get call.respondError(HttpStatusCode.Gone, "segment_expired")
                }
                if (size <= 0L) {
                    media.close()
                    return@get call.respondError(HttpStatusCode.Gone, "segment_expired")
                }

                call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                if (call.request.queryParameters["download"] == "1") {
                    call.response.headers.append(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"${viewerDownloadFileName(segment.startUtc)}\"",
                    )
                }
                call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.response.headers.append("Referrer-Policy", "no-referrer")
                val rangeHeaders = call.request.headers.getAll(HttpHeaders.Range).orEmpty()
                if (rangeHeaders.size > 1) {
                    media.close()
                    return@get call.respondRangeError(size)
                }
                val requestedRange = rangeHeaders.singleOrNull()
                val range = if (requestedRange == null) {
                    ViewerByteRange(0L, size - 1L)
                } else {
                    parseViewerByteRange(requestedRange, size)
                        ?: run {
                            media.close()
                            return@get call.respondRangeError(size)
                        }
                }
                val responseLength = range.endInclusive - range.start + 1L
                val responseStatus = if (requestedRange == null) HttpStatusCode.OK else HttpStatusCode.PartialContent
                call.response.headers.append(HttpHeaders.ContentLength, responseLength.toString())
                if (requestedRange != null) {
                    call.response.headers.append(
                        HttpHeaders.ContentRange,
                        "bytes ${range.start}-${range.endInclusive}/$size",
                    )
                }
                try {
                    call.respondOutputStream(
                        contentType = MP4_CONTENT_TYPE,
                        status = responseStatus,
                        contentLength = responseLength,
                    ) {
                        media.use { source ->
                            source.channel.position(range.start)
                            val buffer = ByteArray(READ_BUFFER_SIZE)
                            var remaining = responseLength
                            while (remaining > 0L) {
                                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read < 0) throw IOException("Finalized segment ended before the selected byte range.")
                                write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                    }
                } catch (failure: IOException) {
                    media.close()
                    throw failure
                }
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondRangeError(size: Long) {
        response.headers.append(HttpHeaders.ContentRange, "bytes */$size")
        respondError(HttpStatusCode(416, "Range Not Satisfiable"), "invalid_range")
    }

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

    private fun SegmentEntity.toViewerJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sessionId", sessionId)
        .put("startUtc", startUtc)
        .put("endUtc", endUtc)
        .put("durationMs", duration)
        .put("width", width)
        .put("height", height)
        .put("codec", codec)
        .put("bytes", bytes)
        .put("audio", audio)
        .put("protected", isProtected)
        .put("videoUrl", "/api/segments/$id/video")

    private fun com.phamdu.flipcamera.data.GapEntity.toViewerJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sessionId", sessionId ?: JSONObject.NULL)
        .put("startUtc", startUtc)
        .put("endUtc", endUtc)
        .put("durationMs", endUtc - startUtc)
        .put("reason", reason.take(MAX_REASON_CHARS))

    private fun isCanonicalUuid(value: String?): Boolean {
        if (value == null || value.length != UUID_TEXT_LENGTH) return false
        return runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }

    private data class TimelineEntry(
        val startUtc: Long,
        val id: String,
        val type: String,
        val json: JSONObject,
    )

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 500
        const val MAX_WINDOW_MS = 31L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1_000L
        const val MAX_FUTURE_SKEW_MS = 5L * 60L * 1_000L
        const val MAX_REASON_CHARS = 240
        const val UUID_TEXT_LENGTH = 36
        const val READ_BUFFER_SIZE = 64 * 1024
        val MP4_CONTENT_TYPE = ContentType.parse("video/mp4")
        val TIMELINE_QUERY_FIELDS = setOf("from", "to", "limit", "afterStart", "afterId", "afterType")
    }
}

internal data class ViewerByteRange(val start: Long, val endInclusive: Long)

/** Parses one RFC 9110 byte range, clamping its end/suffix to the file length. */
internal fun parseViewerByteRange(header: String, totalSize: Long): ViewerByteRange? {
    if (totalSize <= 0L || !header.startsWith("bytes=") || ',' in header) return null
    val value = header.removePrefix("bytes=")
    val separator = value.indexOf('-')
    if (separator < 0 || separator != value.lastIndexOf('-')) return null
    val first = value.substring(0, separator)
    val last = value.substring(separator + 1)
    if (first.isEmpty()) {
        val suffixLength = last.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return ViewerByteRange((totalSize - suffixLength).coerceAtLeast(0L), totalSize - 1L)
    }
    val start = first.toLongOrNull()?.takeIf { it >= 0L && it < totalSize } ?: return null
    val requestedEnd = if (last.isEmpty()) totalSize - 1L else last.toLongOrNull() ?: return null
    if (requestedEnd < start) return null
    return ViewerByteRange(start, requestedEnd.coerceAtMost(totalSize - 1L))
}

/** Tên tệp tải về theo giờ bắt đầu, UTC+7: flip-2026-11-06_08-30-15.mp4 */
internal fun viewerDownloadFileName(startUtcMs: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .format(java.time.Instant.ofEpochMilli(startUtcMs).atOffset(java.time.ZoneOffset.ofHours(7)))
        .let { "flip-$it.mp4" }

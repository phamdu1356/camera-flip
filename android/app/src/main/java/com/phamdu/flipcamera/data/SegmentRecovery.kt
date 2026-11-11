package com.phamdu.flipcamera.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SegmentRecoveryReport(
    val recoveredReady: Int,
    val markedFailed: Int,
    val gapsRecorded: Int,
)

/**
 * Reconcile rows left in RECORDING after a process death. File work runs off the main thread.
 * A deterministic final filename lets this repair a crash between the atomic rename and Room's
 * READY compare-and-set. A valid temp MP4 is adopted only through the app-private file store.
 */
class SegmentRecovery(context: Context, private val dao: SegmentDao) {
    private val fileStore = SegmentFileStore(context.applicationContext)
    private val watermarkGuard = WatermarkSegmentGuard(context.applicationContext)

    suspend fun reconcile(recoveredAtUtc: Long = System.currentTimeMillis()): SegmentRecoveryReport =
        withContext(Dispatchers.IO) {
            var readyCount = 0
            var failedCount = 0
            var gapCount = 0

            for (row in dao.getRecordingSegmentsForRecovery()) {
                // New watermark recordings opt in before encoder start. A crash leaves no
                // proof that every encoded frame was verified; preserve media without adoption,
                // rename or deletion. Legacy rows with no marker retain the old recovery path.
                if (watermarkGuard.isUnverified(row.id)) {
                    val endUtc = maxOf(recoveredAtUtc, Math.addExact(row.startUtc, 1L))
                    val changed = dao.failRecordingWithGap(
                        row.id, row.revision,
                        GapEntity(sessionId = row.sessionId, startUtc = row.startUtc, endUtc = endUtc,
                            reason = "Watermark chưa được xác minh sau gián đoạn; giữ nguyên tệp để kiểm tra."),
                    )
                    if (changed == 1) {
                        failedCount++
                        gapCount++
                    }
                    continue
                }
                var adoptedTemp: File? = null
                val finalized = try {
                    fileStore.findFinalizedForRecovery(row.id) ?: run {
                        val pending = fileStore.adoptPendingFile(row.id, row.path)
                        adoptedTemp = pending
                        fileStore.finalizeTempFile(row.id, pending)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A corrupt or missing recording is not playable. Retain its metadata as
                    // FAILED. Only after that compare-and-set succeeds may we remove an invalid
                    // deterministic final orphan or the validated temp file this store adopted.
                    if (dao.failRecording(row.id, expectedRevision = row.revision) == 1) {
                        adoptedTemp?.let { temp -> runCatching { fileStore.discardTempFile(temp) } }
                        runCatching { fileStore.discardInvalidFinalizedFile(row.id) }
                        if (recoveredAtUtc > row.startUtc) {
                            dao.insertGap(
                                GapEntity(
                                    sessionId = row.sessionId,
                                    startUtc = row.startUtc,
                                    endUtc = recoveredAtUtc,
                                    reason = "Phân đoạn dở dang khi app khởi động lại; không có MP4 dùng được.",
                                ),
                            )
                            gapCount++
                        }
                        failedCount++
                    }
                    continue
                }

                // The old process could not persist an end callback; use measured media duration
                // from the persisted start boundary and never pad to the nominal 60-second size.
                val endUtc = Math.addExact(row.startUtc, finalized.durationMs)
                val changed = dao.finalizeSegment(
                    id = row.id,
                    expectedRevision = row.revision,
                    startUtc = row.startUtc,
                    endUtc = endUtc,
                    duration = finalized.durationMs,
                    bytes = finalized.bytes,
                    width = finalized.width,
                    height = finalized.height,
                    codec = finalized.videoMime ?: "video/avc",
                    audio = finalized.audioTrackPresent,
                    finalInternalName = finalized.internalName,
                    finalPath = finalized.path,
                )
                if (changed == 1) {
                    readyCount++
                } else {
                    // A competing newer database state wins. Keep the media because a failed
                    // reread cannot prove that it is safe to remove.
                    val latest = dao.getById(row.id)
                    if (latest?.status == SegmentStatus.READY &&
                        latest.internalName == finalized.internalName && latest.path == finalized.path
                    ) {
                        readyCount++
                    }
                }
            }
            SegmentRecoveryReport(readyCount, failedCount, gapCount)
        }
}

package com.phamdu.flipcamera.data

/** Maps a Room row to the fields the storage policy needs. */
internal fun SegmentEntity.toStorageSegment(): StorageSegment =
    StorageSegment(id = id, startUtc = startUtc, bytes = bytes, isProtected = isProtected)

/**
 * Ids to evict for the given rows. Only READY rows are considered, so RECORDING, FAILED and DELETED
 * rows are never offered to the policy; [excludeIds] lets the caller shield an in-flight segment.
 */
internal fun storageEvictionIds(
    rows: List<SegmentEntity>,
    config: StoragePolicyConfig,
    nowUtc: Long,
    excludeIds: Set<String> = emptySet(),
): List<String> {
    val ready = rows.filter { it.status == SegmentStatus.READY && it.id !in excludeIds }
    return segmentsToEvict(ready.map { it.toStorageSegment() }, config, nowUtc)
}

/** Reads every READY row with keyset paging so no single query returns the whole library. */
suspend fun SegmentDao.readAllReadySegments(): List<SegmentEntity> {
    val all = ArrayList<SegmentEntity>()
    var afterStart = -1L
    var afterId = ""
    while (true) {
        val page = getReadySegmentsPage(afterStart, afterId, PAGE_SIZE)
        all += page
        if (page.size < PAGE_SIZE) break
        val last = page.last()
        afterStart = last.startUtc
        afterId = last.id
    }
    return all
}

private const val PAGE_SIZE = 200
private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
internal const val MAX_STORAGE_DAYS = 36_500
internal const val MAX_STORAGE_GB = 1_000_000.0

/**
 * Parses the settings form. Days is a whole number and GB may be decimal (a comma is accepted).
 * Returns null for anything invalid, so a typo can never silently become "unlimited" (0).
 */
internal fun parseStoragePolicyInput(mode: RecordingMode, daysText: String, gbText: String): StoragePolicyConfig? {
    val days = daysText.trim().toIntOrNull()?.takeIf { it in 0..MAX_STORAGE_DAYS } ?: return null
    val gb = gbText.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_STORAGE_GB } ?: return null
    val bytes = (gb * BYTES_PER_GB).toLong()
    if (gb > 0.0 && bytes <= 0L) return null
    return StoragePolicyConfig(mode, days, bytes)
}

/** Inverse of [parseStoragePolicyInput] for the GB field; nine decimals keep the round trip exact. */
internal fun formatStorageGb(bytes: Long): String {
    if (bytes <= 0L) return "0"
    return String.format(java.util.Locale.US, "%.9f", bytes / BYTES_PER_GB).trimEnd('0').trimEnd('.')
}

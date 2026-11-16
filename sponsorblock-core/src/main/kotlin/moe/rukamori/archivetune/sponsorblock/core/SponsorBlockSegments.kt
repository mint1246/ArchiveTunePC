package moe.rukamori.archivetune.sponsorblock.core

import kotlin.math.max
import kotlin.math.roundToLong

data class RawSponsorBlockSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val category: String,
    val actionType: String,
)

data class SponsorBlockRange(val startMs: Long, val endMs: Long)

/** Filters invalid and unselected skip segments, then merges overlapping or touching ranges. */
fun normalizeSponsorBlockSegments(
    segments: Iterable<RawSponsorBlockSegment>,
    selectedCategories: Set<String>,
): List<SponsorBlockRange> {
    val ordered = segments.mapNotNull { segment ->
        if (segment.actionType != "skip" || segment.category !in selectedCategories) return@mapNotNull null
        val start = segment.startSeconds
        val end = segment.endSeconds
        if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) return@mapNotNull null
        if (end > Long.MAX_VALUE / MILLIS_PER_SECOND) return@mapNotNull null
        val startMs = (start * MILLIS_PER_SECOND).roundToLong().coerceAtLeast(0L)
        val endMs = (end * MILLIS_PER_SECOND).roundToLong()
        if (endMs - startMs < MIN_SEGMENT_MS) return@mapNotNull null
        SponsorBlockRange(startMs, endMs)
    }.sortedWith(compareBy(SponsorBlockRange::startMs, SponsorBlockRange::endMs))

    val merged = ArrayList<SponsorBlockRange>(ordered.size)
    ordered.forEach { segment ->
        val previous = merged.lastOrNull()
        if (previous != null && segment.startMs <= previous.endMs) {
            merged[merged.lastIndex] = previous.copy(endMs = max(previous.endMs, segment.endMs))
        } else {
            merged += segment
        }
    }
    return merged
}

private const val MILLIS_PER_SECOND = 1_000.0
private const val MIN_SEGMENT_MS = 100L

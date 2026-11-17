package moe.rukamori.archivetune.sponsorblock.core

/** Tracks segments already handled during one playback-monitoring session. */
class SponsorBlockSkipPlanner(private val segments: List<SponsorBlockRange>) {
    private var lastSkippedEndMs: Long? = null

    fun targetPosition(positionMs: Long, durationMs: Long?): Long? {
        val position = positionMs.coerceAtLeast(0L)
        val active = segments.firstOrNull { position >= it.startMs && position < it.endMs - END_TOLERANCE_MS }
        if (active == null) {
            lastSkippedEndMs = null
            return null
        }
        if (lastSkippedEndMs == active.endMs) return null
        lastSkippedEndMs = active.endMs
        val target = durationMs?.takeIf { it > 0L }?.let { active.endMs.coerceAtMost(it) } ?: active.endMs
        return target.takeIf { it - position >= MIN_SEEK_DISTANCE_MS }
    }

    private companion object {
        const val END_TOLERANCE_MS = 50L
        const val MIN_SEEK_DISTANCE_MS = 100L
    }
}

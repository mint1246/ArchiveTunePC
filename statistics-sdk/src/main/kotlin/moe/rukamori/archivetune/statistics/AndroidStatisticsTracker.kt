/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.statistics

interface AndroidStatisticsClock {
    fun elapsedRealtimeMs(): Long

    fun wallTimeEpochMs(): Long
}

interface AndroidStatisticsSink {
    fun recordAudible(
        song: AndroidStatisticsSong,
        startEpochMs: Long,
        durationMs: Long,
        playAtEpochMs: Long? = null,
    )
}

data class AndroidStatisticsPlaybackSnapshot(
    val song: AndroidStatisticsSong?,
    val playWhenReady: Boolean,
    val ready: Boolean,
)

/**
 * Android equivalent of the desktop authoritative listen tracker.
 *
 * Time comes from a monotonic clock, so seeking and playback speed do not manufacture listening
 * time. A delta is counted only while the previously sampled state was ready, intended to play,
 * had a song, and was eligible. Party inclusion is intentionally an input boundary rather than a
 * store concern: pass eligible=false while party listening should be excluded.
 */
class AndroidStatisticsTracker(
    private val sink: AndroidStatisticsSink,
    private val clock: AndroidStatisticsClock,
    private val playThresholdMs: Long = DEFAULT_PLAY_THRESHOLD_MS,
) {
    private data class Sample(
        val song: AndroidStatisticsSong?,
        val audible: Boolean,
        val eligible: Boolean,
        val elapsedMs: Long,
    )

    private data class TrackSession(
        val songId: String,
        var eligibleAudibleMs: Long = 0L,
        var playCounted: Boolean = false,
    )

    private var previous: Sample? = null
    private var session: TrackSession? = null
    private var forceNewSession = false

    init {
        require(playThresholdMs > 0L) { "Play threshold must be positive" }
    }

    @Synchronized
    fun onSnapshot(
        snapshot: AndroidStatisticsPlaybackSnapshot,
        eligible: Boolean = true,
    ) {
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowWall = clock.wallTimeEpochMs()
        accumulatePrevious(nowElapsed, nowWall)

        val current = snapshot.song
        val currentSession = session
        if (
            forceNewSession ||
            current == null ||
            currentSession == null ||
            currentSession.songId != current.id
        ) {
            session = current?.let { TrackSession(it.id) }
            forceNewSession = false
        }

        previous =
            Sample(
                song = current,
                audible = snapshot.playWhenReady && snapshot.ready && current != null,
                eligible = eligible,
                elapsedMs = nowElapsed,
            )
    }

    /** Call for every real media transition, including repeat-one to the same id. */
    @Synchronized
    fun onMediaTransition() {
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowWall = clock.wallTimeEpochMs()
        accumulatePrevious(nowElapsed, nowWall)
        previous = null
        session = null
        forceNewSession = true
    }

    /** Flush time through this instant while preserving the current sampled eligibility/state. */
    @Synchronized
    fun flush() {
        val old = previous ?: return
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowWall = clock.wallTimeEpochMs()
        accumulatePrevious(nowElapsed, nowWall)
        previous = old.copy(elapsedMs = nowElapsed)
    }

    private fun accumulatePrevious(
        nowElapsed: Long,
        nowWall: Long,
    ) {
        val old = previous ?: return
        val delta = (nowElapsed - old.elapsedMs).coerceAtLeast(0L)
        val song = old.song
        if (delta <= 0L || song == null || !old.audible || !old.eligible) return

        var currentSession = session
        if (currentSession == null || currentSession.songId != song.id) {
            currentSession = TrackSession(song.id)
            session = currentSession
        }

        val before = currentSession.eligibleAudibleMs
        val after = safeAdd(before, delta)
        currentSession.eligibleAudibleMs = after

        val threshold = effectivePlayThresholdMs(song.durationMs, playThresholdMs)
        var playAt: Long? = null
        if (!currentSession.playCounted && before < threshold && after >= threshold) {
            currentSession.playCounted = true
            val thresholdOffset = threshold - before
            val intervalStartWall = safeSubtract(nowWall, delta)
            playAt = safeAdd(intervalStartWall, thresholdOffset)
        }

        sink.recordAudible(
            song = song,
            startEpochMs = safeSubtract(nowWall, delta),
            durationMs = delta,
            playAtEpochMs = playAt,
        )
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun safeSubtract(left: Long, right: Long): Long =
        if (right > 0L && left < Long.MIN_VALUE + right) Long.MIN_VALUE else left - right

    companion object {
        const val DEFAULT_PLAY_THRESHOLD_MS = 30_000L
    }
}

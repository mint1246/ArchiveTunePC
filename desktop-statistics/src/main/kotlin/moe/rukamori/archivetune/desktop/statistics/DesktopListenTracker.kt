/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.statistics

import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface StatisticsClock {
    /** Monotonic elapsed time. Must not be derived from the wall clock. */
    fun elapsedRealtimeMs(): Long

    /** Wall time used only to decide which local calendar bucket receives an already measured delta. */
    fun wallTimeEpochMs(): Long
}

object SystemStatisticsClock : StatisticsClock {
    override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

    override fun wallTimeEpochMs(): Long = System.currentTimeMillis()
}

/**
 * Turns desktop [PlaybackSnapshot] samples into authoritative real audible listening time.
 *
 * Position changes are intentionally ignored: seeking forward/back never creates or removes listening
 * time. Playback speed is likewise ignored because deltas come only from a monotonic elapsed clock.
 * A delta is counted only while the previous sampled state was READY + playWhenReady and eligible.
 */
class DesktopListenTracker(
    private val store: DesktopStatisticsStore,
    private val clock: StatisticsClock = SystemStatisticsClock,
    private val playThresholdMs: Long = DEFAULT_PLAY_THRESHOLD_MS,
) {
    private data class Sample(
        val song: StatisticsSongMetadata?,
        val audible: Boolean,
        val eligible: Boolean,
        val elapsedMs: Long,
    )

    private data class TrackSession(
        val songId: String,
        var eligibleAudibleMs: Long = 0L,
        var playCounted: Boolean = false,
    )

    private val lock = ReentrantLock()
    private var previous: Sample? = null
    private var session: TrackSession? = null
    private var forceNewSession = false

    init {
        require(playThresholdMs > 0L) { "Play threshold must be positive" }
    }

    /**
     * Samples current playback. [eligible] can change at any time (for example when the user's
     * "count party listening" setting changes) without resetting the current track's threshold.
     */
    fun onSnapshot(snapshot: PlaybackSnapshot, eligible: Boolean = true) = lock.withLock {
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowWall = clock.wallTimeEpochMs()
        accumulatePreviousLocked(nowElapsed, nowWall)

        val current = snapshot.currentItem?.toStatisticsMetadata(snapshot.durationMs)
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

        previous = Sample(
            song = current,
            audible = snapshot.isStatisticsAudible(),
            eligible = eligible,
            elapsedMs = nowElapsed,
        )
    }

    /**
     * Marks a real media transition, including repeat-one of the same id. Call this from the desktop
     * playback event stream when available so each repeated track gets its own play gate.
     * Time since the previous snapshot is flushed first using the previous authoritative state.
     */
    fun onMediaTransition() = lock.withLock {
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowWall = clock.wallTimeEpochMs()
        accumulatePreviousLocked(nowElapsed, nowWall)
        previous = null
        session = null
        forceNewSession = true
    }

    /** Flushes audible time through this instant without changing play/pause/eligibility state. */
    fun flush() = lock.withLock {
        val old = previous ?: return@withLock
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowWall = clock.wallTimeEpochMs()
        accumulatePreviousLocked(nowElapsed, nowWall)
        previous = old.copy(elapsedMs = nowElapsed)
    }

    private fun accumulatePreviousLocked(nowElapsed: Long, nowWall: Long) {
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

        val effectiveThresholdMs = song.playThresholdMs()
        var playAt: Long? = null
        if (!currentSession.playCounted && before < effectiveThresholdMs && after >= effectiveThresholdMs) {
            currentSession.playCounted = true
            val thresholdOffset = effectiveThresholdMs - before
            val intervalStartWall = safeSubtract(nowWall, delta)
            playAt = safeAdd(intervalStartWall, thresholdOffset)
        }

        store.recordAudible(
            song = song,
            startEpochMs = safeSubtract(nowWall, delta),
            durationMs = delta,
            playAtEpochMs = playAt,
        )
    }

    private fun PlaybackSnapshot.isStatisticsAudible(): Boolean =
        playWhenReady && status == PlaybackStatus.READY && currentItem != null

    private fun PlaybackItem.toStatisticsMetadata(snapshotDurationMs: Long): StatisticsSongMetadata {
        val converted = StatisticsSongMetadata.fromPlayback(metadata)
        return if (converted.durationMs != null || snapshotDurationMs <= 0L) {
            converted
        } else {
            converted.copy(durationMs = snapshotDurationMs)
        }
    }

    private fun StatisticsSongMetadata.playThresholdMs(): Long {
        val duration = durationMs?.takeIf { it > 0L } ?: return playThresholdMs
        val halfDuration = (duration / 2L).coerceAtLeast(1L)
        return minOf(playThresholdMs, halfDuration)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun safeSubtract(left: Long, right: Long): Long =
        if (right > 0L && left < Long.MIN_VALUE + right) Long.MIN_VALUE else left - right

    companion object {
        const val DEFAULT_PLAY_THRESHOLD_MS = 30_000L
    }
}

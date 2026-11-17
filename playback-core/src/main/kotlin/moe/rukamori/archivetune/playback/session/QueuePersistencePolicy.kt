/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.math.abs
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus

/** Decides when to save a queue. Supply snapshots in order from one playback session. */
class QueuePersistencePolicy(
    private val checkpointMs: Long = 25_000L,
    private val seekJumpMs: Long = 5_000L,
) {
    private data class QueueKey(
        val title: String?,
        val queue: List<PlaybackItem>,
        val currentIndex: Int,
    )

    private data class Observation(
        val key: QueueKey,
        val playWhenReady: Boolean,
        val stopped: Boolean,
        val positionMs: Long,
        val positionBucket: Long,
    )

    private var previous: Observation? = null

    init {
        require(checkpointMs > 0L) { "Queue checkpoint interval must be positive" }
        require(seekJumpMs > 0L) { "Queue seek threshold must be positive" }
    }

    fun shouldPersist(snapshot: PlaybackSnapshot, force: Boolean = false): Boolean {
        val position = snapshot.positionMs.coerceAtLeast(0L)
        val current = Observation(
            key = QueueKey(snapshot.queueTitle, snapshot.queue, snapshot.currentIndex),
            playWhenReady = snapshot.playWhenReady,
            stopped = snapshot.status == PlaybackStatus.IDLE || snapshot.status == PlaybackStatus.ENDED,
            positionMs = position,
            positionBucket = position / checkpointMs,
        )
        val last = previous
        previous = current
        if (force) return true
        if (last == null) return false
        if (current.key != last.key) return true
        if (current.playWhenReady != last.playWhenReady) return true
        if (!last.stopped && current.stopped) return true
        if (current.key.currentIndex !in current.key.queue.indices) return false
        if (abs(current.positionMs - last.positionMs) >= seekJumpMs) return true
        return current.positionBucket != last.positionBucket
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Complete platform playback boundary consumed by shared UI and platform integrations.
 * Implementations serialize commands and release all owned resources from [close].
 */
interface PlaybackController : AutoCloseable {
    val state: StateFlow<PlaybackSnapshot>
    val events: SharedFlow<PlaybackEvent>

    suspend fun replaceQueue(
        items: List<PlaybackItem>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        title: String? = null,
        playWhenReady: Boolean = true,
    )

    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun seekToItem(index: Int, positionMs: Long = 0L)
    suspend fun skipNext()
    suspend fun skipPrevious(restartThresholdMs: Long = 3_000L)

    suspend fun enqueue(items: List<PlaybackItem>)
    suspend fun playNext(items: List<PlaybackItem>)
    suspend fun removeQueueItem(index: Int)
    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int)

    suspend fun setRepeatMode(mode: RepeatMode)
    suspend fun setShuffleEnabled(enabled: Boolean)
    suspend fun setVolume(volume: Float)
    suspend fun setSpeed(speed: Float)
    suspend fun retry()
    suspend fun clearError()
}

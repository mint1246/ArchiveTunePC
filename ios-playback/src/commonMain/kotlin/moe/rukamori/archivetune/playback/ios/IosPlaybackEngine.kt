/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource

/** Transport state reported by an underlying iOS media player implementation. */
data class IosEngineState(
    val itemId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val ended: Boolean = false,
    val error: PlaybackFailure? = null,
)

/** Callbacks invoked by platform lock-screen / Control Center remote commands. */
data class IosRemoteCommandHandlers(
    val onPlay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onTogglePlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onSeekTo: (positionMs: Long) -> Unit = {},
)

/** Structured exception thrown by resolvers or engines to preserve [PlaybackFailure] categories. */
class IosPlaybackException(
    val failure: PlaybackFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)

/**
 * Platform audio transport seam behind [IosPlaybackController].
 *
 * Apple targets supply `AvFoundationPlaybackEngine`; unit tests on JVM/Native supply a fake.
 * Implementations must never log signed URLs, cookies, or request headers.
 */
interface IosPlaybackEngine : AutoCloseable {
    val canAdjustVolume: Boolean get() = true
    val state: StateFlow<IosEngineState>

    fun bindRemoteCommands(handlers: IosRemoteCommandHandlers?) {}
    fun updateNavigationAvailability(hasPrevious: Boolean, hasNext: Boolean) {}

    fun load(item: PlaybackItem, source: ResolvedPlaybackSource, startPositionMs: Long = 0L)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float) {}
    fun setVolume(volume: Float) {}
    fun stop()
}

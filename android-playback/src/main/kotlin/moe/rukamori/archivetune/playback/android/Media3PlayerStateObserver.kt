/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Timeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.extensions.getCurrentQueueIndex
import moe.rukamori.archivetune.extensions.getQueueWindows

/**
 * Observes transport, queue windows, and playback errors on an existing Media3 [Player].
 * Construct, control, and close this observer on the player's application thread. Its [StateFlow]s
 * can be collected from other threads. The caller owns the player and releases it separately.
 */
class Media3PlayerStateObserver(
    val player: Player,
    private val onPlayerError: (PlaybackException) -> Unit = {},
    private val onTimelineUpdated: (Timeline, Int) -> Unit = { _, _ -> },
) : Player.Listener, AutoCloseable {
    private val _playbackState = MutableStateFlow(player.playbackState)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(player.isPlaying)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackParameters = MutableStateFlow(player.playbackParameters)
    val playbackParameters: StateFlow<PlaybackParameters> = _playbackParameters.asStateFlow()

    private val _queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())
    val queueWindows: StateFlow<List<Timeline.Window>> = _queueWindows.asStateFlow()

    private val _currentMediaItemIndex = MutableStateFlow(-1)
    val currentMediaItemIndex: StateFlow<Int> = _currentMediaItemIndex.asStateFlow()

    private val _currentWindowIndex = MutableStateFlow(-1)
    val currentWindowIndex: StateFlow<Int> = _currentWindowIndex.asStateFlow()

    private val _shuffleModeEnabled = MutableStateFlow(false)
    val shuffleModeEnabled: StateFlow<Boolean> = _shuffleModeEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _canSkipPrevious = MutableStateFlow(true)
    val canSkipPrevious: StateFlow<Boolean> = _canSkipPrevious.asStateFlow()

    private val _canSkipNext = MutableStateFlow(true)
    val canSkipNext: StateFlow<Boolean> = _canSkipNext.asStateFlow()

    private val _error = MutableStateFlow<PlaybackException?>(null)
    val error: StateFlow<PlaybackException?> = _error.asStateFlow()

    private var dismissedPlaybackError: PlaybackException? = null

    init {
        player.addListener(this)

        _playbackState.value = player.playbackState
        _isPlaying.value = player.isPlaying
        _playbackParameters.value = player.playbackParameters
        _queueWindows.value = player.getQueueWindows()
        _currentWindowIndex.value = player.getCurrentQueueIndex()
        _currentMediaItemIndex.value = player.currentMediaItemIndex
        _shuffleModeEnabled.value = player.shuffleModeEnabled
        _repeatMode.value = player.repeatMode
    }

    fun dismissPlaybackError() {
        dismissedPlaybackError = _error.value ?: player.playerError
        _error.value = null
    }

    fun seekToNext() {
        player.seekToNext()
        player.prepare()
        player.playWhenReady = true
    }

    fun seekToPrevious() {
        player.seekToPrevious()
        player.prepare()
        player.playWhenReady = true
    }

    override fun onPlaybackStateChanged(state: Int) {
        _playbackState.value = state
        updatePlaybackError(player.playerError)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        _playbackParameters.value = playbackParameters
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        _currentMediaItemIndex.value = player.currentMediaItemIndex
        _currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        _queueWindows.value = player.getQueueWindows()
        onTimelineUpdated(timeline, reason)
        _currentMediaItemIndex.value = player.currentMediaItemIndex
        _currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        _shuffleModeEnabled.value = enabled
        _queueWindows.value = player.getQueueWindows()
        _currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        _repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            onPlayerError.invoke(playbackError)
        }
        updatePlaybackError(playbackError)
    }

    private fun updatePlaybackError(playbackError: PlaybackException?) {
        when {
            playbackError == null -> {
                dismissedPlaybackError = null
                _error.value = null
            }

            playbackError !== dismissedPlaybackError -> {
                dismissedPlaybackError = null
                _error.value = playbackError
            }
        }
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window =
                player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            _canSkipPrevious.value =
                player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                    !window.isLive ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            _canSkipNext.value =
                (window.isLive && window.isDynamic) ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            _canSkipPrevious.value = false
            _canSkipNext.value = false
        }
    }

    fun dispose() {
        player.removeListener(this)
    }

    override fun close() {
        dispose()
    }
}

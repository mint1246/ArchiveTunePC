/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.playback.api.SleepTimerController

class SleepTimer(
    scope: CoroutineScope,
    val player: Player,
    service: MusicService,
) : Player.Listener {
    var triggerTime by mutableStateOf(-1L)
        private set
    var pauseWhenSongEnd by mutableStateOf(false)
        private set
    val isActive: Boolean
        get() = triggerTime != -1L || pauseWhenSongEnd

    private val controller = SleepTimerController(
        scope = scope,
        pause = service::pauseFromSleepTimer,
        onStateChanged = { state ->
            triggerTime = state.triggerTime
            pauseWhenSongEnd = state.pauseWhenSongEnd
        },
    )

    fun start(minute: Int) = controller.start(minute)

    fun clear() = controller.clear()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        controller.onSongTransition()
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) controller.onPlaybackEnded()
    }
}

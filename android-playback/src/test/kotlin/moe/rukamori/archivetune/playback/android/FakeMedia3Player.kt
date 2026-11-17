/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.lang.reflect.Proxy

internal data class FakeWindowSpec(
    val uid: Any,
    val mediaId: String,
    val isLive: Boolean = false,
    val isDynamic: Boolean = false,
)

internal class FakeTimeline(
    private val windows: List<FakeWindowSpec>,
    private val shuffledOrder: List<Int>? = null,
) : Timeline() {
    override fun getWindowCount(): Int = windows.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        val spec = windows[windowIndex]
        window.uid = spec.uid
        window.mediaItem = MediaItem.Builder().setMediaId(spec.mediaId).build()
        window.firstPeriodIndex = windowIndex
        window.lastPeriodIndex = windowIndex
        window.liveConfiguration = if (spec.isLive) MediaItem.LiveConfiguration.Builder().build() else null
        window.isDynamic = spec.isDynamic
        return window
    }

    override fun getPeriodCount(): Int = windows.size

    override fun getPeriod(
        periodIndex: Int,
        period: Period,
        setIds: Boolean,
    ): Period {
        val uid = windows[periodIndex].uid
        return period.set(uid, uid, periodIndex, C.TIME_UNSET, 0L)
    }

    override fun getIndexOfPeriod(uid: Any): Int = windows.indexOfFirst { it.uid == uid }

    override fun getUidOfPeriod(periodIndex: Int): Any = windows[periodIndex].uid

    override fun getNextWindowIndex(
        windowIndex: Int,
        repeatMode: Int,
        shuffleModeEnabled: Boolean,
    ): Int {
        if (shuffleModeEnabled && shuffledOrder != null) {
            if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
            val pos = shuffledOrder.indexOf(windowIndex)
            if (pos == -1) return C.INDEX_UNSET
            return when {
                pos + 1 < shuffledOrder.size -> shuffledOrder[pos + 1]
                repeatMode == Player.REPEAT_MODE_ALL && shuffledOrder.isNotEmpty() -> shuffledOrder.first()
                else -> C.INDEX_UNSET
            }
        }
        return super.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    }

    override fun getPreviousWindowIndex(
        windowIndex: Int,
        repeatMode: Int,
        shuffleModeEnabled: Boolean,
    ): Int {
        if (shuffleModeEnabled && shuffledOrder != null) {
            if (repeatMode == Player.REPEAT_MODE_ONE) return windowIndex
            val pos = shuffledOrder.indexOf(windowIndex)
            if (pos == -1) return C.INDEX_UNSET
            return when {
                pos - 1 >= 0 -> shuffledOrder[pos - 1]
                repeatMode == Player.REPEAT_MODE_ALL && shuffledOrder.isNotEmpty() -> shuffledOrder.last()
                else -> C.INDEX_UNSET
            }
        }
        return super.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    }
}

internal class FakeMedia3Player {
    var playbackState: Int = Player.STATE_IDLE
    var isPlaying: Boolean = false
    var playWhenReady: Boolean = false
    var repeatMode: Int = Player.REPEAT_MODE_OFF
    var shuffleModeEnabled: Boolean = false
    var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    var currentTimeline: Timeline = Timeline.EMPTY
    var currentMediaItemIndex: Int = 0
    var playerError: PlaybackException? = null
    val availableCommands: MutableSet<Int> = mutableSetOf()

    var prepareCount: Int = 0
    var seekToDefaultPositionCount: Int = 0
    var seekToNextCount: Int = 0
    var seekToPreviousCount: Int = 0

    val listeners: MutableList<Player.Listener> = mutableListOf()

    val player: Player =
        Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "addListener" -> {
                    listeners.add(args[0] as Player.Listener)
                    null
                }
                "removeListener" -> {
                    listeners.remove(args[0] as Player.Listener)
                    null
                }
                "getPlaybackState" -> playbackState
                "isPlaying" -> isPlaying
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> {
                    playWhenReady = args[0] as Boolean
                    null
                }
                "prepare" -> {
                    prepareCount++
                    null
                }
                "seekToDefaultPosition" -> {
                    seekToDefaultPositionCount++
                    null
                }
                "seekToNext" -> {
                    seekToNextCount++
                    null
                }
                "seekToPrevious" -> {
                    seekToPreviousCount++
                    null
                }
                "getRepeatMode" -> repeatMode
                "setRepeatMode" -> {
                    repeatMode = args[0] as Int
                    null
                }
                "getShuffleModeEnabled" -> shuffleModeEnabled
                "setShuffleModeEnabled" -> {
                    shuffleModeEnabled = args[0] as Boolean
                    null
                }
                "getPlaybackParameters" -> playbackParameters
                "getCurrentTimeline" -> currentTimeline
                "getCurrentMediaItemIndex" -> currentMediaItemIndex
                "getMediaItemCount" -> currentTimeline.windowCount
                "getMediaItemAt" ->
                    currentTimeline.getWindow(args[0] as Int, Timeline.Window()).mediaItem
                "getPlayerError" -> playerError
                "isCommandAvailable" -> (args[0] as Int) in availableCommands
                else -> defaultReturn(method.returnType)
            }
        } as Player

    fun dispatchPlaybackStateChanged(state: Int) {
        playbackState = state
        listeners.toList().forEach { it.onPlaybackStateChanged(state) }
    }

    fun dispatchIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        listeners.toList().forEach { it.onIsPlayingChanged(playing) }
    }

    fun dispatchPlaybackParametersChanged(parameters: PlaybackParameters) {
        playbackParameters = parameters
        listeners.toList().forEach { it.onPlaybackParametersChanged(parameters) }
    }

    fun dispatchMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
    ) {
        listeners.toList().forEach { it.onMediaItemTransition(mediaItem, reason) }
    }

    fun dispatchTimelineChanged(
        timeline: Timeline = currentTimeline,
        reason: Int = Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
    ) {
        currentTimeline = timeline
        listeners.toList().forEach { it.onTimelineChanged(timeline, reason) }
    }

    fun dispatchShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled = enabled
        listeners.toList().forEach { it.onShuffleModeEnabledChanged(enabled) }
    }

    fun dispatchRepeatModeChanged(mode: Int) {
        repeatMode = mode
        listeners.toList().forEach { it.onRepeatModeChanged(mode) }
    }

    fun dispatchPlayerErrorChanged(error: PlaybackException?) {
        playerError = error
        listeners.toList().forEach { it.onPlayerErrorChanged(error) }
    }

    private fun defaultReturn(returnType: Class<*>): Any? =
        when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.QueueDragDestination
import moe.rukamori.archivetune.playback.api.QueueReorderAction
import moe.rukamori.archivetune.playback.api.QueueReorderPlanner
import moe.rukamori.archivetune.playback.api.QueueReorderWindow
import moe.rukamori.archivetune.playback.api.RepeatMode

fun Int.toPlaybackRepeatMode(): RepeatMode =
    when (this) {
        Player.REPEAT_MODE_OFF -> RepeatMode.OFF
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> throw IllegalArgumentException("Unsupported Media3 repeat mode: $this")
    }

fun RepeatMode.toMedia3RepeatMode(): Int =
    when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

fun Timeline.Window.toQueueReorderWindow(): QueueReorderWindow =
    QueueReorderWindow(
        uid = uid,
        periodIndex = firstPeriodIndex,
    )

fun Player.planQueueReorder(
    draggedItemUid: Any,
    destination: QueueDragDestination,
): QueueReorderAction =
    QueueReorderPlanner.planReorder(
        windows = getQueueWindows().map { it.toQueueReorderWindow() },
        draggedItemUid = draggedItemUid,
        destination = destination,
        shuffleEnabled = shuffleModeEnabled,
    )

fun Player.toPlaybackStatus(): PlaybackStatus =
    if (playerError != null) {
        PlaybackStatus.ERROR
    } else {
        when (playbackState) {
            Player.STATE_IDLE -> PlaybackStatus.IDLE
            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            Player.STATE_READY -> PlaybackStatus.READY
            Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> PlaybackStatus.IDLE
        }
    }

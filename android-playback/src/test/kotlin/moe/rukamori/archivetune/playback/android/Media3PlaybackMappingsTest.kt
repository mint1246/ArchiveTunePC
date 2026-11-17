/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.QueueDragDestination
import moe.rukamori.archivetune.playback.api.QueueReorderAction
import moe.rukamori.archivetune.playback.api.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3PlaybackMappingsTest {
    @Test
    fun `repeat mode round trips between Media3 and playback-core`() {
        assertEquals(RepeatMode.OFF, Player.REPEAT_MODE_OFF.toPlaybackRepeatMode())
        assertEquals(RepeatMode.ONE, Player.REPEAT_MODE_ONE.toPlaybackRepeatMode())
        assertEquals(RepeatMode.ALL, Player.REPEAT_MODE_ALL.toPlaybackRepeatMode())

        assertEquals(Player.REPEAT_MODE_OFF, RepeatMode.OFF.toMedia3RepeatMode())
        assertEquals(Player.REPEAT_MODE_ONE, RepeatMode.ONE.toMedia3RepeatMode())
        assertEquals(Player.REPEAT_MODE_ALL, RepeatMode.ALL.toMedia3RepeatMode())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toPlaybackRepeatMode rejects unknown mode`() {
        (-1).toPlaybackRepeatMode()
    }

    @Test
    fun `planQueueReorder delegates using queue window order and shuffle mode`() {
        val timeline =
            FakeTimeline(
                windows =
                    listOf(
                        FakeWindowSpec(uid = "u0", mediaId = "m0"),
                        FakeWindowSpec(uid = "u1", mediaId = "m1"),
                        FakeWindowSpec(uid = "u2", mediaId = "m2"),
                    ),
                shuffledOrder = listOf(2, 0, 1),
            )
        val fake = FakeMedia3Player().apply {
            currentTimeline = timeline
            currentMediaItemIndex = 0
            shuffleModeEnabled = true
        }

        val action =
            fake.player.planQueueReorder(
                draggedItemUid = "u1",
                destination = QueueDragDestination.Start,
            )

        assertEquals(QueueReorderAction.UpdateShuffleOrder(intArrayOf(1, 2, 0)), action)
    }

    @Test
    fun `toPlaybackStatus maps player error and states`() {
        val fake = FakeMedia3Player()

        fake.playbackState = Player.STATE_IDLE
        assertEquals(PlaybackStatus.IDLE, fake.player.toPlaybackStatus())

        fake.playbackState = Player.STATE_BUFFERING
        assertEquals(PlaybackStatus.BUFFERING, fake.player.toPlaybackStatus())

        fake.playbackState = Player.STATE_READY
        assertEquals(PlaybackStatus.READY, fake.player.toPlaybackStatus())

        fake.playbackState = Player.STATE_ENDED
        assertEquals(PlaybackStatus.ENDED, fake.player.toPlaybackStatus())

        fake.playerError = PlaybackException("boom", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertEquals(PlaybackStatus.ERROR, fake.player.toPlaybackStatus())
    }
}

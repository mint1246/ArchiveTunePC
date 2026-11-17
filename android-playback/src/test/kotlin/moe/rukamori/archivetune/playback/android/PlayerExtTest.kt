/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import moe.rukamori.archivetune.extensions.findNextMediaItemById
import moe.rukamori.archivetune.extensions.getCurrentQueueIndex
import moe.rukamori.archivetune.extensions.getQueueWindows
import moe.rukamori.archivetune.extensions.mediaItems
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.extensions.toggleRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerExtTest {
    @Test
    fun `togglePlayPause prepares and enables playWhenReady from idle`() {
        val fake = FakeMedia3Player().apply {
            playbackState = Player.STATE_IDLE
            playWhenReady = false
        }

        fake.player.togglePlayPause()

        assertEquals(1, fake.prepareCount)
        assertEquals(0, fake.seekToDefaultPositionCount)
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun `togglePlayPause seeks to default position and plays when ended`() {
        val fake = FakeMedia3Player().apply {
            playbackState = Player.STATE_ENDED
            playWhenReady = false
        }

        fake.player.togglePlayPause()

        assertEquals(0, fake.prepareCount)
        assertEquals(1, fake.seekToDefaultPositionCount)
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun `togglePlayPause flips playWhenReady when ready`() {
        val fake = FakeMedia3Player().apply {
            playbackState = Player.STATE_READY
            playWhenReady = true
        }

        fake.player.togglePlayPause()
        assertFalse(fake.playWhenReady)

        fake.player.togglePlayPause()
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun `toggleRepeatMode cycles off all one off`() {
        val fake = FakeMedia3Player().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }

        fake.player.toggleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ALL, fake.repeatMode)

        fake.player.toggleRepeatMode()
        assertEquals(Player.REPEAT_MODE_ONE, fake.repeatMode)

        fake.player.toggleRepeatMode()
        assertEquals(Player.REPEAT_MODE_OFF, fake.repeatMode)
    }

    @Test(expected = IllegalStateException::class)
    fun `toggleRepeatMode throws on invalid repeat mode`() {
        val fake = FakeMedia3Player().apply {
            repeatMode = 99
        }
        fake.player.toggleRepeatMode()
    }

    @Test
    fun `getQueueWindows and getCurrentQueueIndex follow shuffle traversal order`() {
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

        val windows = fake.player.getQueueWindows()
        assertEquals(listOf("m2", "m0", "m1"), windows.map { it.mediaItem.mediaId })
        assertEquals(1, fake.player.getCurrentQueueIndex())
    }

    @Test
    fun `getQueueWindows and getCurrentQueueIndex handle empty timeline`() {
        val fake = FakeMedia3Player().apply {
            currentTimeline = Timeline.EMPTY
        }

        assertTrue(fake.player.getQueueWindows().isEmpty())
        assertEquals(-1, fake.player.getCurrentQueueIndex())
    }

    @Test
    fun `mediaItems and findNextMediaItemById search from current index`() {
        val timeline =
            FakeTimeline(
                windows =
                    listOf(
                        FakeWindowSpec(uid = "u0", mediaId = "dup"),
                        FakeWindowSpec(uid = "u1", mediaId = "mid"),
                        FakeWindowSpec(uid = "u2", mediaId = "dup"),
                    ),
            )
        val fake = FakeMedia3Player().apply {
            currentTimeline = timeline
            currentMediaItemIndex = 1
        }

        assertEquals(listOf("dup", "mid", "dup"), fake.player.mediaItems.map { it.mediaId })
        assertEquals("dup", fake.player.findNextMediaItemById("dup")?.mediaId)
        assertNull(fake.player.findNextMediaItemById("missing"))
    }
}

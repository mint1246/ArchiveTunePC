/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlayerStateObserverTest {
    @Test
    fun `initial state mirrors player and registers listener`() {
        val timeline =
            FakeTimeline(
                windows =
                    listOf(
                        FakeWindowSpec(uid = "u0", mediaId = "m0"),
                        FakeWindowSpec(uid = "u1", mediaId = "m1"),
                    ),
            )
        val fake = FakeMedia3Player().apply {
            playbackState = Player.STATE_READY
            isPlaying = true
            playbackParameters = PlaybackParameters(1.25f, 0.9f)
            currentTimeline = timeline
            currentMediaItemIndex = 1
            shuffleModeEnabled = false
            repeatMode = Player.REPEAT_MODE_ALL
        }

        val observer = Media3PlayerStateObserver(fake.player)

        assertEquals(1, fake.listeners.size)
        assertEquals(Player.STATE_READY, observer.playbackState.value)
        assertTrue(observer.isPlaying.value)
        assertEquals(PlaybackParameters(1.25f, 0.9f), observer.playbackParameters.value)
        assertEquals(listOf("m0", "m1"), observer.queueWindows.value.map { it.mediaItem.mediaId })
        assertEquals(1, observer.currentMediaItemIndex.value)
        assertEquals(1, observer.currentWindowIndex.value)
        assertFalse(observer.shuffleModeEnabled.value)
        assertEquals(Player.REPEAT_MODE_ALL, observer.repeatMode.value)
        assertTrue(observer.canSkipPrevious.value)
        assertTrue(observer.canSkipNext.value)
        assertNull(observer.error.value)

        observer.close()
        assertTrue(fake.listeners.isEmpty())
    }

    @Test
    fun `timeline and command updates drive skip availability and callback`() {
        val fake = FakeMedia3Player()
        var timelineCallbackCount = 0
        val observer =
            Media3PlayerStateObserver(
                player = fake.player,
                onTimelineUpdated = { _, _ -> timelineCallbackCount++ },
            )

        fake.dispatchTimelineChanged(Timeline.EMPTY)
        assertFalse(observer.canSkipPrevious.value)
        assertFalse(observer.canSkipNext.value)
        assertEquals(1, timelineCallbackCount)

        val liveTimeline =
            FakeTimeline(
                windows =
                    listOf(
                        FakeWindowSpec(uid = "live", mediaId = "live-id", isLive = true, isDynamic = true),
                    ),
            )
        fake.currentMediaItemIndex = 0
        fake.availableCommands.clear()
        fake.dispatchTimelineChanged(liveTimeline)
        assertFalse(observer.canSkipPrevious.value)
        assertTrue(observer.canSkipNext.value)
        assertEquals(2, timelineCallbackCount)

        fake.availableCommands.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        fake.dispatchRepeatModeChanged(Player.REPEAT_MODE_ONE)
        assertTrue(observer.canSkipPrevious.value)
        assertEquals(Player.REPEAT_MODE_ONE, observer.repeatMode.value)
    }

    @Test
    fun `dismissPlaybackError suppresses same error instance until cleared or replaced`() {
        val fake = FakeMedia3Player()
        val reported = mutableListOf<PlaybackException>()
        val observer =
            Media3PlayerStateObserver(
                player = fake.player,
                onPlayerError = { reported += it },
            )

        val firstError = PlaybackException("first", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        fake.dispatchPlayerErrorChanged(firstError)
        assertSame(firstError, observer.error.value)
        assertEquals(listOf(firstError), reported)

        observer.dismissPlaybackError()
        assertNull(observer.error.value)

        fake.dispatchPlaybackStateChanged(Player.STATE_IDLE)
        assertNull(observer.error.value)

        val secondError = PlaybackException("second", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        fake.dispatchPlayerErrorChanged(secondError)
        assertSame(secondError, observer.error.value)

        fake.dispatchPlayerErrorChanged(null)
        assertNull(observer.error.value)
    }

    @Test
    fun `seekToNext and seekToPrevious prepare and enable playWhenReady`() {
        val fake = FakeMedia3Player()
        val observer = Media3PlayerStateObserver(fake.player)

        observer.seekToNext()
        assertEquals(1, fake.seekToNextCount)
        assertEquals(1, fake.prepareCount)
        assertTrue(fake.playWhenReady)

        fake.playWhenReady = false
        observer.seekToPrevious()
        assertEquals(1, fake.seekToPreviousCount)
        assertEquals(2, fake.prepareCount)
        assertTrue(fake.playWhenReady)
    }
}

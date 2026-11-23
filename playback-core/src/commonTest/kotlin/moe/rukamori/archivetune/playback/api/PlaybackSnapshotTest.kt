/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSnapshotTest {
    private val item = PlaybackItem(PlaybackMetadata(id = "id", title = "Title"))

    @Test
    fun isPlaying_requires_ready_and_requested_playback() {
        assertTrue(
            PlaybackSnapshot(
                queue = listOf(item),
                currentIndex = 0,
                status = PlaybackStatus.READY,
                playWhenReady = true,
            ).isPlaying,
        )
        assertFalse(
            PlaybackSnapshot(
                queue = listOf(item),
                currentIndex = 0,
                status = PlaybackStatus.BUFFERING,
                playWhenReady = true,
            ).isPlaying,
        )
    }

    @Test
    fun loading_includes_resolution_engine_load_and_buffering() {
        assertTrue(PlaybackSnapshot(status = PlaybackStatus.RESOLVING).isLoading)
        assertTrue(PlaybackSnapshot(status = PlaybackStatus.LOADING).isLoading)
        assertTrue(PlaybackSnapshot(status = PlaybackStatus.BUFFERING).isLoading)
        assertFalse(PlaybackSnapshot(status = PlaybackStatus.READY).isLoading)
    }

    @Test
    fun skip_capabilities_use_navigator_state_and_current_position() {
        assertTrue(
            PlaybackSnapshot(
                queue = listOf(item),
                currentIndex = 0,
                hasNextItem = true,
            ).canSkipNext,
        )
        assertTrue(
            PlaybackSnapshot(
                queue = listOf(item),
                currentIndex = 0,
                positionMs = 1L,
            ).canSkipPrevious,
        )
        assertFalse(
            PlaybackSnapshot(
                queue = listOf(item),
                currentIndex = 0,
            ).canSkipNext,
        )
    }
}

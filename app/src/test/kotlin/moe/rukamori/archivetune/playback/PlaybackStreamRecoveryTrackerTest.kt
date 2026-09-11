/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStreamRecoveryTrackerTest {
    @Test
    fun allowsBoundedFallbackChainForSameMedia() {
        val tracker = PlaybackStreamRecoveryTracker(maxRetryAttemptsPerMedia = 3)

        assertTrue(tracker.registerRetryAttempt("song"))
        assertTrue(tracker.registerRetryAttempt("song"))
        assertTrue(tracker.registerRetryAttempt("song"))
        assertFalse(tracker.registerRetryAttempt("song"))
    }

    @Test
    fun readyPlaybackResetsRetryBudget() {
        val tracker = PlaybackStreamRecoveryTracker(maxRetryAttemptsPerMedia = 1)

        assertTrue(tracker.registerRetryAttempt("song"))
        assertFalse(tracker.registerRetryAttempt("song"))
        tracker.onPlaybackRecovered("song")
        assertTrue(tracker.registerRetryAttempt("song"))
    }

    @Test
    fun changingMediaGetsFreshRetryBudget() {
        val tracker = PlaybackStreamRecoveryTracker(maxRetryAttemptsPerMedia = 1)

        assertTrue(tracker.registerRetryAttempt("song-a"))
        tracker.onMediaItemChanged("song-b")
        assertTrue(tracker.registerRetryAttempt("song-b"))
    }
}

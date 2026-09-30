/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodcastPlaybackIdentityTest {
    @Test
    fun clickedEpisodeIsSelectedFromTheVisibleQueue() {
        assertEquals(1, requestedEpisodeQueueIndex(listOf("episode-a", "episode-c", "episode-b"), "episode-c"))
    }

    @Test
    fun missingClickedEpisodeDoesNotFallBackToQueueHead() {
        assertNull(requestedEpisodeQueueIndex(listOf("episode-a", "episode-b"), "episode-c"))
    }
}

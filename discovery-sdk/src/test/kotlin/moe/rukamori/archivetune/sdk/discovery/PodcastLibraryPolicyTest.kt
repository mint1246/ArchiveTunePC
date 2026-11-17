/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodcastLibraryPolicyTest {
    private data class Episode(
        val id: String,
        val inLibrary: Boolean,
    )

    @Test
    fun `shouldSyncPodcastSaveRemotely skips unsave without remote timestamp and blank playlists`() {
        assertNull(
            PodcastLibraryPolicy.shouldSyncPodcastSaveRemotely(
                save = false,
                hasRemoteSavedTimestamp = false,
                playlistId = "PL123",
            ),
        )
        assertNull(
            PodcastLibraryPolicy.shouldSyncPodcastSaveRemotely(
                save = true,
                hasRemoteSavedTimestamp = false,
                playlistId = "   ",
            ),
        )
        assertEquals(
            "PL123",
            PodcastLibraryPolicy.shouldSyncPodcastSaveRemotely(
                save = true,
                hasRemoteSavedTimestamp = false,
                playlistId = " PL123 ",
            ),
        )
        assertEquals(
            "PL123",
            PodcastLibraryPolicy.shouldSyncPodcastSaveRemotely(
                save = false,
                hasRemoteSavedTimestamp = true,
                playlistId = "PL123",
            ),
        )
    }

    @Test
    fun `reconcileRemoteSavedFlag preserves existing timestamp or assigns now when saved`() {
        assertEquals(100L, PodcastLibraryPolicy.reconcileRemoteSavedFlag(isRemotelySaved = true, existingRemoteSavedAt = 100L, now = 200L))
        assertEquals(200L, PodcastLibraryPolicy.reconcileRemoteSavedFlag(isRemotelySaved = true, existingRemoteSavedAt = null, now = 200L))
        assertNull(PodcastLibraryPolicy.reconcileRemoteSavedFlag(isRemotelySaved = false, existingRemoteSavedAt = 100L, now = 200L))
    }

    @Test
    fun `savedEpisodeIds returns ids of episodes in library`() {
        val episodes = listOf(Episode("e1", true), Episode("e2", false), Episode("e3", true))
        assertEquals(
            setOf("e1", "e3"),
            PodcastLibraryPolicy.savedEpisodeIds(
                episodes = episodes,
                isInLibrary = { it.inLibrary },
                idOf = { it.id },
            ),
        )
    }
}

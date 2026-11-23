/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioContinuationPolicyTest {
    private data class Track(
        val id: String,
        val isPodcast: Boolean = false,
    )

    @Test
    fun selectLocalMixItems_excludes_playlist_seeds_podcasts_duplicates_and_caps_size() {
        val relatedBySeed =
            mapOf(
                "seed-1" to listOf(Track("seed-2"), Track("pod-1", isPodcast = true), Track("r1"), Track("r2")),
                "seed-2" to listOf(Track("r2"), Track("r3"), Track("r4")),
            )
        val selected =
            RadioContinuationPolicy.selectLocalMixItems(
                playlistSongIds = listOf("seed-1", "seed-2"),
                relatedSongsForSeed = { relatedBySeed[it].orEmpty() },
                idOf = { it.id },
                isPodcast = { it.isPodcast },
                maxMixSize = 3,
            )
        assertEquals(listOf(Track("r1"), Track("r2"), Track("r3")), selected)
    }

    @Test
    fun mergeOnlineMixItems_excludes_playlist_songs_deduplicates_and_caps_size() {
        val merged =
            RadioContinuationPolicy.mergeOnlineMixItems(
                playlistSongIds = setOf("seed-1"),
                nextItems = listOf(Track("seed-1"), Track("n1"), Track("n2")),
                relatedItems = listOf(Track("n2"), Track("r1"), Track("r2")),
                idOf = { it.id },
                maxMixSize = 3,
            )
        assertEquals(listOf(Track("n1"), Track("n2"), Track("r1")), merged)
    }

    @Test
    fun sliceAlbumRadioContinuation_drops_album_prefix_on_first_load_safely() {
        val items = listOf("a1", "a2", "r1", "r2")
        assertEquals(
            listOf("r1", "r2"),
            RadioContinuationPolicy.sliceAlbumRadioContinuation(items, albumSongCount = 2, firstTimeLoaded = false),
        )
        assertEquals(
            emptyList(),
            RadioContinuationPolicy.sliceAlbumRadioContinuation(items, albumSongCount = 10, firstTimeLoaded = false),
        )
        assertEquals(
            items,
            RadioContinuationPolicy.sliceAlbumRadioContinuation(items, albumSongCount = 2, firstTimeLoaded = true),
        )
    }

    @Test
    fun filterLocalAlbumRadioItems_excludes_initial_songs_only_before_first_load() {
        val items = listOf(Track("a1"), Track("r1"), Track("a2"))
        assertEquals(
            listOf(Track("r1")),
            RadioContinuationPolicy.filterLocalAlbumRadioItems(
                items = items,
                initialSongIds = setOf("a1", "a2"),
                firstTimeLoaded = false,
                idOf = { it.id },
            ),
        )
        assertEquals(
            items,
            RadioContinuationPolicy.filterLocalAlbumRadioItems(
                items = items,
                initialSongIds = setOf("a1", "a2"),
                firstTimeLoaded = true,
                idOf = { it.id },
            ),
        )
    }

    @Test
    fun hasRadioNextPage_respects_localOnly_firstTimeLoaded_and_continuation() {
        assertFalse(RadioContinuationPolicy.hasRadioNextPage(localOnly = true, firstTimeLoaded = false, continuation = "c1"))
        assertTrue(RadioContinuationPolicy.hasRadioNextPage(localOnly = false, firstTimeLoaded = false, continuation = null))
        assertTrue(RadioContinuationPolicy.hasRadioNextPage(localOnly = false, firstTimeLoaded = true, continuation = "c1"))
        assertFalse(RadioContinuationPolicy.hasRadioNextPage(localOnly = false, firstTimeLoaded = true, continuation = null))
    }
}

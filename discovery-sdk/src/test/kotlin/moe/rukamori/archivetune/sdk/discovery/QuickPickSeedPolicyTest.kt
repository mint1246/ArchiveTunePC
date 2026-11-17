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

class QuickPickSeedPolicyTest {
    private data class Seed(
        val id: String,
        val primaryArtistKey: String,
    )

    private data class Song(
        val id: String,
        val title: String,
        val artistKeys: List<String>,
        val hasArtists: Boolean = artistKeys.isNotEmpty(),
    )

    @Test
    fun `isValidVideoId requires 11 non-blank characters`() {
        assertTrue(QuickPickSeedPolicy.isValidVideoId("dQw4w9WgXcQ"))
        assertFalse(QuickPickSeedPolicy.isValidVideoId("short12345"))
        assertFalse(QuickPickSeedPolicy.isValidVideoId("toolong12345"))
        assertFalse(QuickPickSeedPolicy.isValidVideoId("           "))
    }

    @Test
    fun `artistRecommendationKey prefers non-blank id and falls back to lowercase name`() {
        assertEquals("UC123", QuickPickSeedPolicy.artistRecommendationKey("UC123", "Artist A"))
        assertEquals("artist a", QuickPickSeedPolicy.artistRecommendationKey(null, "Artist A"))
        assertEquals("artist a", QuickPickSeedPolicy.artistRecommendationKey("   ", "Artist A"))
    }

    @Test
    fun `primaryArtistKey falls back from id to lowercase name to seed id`() {
        assertEquals("UC1", QuickPickSeedPolicy.primaryArtistKey("seed1234567", listOf("", "UC1"), listOf("Name")))
        assertEquals("name", QuickPickSeedPolicy.primaryArtistKey("seed1234567", emptyList(), listOf(" ", "Name")))
        assertEquals("seed1234567", QuickPickSeedPolicy.primaryArtistKey("seed1234567", emptyList(), emptyList()))
    }

    @Test
    fun `selectLocalSeeds deduplicates by primary artist key up to limit`() {
        val candidates =
            listOf(
                Seed("s1", "a1"),
                Seed("s2", "a1"),
                Seed("s3", "a2"),
                Seed("s4", "a3"),
            )
        assertEquals(
            listOf(Seed("s1", "a1"), Seed("s3", "a2")),
            QuickPickSeedPolicy.selectLocalSeeds(candidates, limit = 2) { it.primaryArtistKey },
        )
        assertEquals(emptyList(), QuickPickSeedPolicy.selectLocalSeeds(candidates, limit = 0) { it.primaryArtistKey })
    }

    @Test
    fun `rankRemoteSeeds filters invalid ids and ranks by artist frequency`() {
        val songs =
            listOf(
                Song("invalid-id", "Ignored", listOf("artist-top")),
                Song("vid00000001", "One", listOf("artist-rare")),
                Song("vid00000002", "Two", listOf("artist-top")),
                Song("vid00000003", "Three", listOf("artist-top")),
                Song("vid00000002", "Two Dup", listOf("artist-top")),
            )
        val ranked =
            QuickPickSeedPolicy.rankRemoteSeeds(
                songs = songs,
                limit = 2,
                idOf = { it.id },
                artistKeysOf = { it.artistKeys },
            )
        assertEquals(listOf("vid00000002", "vid00000003"), ranked.map { it.id })
    }

    @Test
    fun `mergeSeeds supplements local seeds with remote seeds distinct by id and artist`() {
        val local = listOf(Seed("vid00000001", "artist-1"))
        val remote =
            listOf(
                Seed("vid00000001", "artist-other"),
                Seed("vid00000002", "artist-1"),
                Seed("vid00000003", "artist-2"),
                Seed("vid00000004", "artist-3"),
            )
        val merged =
            QuickPickSeedPolicy.mergeSeeds(
                localSeeds = local,
                remoteSeeds = remote,
                limit = 2,
                idOf = { it.id },
                primaryArtistKeyOf = { it.primaryArtistKey },
            )
        assertEquals(listOf("vid00000001", "vid00000003"), merged.map { it.id })
    }

    @Test
    fun `filterValidRecommendations excludes seed invalid ids blank titles artistless songs and duplicates`() {
        val seedId = "seed0000001"
        val songs =
            listOf(
                Song(seedId, "Self", listOf("a1")),
                Song("short", "Short Id", listOf("a1")),
                Song("vid00000001", "   ", listOf("a1")),
                Song("vid00000002", "No Artist", emptyList(), hasArtists = false),
                Song("vid00000003", "Valid First", listOf("a1")),
                Song("vid00000003", "Valid Duplicate", listOf("a1")),
                Song("vid00000004", "Valid Second", listOf("a2")),
            )
        val filtered =
            QuickPickSeedPolicy.filterValidRecommendations(
                songs = songs,
                seedSongId = seedId,
                idOf = { it.id },
                titleOf = { it.title },
                hasArtists = { it.hasArtists },
            )
        assertEquals(listOf("vid00000003", "vid00000004"), filtered.map { it.id })
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RelatedSongsResolverTest {
    private data class Song(
        val id: String,
        val title: String,
    )

    private val seedId = "seed0000001"

    @Test
    fun returns_primary_radio_recommendations_without_invoking_fallbacks() =
        runTest {
            var searchCalled = false
            var localCalled = false
            val result =
                RelatedSongsResolver.resolve(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = {
                        RelatedSongsResolver.PrimaryOutcome(
                            songs = listOf(Song(seedId, "Self"), Song("vid00000002", "Radio Hit")),
                        )
                    },
                    loadSearchFallback = {
                        searchCalled = true
                        Result.success(emptyList())
                    },
                    loadLocalFallback = {
                        localCalled = true
                        emptyList()
                    },
                )
            assertTrue(result.isSuccess)
            assertEquals(listOf(Song("vid00000002", "Radio Hit")), result.getOrNull())
            assertFalse(searchCalled)
            assertFalse(localCalled)
        }

    @Test
    fun falls_back_to_search_when_primary_yields_no_valid_recommendations() =
        runTest {
            var localCalled = false
            val result =
                RelatedSongsResolver.resolve(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = {
                        RelatedSongsResolver.PrimaryOutcome(
                            songs = listOf(Song(seedId, "Only Self")),
                            error = IllegalStateException("primary failed"),
                        )
                    },
                    loadSearchFallback = {
                        Result.success(listOf(Song("vid00000003", "Search Match")))
                    },
                    loadLocalFallback = {
                        localCalled = true
                        emptyList()
                    },
                )
            assertTrue(result.isSuccess)
            assertEquals(listOf(Song("vid00000003", "Search Match")), result.getOrNull())
            assertFalse(localCalled)
        }

    @Test
    fun falls_back_to_local_cached_songs_when_primary_and_search_are_empty() =
        runTest {
            val result =
                RelatedSongsResolver.resolve(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = { RelatedSongsResolver.PrimaryOutcome(emptyList()) },
                    loadSearchFallback = { Result.success(emptyList()) },
                    loadLocalFallback = { listOf(Song("vid00000004", "Cached Match")) },
                )
            assertTrue(result.isSuccess)
            assertEquals(listOf(Song("vid00000004", "Cached Match")), result.getOrNull())
        }

    @Test
    fun returns_empty_success_when_all_stages_are_empty_and_primary_succeeded() =
        runTest {
            val result =
                RelatedSongsResolver.resolve<Song>(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = { RelatedSongsResolver.PrimaryOutcome(emptyList(), error = null) },
                    loadSearchFallback = { Result.success(emptyList()) },
                    loadLocalFallback = { emptyList() },
                )
            assertTrue(result.isSuccess)
            assertEquals(emptyList<Song>(), result.getOrNull())
        }

    @Test
    fun returns_primary_failure_when_all_stages_are_empty_and_primary_failed() =
        runTest {
            val primaryError = IllegalStateException("network down")
            val result =
                RelatedSongsResolver.resolve<Song>(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = { RelatedSongsResolver.PrimaryOutcome(emptyList(), error = primaryError) },
                    loadSearchFallback = { Result.failure(IllegalStateException("search also failed")) },
                    loadLocalFallback = { emptyList() },
                )
            assertTrue(result.isFailure)
            assertSame(primaryError, result.exceptionOrNull())
        }

    @Test
    fun rethrows_cancellation_from_primary_outcome_or_fallback_stages() =
        runTest {
            assertFailsWith<CancellationException> {
                RelatedSongsResolver.resolve<Song>(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = { RelatedSongsResolver.PrimaryOutcome(emptyList(), CancellationException("primary")) },
                    loadSearchFallback = { Result.success(emptyList()) },
                    loadLocalFallback = { emptyList() },
                )
            }
            assertFailsWith<CancellationException> {
                RelatedSongsResolver.resolve<Song>(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = { RelatedSongsResolver.PrimaryOutcome(emptyList()) },
                    loadSearchFallback = { Result.failure(CancellationException("search")) },
                    loadLocalFallback = { emptyList() },
                )
            }
            assertFailsWith<CancellationException> {
                RelatedSongsResolver.resolve<Song>(
                    seedSongId = seedId,
                    idOf = { it.id },
                    titleOf = { it.title },
                    loadPrimary = { RelatedSongsResolver.PrimaryOutcome(emptyList()) },
                    loadSearchFallback = { Result.success(emptyList()) },
                    loadLocalFallback = { throw CancellationException("local") },
                )
            }
        }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LyricsFetchCoordinatorTest {
    private val query =
        LyricsQuery(
            mediaId = "video-1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            duration = 180,
        )

    @Test
    fun fetchOrdered_providerPriorityWinsOverRicherLowerPriorityResult() = runBlocking {
        val coordinator = coordinator()
        val high = fakeProvider("high") { Result.success("short") }
        val low = fakeProvider("low") { Result.success("a much richer lower-priority translation") }

        assertEquals("short", coordinator.fetchOrdered(listOf(high, low), query))
    }

    @Test
    fun fetchOrdered_skipsNotFoundAndPreservesUnicodeExactly() = runBlocking {
        val unicode = "こんにちは世界 🌍 שלום مرحبا émoji"
        val coordinator = appLikeCoordinator()
        val empty = fakeProvider("empty") { Result.success("   ") }
        val unicodeProvider = fakeProvider("unicode") { Result.success("  $unicode  ") }

        assertEquals(unicode, coordinator.fetchOrdered(listOf(empty, unicodeProvider), query))
    }

    @Test
    fun fetchOrdered_triesCanonicalTitleThenOriginalTitle() = runBlocking {
        val titles = mutableListOf<String>()
        val coordinator = appLikeCoordinator()
        val provider =
            fakeProvider("provider", getLyrics = { title ->
                titles += title
                if (title == "My Song") Result.success("  ") else Result.success("fallback lyrics")
            })

        val result =
            coordinator.fetchOrdered(
                listOf(provider),
                query.copy(title = "My Song (feat. Guest) [Official Music Video]"),
            )

        assertEquals("fallback lyrics", result)
        assertEquals(
            listOf("My Song", "My Song (feat. Guest) [Official Music Video]"),
            titles,
        )
    }

    @Test
    fun fetchOrdered_returnsNotFoundWhenNoProviderHasLyrics() = runBlocking {
        val coordinator = appLikeCoordinator()
        val first = fakeProvider("first") { Result.success("  ") }
        val second = fakeProvider("second") { Result.failure<String>(IllegalStateException("network")) }

        assertEquals(
            LyricsFetchCoordinator.LYRICS_NOT_FOUND,
            coordinator.fetchOrdered(listOf(first, second), query),
        )
    }

    @Test
    fun fetchOrdered_timeoutIsSoftFailureAndLowerPriorityProviderStillWins() = runBlocking {
        var timedOutPhase = ""
        val coordinator =
            coordinator(
                timeoutMs = 80L,
                onTimeout = { _, phase -> timedOutPhase = phase },
            )
        val slow =
            fakeProvider("slow", getLyrics = {
                delay(10_000L)
                Result.success("slow lyrics")
            })
        val fast = fakeProvider("fast") { Result.success("fast lyrics") }

        assertEquals("fast lyrics", coordinator.fetchOrdered(listOf(slow, fast), query))
        assertEquals("automatic", timedOutPhase)
    }

    @Test
    fun fetchOrdered_reportsProviderFailureAndContinues() = runBlocking {
        val reported = AtomicInteger()
        val coordinator = coordinator(onError = { reported.incrementAndGet() })
        val failing = fakeProvider("failing") { Result.failure<String>(IllegalStateException("boom")) }
        val working = fakeProvider("working") { Result.success("recovered") }

        assertEquals("recovered", coordinator.fetchOrdered(listOf(failing, working), query))
        assertEquals(1, reported.get())
    }

    @Test
    fun fetchOrdered_rethrowsCancellation() = runBlocking {
        val coordinator = coordinator()
        val cancelled =
            fakeProvider("cancelled", getLyrics = { throw CancellationException("caller cancelled") })

        assertFailsWith<CancellationException> {
            coordinator.fetchOrdered(listOf(cancelled), query)
        }
    }

    @Test
    fun searchAll_emitsOrderedUpdatesAndAggregatesResults() = runBlocking {
        val coordinator = appLikeCoordinator()
        val updates = java.util.Collections.synchronizedList(mutableListOf<LyricsProviderSearchUpdate>())
        val first =
            fakeProvider(
                name = "first",
                getAllLyrics = { _, callback ->
                    callback("第一行")
                    callback("第二行")
                },
            )
        val second = fakeProvider(name = "second", getAllLyrics = { _, _ -> })

        val results =
            coordinator.searchAll(listOf(first, second), query) { updates += it }

        assertEquals(
            listOf(
                LyricsResult("first", "第一行"),
                LyricsResult("first", "第二行"),
            ),
            results,
        )
        // Updates arrive in completion order (providers run concurrently), so assert by provider.
        assertEquals(setOf(0, 1), updates.map(LyricsProviderSearchUpdate::providerIndex).toSet())
        assertEquals(
            mapOf(
                "first" to LyricsProviderSearchStatus.FOUND,
                "second" to LyricsProviderSearchStatus.NO_MATCH,
            ),
            updates.associate {
                it.providerName to it.status
            },
        )
    }

    @Test
    fun searchAll_withoutNetworkEmitsFailedForEveryProvider() = runBlocking {
        val coordinator =
            appLikeCoordinator(isNetworkAvailable = { false })
        val updates = mutableListOf<LyricsProviderSearchUpdate>()
        val first = fakeProvider("first")
        val second = fakeProvider("second")

        val results = coordinator.searchAll(listOf(first, second), query) { updates += it }

        assertTrue(results.isEmpty())
        assertEquals(
            listOf(LyricsProviderSearchStatus.FAILED, LyricsProviderSearchStatus.FAILED),
            updates.map(LyricsProviderSearchUpdate::status),
        )
        assertEquals(listOf("first", "second"), updates.map(LyricsProviderSearchUpdate::providerName))
    }

    @Test
    fun searchAll_rethrowsCancellation() = runBlocking {
        val coordinator = coordinator()
        val cancelled =
            fakeProvider(
                name = "cancelled",
                getAllLyrics = { _, _ -> throw CancellationException("caller cancelled") },
            )

        assertFailsWith<CancellationException> {
            coordinator.searchAll(listOf(cancelled), query) {}
        }
    }

    @Test
    fun titleVariants_stripSuffixesAndKeepUnicodeCodePoints() {
        assertEquals(
            listOf("歌曲", "歌曲 (feat. 歌手) [Official Music Video]"),
            LyricsQueryPolicy.titleVariants("歌曲 (feat. 歌手) [Official Music Video]"),
        )
        assertEquals(listOf("歌詞"), LyricsQueryPolicy.titleVariants("  歌詞  "))
        assertTrue(LyricsQueryPolicy.titleVariants("   ").isEmpty())
    }

    @Test
    fun cacheKey_remainsTrackScopedAndUsesNulMetadataSeparator() {
        assertEquals("id:video-a", LyricsQueryPolicy.cacheKey("video-a", "曲", "歌手"))
        assertEquals("meta:歌手\u0000曲", LyricsQueryPolicy.cacheKey(null, " 曲 ", " 歌手 "))
    }

    private fun coordinator(
        timeoutMs: Long = 1_000L,
        onError: (Throwable) -> Unit = {},
        onTimeout: (String, String) -> Unit = { _, _ -> },
        isNetworkAvailable: () -> Boolean = { true },
    ) =
        LyricsFetchCoordinator(
            LyricsFetchCoordinatorConfig(
                timeoutMs = timeoutMs,
                onError = onError,
                onTimeout = onTimeout,
                isNetworkAvailable = isNetworkAvailable,
            ),
        )

    /** Mirrors the Android seams: lyricsOrNotFound maps blank text to the not-found sentinel. */
    private fun appLikeCoordinator(isNetworkAvailable: () -> Boolean = { true }) =
        LyricsFetchCoordinator(
            LyricsFetchCoordinatorConfig(
                normalize = { lyrics ->
                    if (lyrics.isBlank()) LyricsFetchCoordinator.LYRICS_NOT_FOUND else lyrics.trim()
                },
                isMeaningful = { it != LyricsFetchCoordinator.LYRICS_NOT_FOUND },
                isNetworkAvailable = isNetworkAvailable,
            ),
        )

    private fun fakeProvider(
        name: String,
        getAllLyrics: suspend (title: String, callback: (String) -> Unit) -> Unit = { _, _ -> },
        getLyrics: suspend (title: String) -> Result<String> = {
            Result.success(LyricsFetchCoordinator.LYRICS_NOT_FOUND)
        },
    ) =
        object : LyricsFetchProvider {
            override val name: String = name

            override suspend fun getLyrics(
                id: String,
                title: String,
                artist: String,
                album: String?,
                duration: Int,
            ): Result<String> = getLyrics(title)

            override suspend fun getAllLyrics(
                id: String,
                title: String,
                artist: String,
                album: String?,
                duration: Int,
                callback: (String) -> Unit,
            ) = getAllLyrics(title, callback)
        }
}

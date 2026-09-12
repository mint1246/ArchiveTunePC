/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LyricsRequestCacheTest {
    @Test
    fun concurrentIdenticalSearches_shareOneFetch() = runBlocking {
        val cache = LyricsRequestCache()
        cache.alignProviderConfiguration(listOf("first", "second"))
        val request = request(providers = listOf("first", "second"))
        val fetchCount = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        suspend fun fetch(): List<LyricsResult> {
            fetchCount.incrementAndGet()
            release.await()
            return listOf(LyricsResult("first", "lyrics"))
        }

        val first = async(Dispatchers.Default) { cache.getOrFetchSearch(request, false, ::fetch) }
        while (fetchCount.get() == 0) delay(1)
        val second = async(Dispatchers.Default) { cache.getOrFetchSearch(request, false, ::fetch) }
        delay(20)
        release.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals(1, fetchCount.get())
    }

    @Test
    fun normalSearch_hitsCacheInsideTtl() = runBlocking {
        var now = 1_000L
        val cache = LyricsRequestCache(searchTtlMs = 500L, clockMs = { now })
        cache.alignProviderConfiguration(listOf("first"))
        val request = request()
        var fetchCount = 0

        repeat(2) {
            cache.getOrFetchSearch(request, false) {
                fetchCount++
                listOf(LyricsResult("first", "lyrics"))
            }
        }

        assertEquals(1, fetchCount)
        now += 100L
        cache.getOrFetchSearch(request, false) {
            fetchCount++
            listOf(LyricsResult("first", "new"))
        }
        assertEquals(1, fetchCount)
    }

    @Test
    fun explicitRefresh_bypassesCache() = runBlocking {
        val cache = LyricsRequestCache()
        cache.alignProviderConfiguration(listOf("first"))
        val request = request()
        var fetchCount = 0

        val original = cache.getOrFetchSearch(request, false) {
            fetchCount++
            listOf(LyricsResult("first", "old"))
        }
        val refreshed = cache.getOrFetchSearch(request, true) {
            fetchCount++
            listOf(LyricsResult("first", "fresh"))
        }

        assertEquals("old", original.single().lyrics)
        assertEquals("fresh", refreshed.single().lyrics)
        assertEquals(2, fetchCount)
    }

    @Test
    fun providerConfigurationChange_invalidatesOldEntries() = runBlocking {
        val cache = LyricsRequestCache()
        val firstRequest = request(providers = listOf("first"))
        var fetchCount = 0

        cache.alignProviderConfiguration(listOf("first"))
        cache.getOrFetchSearch(firstRequest, false) {
            fetchCount++
            listOf(LyricsResult("first", "first-result"))
        }

        cache.alignProviderConfiguration(listOf("second"))
        cache.getOrFetchSearch(request(providers = listOf("second")), false) {
            fetchCount++
            listOf(LyricsResult("second", "second-result"))
        }

        cache.alignProviderConfiguration(listOf("first"))
        cache.getOrFetchSearch(firstRequest, false) {
            fetchCount++
            listOf(LyricsResult("first", "fresh-first-result"))
        }

        assertEquals(3, fetchCount)
    }

    @Test
    fun expiredSearchEntry_refetches() = runBlocking {
        var now = 10_000L
        val cache = LyricsRequestCache(searchTtlMs = 100L, clockMs = { now })
        cache.alignProviderConfiguration(listOf("first"))
        val request = request()
        var fetchCount = 0

        cache.getOrFetchSearch(request, false) {
            fetchCount++
            listOf(LyricsResult("first", "old"))
        }
        now += 101L
        val refreshed = cache.getOrFetchSearch(request, false) {
            fetchCount++
            listOf(LyricsResult("first", "fresh"))
        }

        assertEquals(2, fetchCount)
        assertEquals("fresh", refreshed.single().lyrics)
    }

    @Test
    fun byteBound_evictsLeastRecentlyUsedSearchEntry() = runBlocking {
        val cache = LyricsRequestCache(searchCacheBytes = 20, clockMs = { 0L })
        cache.alignProviderConfiguration(listOf("first"))
        var fetchCount = 0

        suspend fun fetch(key: String): List<LyricsResult> =
            cache.getOrFetchSearch(request(mediaId = key), false) {
                fetchCount++
                listOf(LyricsResult("first", "12345")) // 10 bytes by upstream UTF-16 estimate.
            }

        fetch("one")
        fetch("two")
        fetch("three")
        fetch("one")

        assertEquals(4, fetchCount)
    }

    @Test
    fun providerOrder_remainsAuthoritative() {
        val lowerPriorityRicherLyrics = "[00:01.00]<00:01.00>word synced"
        assertEquals(
            "plain first-provider result",
            selectProviderOrderedLyrics(
                listOf(
                    "plain first-provider result",
                    lowerPriorityRicherLyrics,
                ),
            ),
        )
    }

    private fun request(
        mediaId: String = "song",
        providers: List<String> = listOf("first"),
    ) = LyricsRequestKey(
        mediaId = mediaId,
        title = "Title",
        artists = "Artist",
        album = "Album",
        duration = 180,
        providers = providers,
    )
}

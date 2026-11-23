/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun selectProviderOrderedLyrics(results: List<String?>): String? = results.firstOrNull { it != null }

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

data class LyricsRequestKey(
    val mediaId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val duration: Int,
    val providers: List<String>,
)

class LyricsRequestCache(
    private val searchCacheBytes: Int = DEFAULT_SEARCH_CACHE_BYTES,
    private val singleCacheBytes: Int = DEFAULT_SINGLE_CACHE_BYTES,
    private val searchTtlMs: Long = DEFAULT_SEARCH_TTL_MS,
    private val clockMs: () -> Long = ::lyricsMonotonicMillis,
) {
    private val lock = LyricsLock()
    private var generation = 0L
    private var providerConfiguration: List<String>? = null

    private val searchCache =
        ByteBoundedLruCache<LyricsRequestKey, CachedResults>(searchCacheBytes) { cached ->
            cached.results.sumOf { result -> result.lyrics.length.toLong() * 2L }
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        }
    private val singleCache =
        ByteBoundedLruCache<LyricsRequestKey, String>(singleCacheBytes) { lyrics ->
            (lyrics.length.toLong() * 2L)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        }

    private val singleInFlight = mutableMapOf<LyricsRequestKey, CompletableDeferred<String>>()
    private val searchInFlight = mutableMapOf<LyricsRequestKey, CompletableDeferred<List<LyricsResult>>>()

    fun alignProviderConfiguration(providerNames: List<String>) {
        lock.withLock {
            val normalized = providerNames.toList()
            val previous = providerConfiguration
            if (previous == null) {
                providerConfiguration = normalized
            } else if (previous != normalized) {
                providerConfiguration = normalized
                generation++
                searchCache.evictAll()
                singleCache.evictAll()
                singleInFlight.clear()
                searchInFlight.clear()
            }
        }
    }

    suspend fun getOrFetchSingle(
        request: LyricsRequestKey,
        forceRefresh: Boolean,
        cacheable: (String) -> Boolean,
        fetch: suspend () -> String,
    ): String {
        if (forceRefresh) invalidate(request)

        while (true) {
            currentCoroutineContext().ensureActive()
            val candidate = CompletableDeferred<String>()
            var generationAtStart = 0L
            var shared: CompletableDeferred<String>? = null
            val hit = lock.withLock {
                singleCache[request] ?: run {
                    generationAtStart = generation
                    shared = singleInFlight.getOrPut(request) { candidate }
                    null
                }
            }
            if (hit != null) return hit
            val active = shared!!

            if (active !== candidate) {
                try {
                    return active.await()
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            try {
                val value = fetch()
                currentCoroutineContext().ensureActive()
                lock.withLock {
                    if (generationAtStart == generation && cacheable(value)) {
                        singleCache.put(request, value)
                    }
                }
                active.complete(value)
                return value
            } catch (throwable: Throwable) {
                lock.withLock {
                    if (singleInFlight[request] === active) singleInFlight.remove(request)
                }
                active.completeExceptionally(throwable)
                throw throwable
            } finally {
                lock.withLock {
                    if (singleInFlight[request] === active) singleInFlight.remove(request)
                }
            }
        }
    }

    suspend fun getOrFetchSearch(
        request: LyricsRequestKey,
        forceRefresh: Boolean,
        fetch: suspend () -> List<LyricsResult>,
    ): List<LyricsResult> {
        if (forceRefresh) invalidate(request)

        while (true) {
            currentCoroutineContext().ensureActive()
            val candidate = CompletableDeferred<List<LyricsResult>>()
            var generationAtStart = 0L
            var shared: CompletableDeferred<List<LyricsResult>>? = null
            val hit = lock.withLock {
                val now = clockMs()
                val cached = searchCache[request]
                if (cached != null) {
                    if (now < cached.expiresAtMs) {
                        cached.results
                    } else {
                        searchCache.remove(request)
                        generationAtStart = generation
                        shared = searchInFlight.getOrPut(request) { candidate }
                        null
                    }
                } else {
                    generationAtStart = generation
                    shared = searchInFlight.getOrPut(request) { candidate }
                    null
                }
            }
            if (hit != null) return hit
            val active = shared!!

            if (active !== candidate) {
                try {
                    return active.await()
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            try {
                val results = fetch()
                currentCoroutineContext().ensureActive()
                lock.withLock {
                    if (generationAtStart == generation && results.isNotEmpty()) {
                        searchCache.put(
                            request,
                            CachedResults(
                                results = results.toList(),
                                expiresAtMs = clockMs() + searchTtlMs,
                            ),
                        )
                    }
                }
                active.complete(results)
                return results
            } catch (throwable: Throwable) {
                lock.withLock {
                    if (searchInFlight[request] === active) searchInFlight.remove(request)
                }
                active.completeExceptionally(throwable)
                throw throwable
            } finally {
                lock.withLock {
                    if (searchInFlight[request] === active) searchInFlight.remove(request)
                }
            }
        }
    }

    fun clear() {
        lock.withLock {
            generation++
            searchCache.evictAll()
            singleCache.evictAll()
            singleInFlight.clear()
            searchInFlight.clear()
        }
    }

    fun invalidate(request: LyricsRequestKey) {
        lock.withLock {
            generation++
            searchCache.remove(request)
            singleCache.remove(request)
            singleInFlight.remove(request)
            searchInFlight.remove(request)
        }
    }

    private data class CachedResults(
        val results: List<LyricsResult>,
        val expiresAtMs: Long,
    )

    companion object {
        internal const val DEFAULT_SEARCH_CACHE_BYTES = 8 * 1024 * 1024
        internal const val DEFAULT_SINGLE_CACHE_BYTES = 4 * 1024 * 1024
        internal const val DEFAULT_SEARCH_TTL_MS = 120_000L
    }
}

internal class ByteBoundedLruCache<K, V>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {
    private val entries = LinkedHashMap<K, V>()
    private var currentBytes = 0

    operator fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun put(key: K, value: V) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val weight = sizeOf(value).coerceAtLeast(1)
        entries.remove(key)?.let { previous -> currentBytes -= sizeOf(previous).coerceAtLeast(1) }
        if (weight > maxBytes) return

        entries[key] = value
        currentBytes += weight
        trimToSize()
    }

    fun remove(key: K): V? =
        entries.remove(key)?.also { previous ->
            currentBytes -= sizeOf(previous).coerceAtLeast(1)
        }

    fun evictAll() {
        entries.clear()
        currentBytes = 0
    }

    private fun trimToSize() {
        while (currentBytes > maxBytes && entries.isNotEmpty()) {
            val eldestKey = entries.keys.first()
            val eldestValue = entries.remove(eldestKey) ?: break
            currentBytes -= sizeOf(eldestValue).coerceAtLeast(1)
        }
    }
}

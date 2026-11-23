/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * A provider that can be queried for lyrics without any platform dependency.
 *
 * Android supplies an adapter over its own [moe.rukamori.archivetune.lyrics.LyricsProvider]
 * instances: the platform-owned `isEnabled(Context)` check and DataStore-backed ordering stay
 * in the application, while this SDK owns ordering of results, timeouts, bounded concurrency
 * and cancellation semantics.
 */
interface LyricsFetchProvider {
    val name: String

    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String>

    suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback)
    }
}

/** Normalized request passed to a [LyricsFetchCoordinator]. Strings are kept as Unicode. */
data class LyricsQuery(
    val mediaId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val duration: Int,
)

enum class LyricsProviderSearchStatus {
    SEARCHING,
    FOUND,
    NO_MATCH,
    TIMED_OUT,
    FAILED,
}

data class LyricsProviderSearchUpdate(
    val providerIndex: Int,
    val providerName: String,
    val status: LyricsProviderSearchStatus,
    val results: List<LyricsResult> = emptyList(),
)

/**
 * Platform seams for a [LyricsFetchCoordinator]. Defaults keep the coordinator usable headlessly;
 * Android injects its real normalization, error reporting, logging and network observation.
 */
data class LyricsFetchCoordinatorConfig(
    val maxConcurrentProviders: Int = 6,
    val timeoutMs: Long = LyricsQueryPolicy.PROVIDER_TIMEOUT_MS,
    val normalize: (String) -> String = { it },
    val isMeaningful: (String) -> Boolean = { it.isNotBlank() },
    val onError: (Throwable) -> Unit = {},
    val onTimeout: (providerName: String, phase: String) -> Unit = { _, _ -> },
    val isNetworkAvailable: () -> Boolean = { true },
)

/**
 * Headless lyrics provider orchestration extracted from Android's `LyricsHelper`.
 *
 * Preserved behavior:
 * - Provider **priority is authoritative**. [fetchOrdered] queries every enabled provider
 *   concurrently (bounded by [LyricsFetchCoordinatorConfig.maxConcurrentProviders]) but returns
 *   the first meaningful result in the supplied order, so a richer lower-priority response can
 *   never silently win.
 * - **Unicode** titles are reduced to query variants by [LyricsQueryPolicy], and returned lyric
 *   strings are passed through the caller-supplied normalization untouched otherwise.
 * - **Cancellation** is never swallowed: timeout is a per-provider soft failure, while a real
 *   [CancellationException] is always rethrown to the caller.
 */
class LyricsFetchCoordinator(
    private val config: LyricsFetchCoordinatorConfig = LyricsFetchCoordinatorConfig(),
) {
    private val providerPermits = Semaphore(config.maxConcurrentProviders.coerceAtLeast(1))

    /**
     * Returns the highest-priority meaningful lyric text, or [LYRICS_NOT_FOUND] when no provider
     * produced usable content.
     */
    suspend fun fetchOrdered(
        providers: List<LyricsFetchProvider>,
        query: LyricsQuery,
    ): String {
        if (providers.isEmpty()) return LYRICS_NOT_FOUND
        val results =
            supervisorScope {
                providers
                    .map { provider ->
                        async(lyricsIoDispatcher) {
                            providerPermits.withPermit { fetchProvider(provider, query) }
                        }
                    }.map { it.await() }
            }
        return selectProviderOrderedLyrics(results) ?: LYRICS_NOT_FOUND
    }

    /**
     * Queries all providers for search results, emitting one update per provider as each completes.
     * The caller emits the initial SEARCHING updates so a cache hit keeps its original behavior.
     */
    suspend fun searchAll(
        providers: List<LyricsFetchProvider>,
        query: LyricsQuery,
        onProviderUpdate: (LyricsProviderSearchUpdate) -> Unit,
    ): List<LyricsResult> {
        if (!config.isNetworkAvailable()) {
            providers.forEachIndexed { index, provider ->
                onProviderUpdate(
                    LyricsProviderSearchUpdate(
                        providerIndex = index,
                        providerName = provider.name,
                        status = LyricsProviderSearchStatus.FAILED,
                    ),
                )
            }
            return emptyList()
        }

        val outcomes =
            supervisorScope {
                providers
                    .mapIndexed { index, provider ->
                        async(lyricsIoDispatcher) {
                            val outcome =
                                providerPermits.withPermit { fetchAllFromProvider(provider, query) }
                            onProviderUpdate(
                                LyricsProviderSearchUpdate(
                                    providerIndex = index,
                                    providerName = provider.name,
                                    status = outcome.status,
                                    results = outcome.results,
                                ),
                            )
                            outcome
                        }
                    }.map { it.await() }
            }
        return outcomes.flatMap(ProviderSearchOutcome::results)
    }

    private suspend fun fetchProvider(
        provider: LyricsFetchProvider,
        query: LyricsQuery,
    ): String? =
        try {
            withTimeout(config.timeoutMs) {
                for (title in LyricsQueryPolicy.titleVariants(query.title)) {
                    val lyrics =
                        provider
                            .getLyrics(query.mediaId, title, query.artists, query.album, query.duration)
                            .fold(
                                onSuccess = { value ->
                                    config.normalize(value).takeIf(config.isMeaningful)
                                },
                                onFailure = { error ->
                                    config.onError(error)
                                    null
                                },
                            )
                    if (lyrics != null) return@withTimeout lyrics
                }
                null
            }
        } catch (_: TimeoutCancellationException) {
            config.onTimeout(provider.name, "automatic")
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            config.onError(error)
            null
        }

    private suspend fun fetchAllFromProvider(
        provider: LyricsFetchProvider,
        query: LyricsQuery,
    ): ProviderSearchOutcome =
        try {
            withTimeout(config.timeoutMs) {
                for (title in LyricsQueryPolicy.titleVariants(query.title)) {
                    val results = mutableListOf<LyricsResult>()
                    provider.getAllLyrics(
                        id = query.mediaId,
                        title = title,
                        artist = query.artists,
                        album = query.album,
                        duration = query.duration,
                    ) { lyrics ->
                        val normalized = config.normalize(lyrics)
                        if (config.isMeaningful(normalized)) {
                            results += LyricsResult(provider.name, normalized)
                        }
                    }
                    if (results.isNotEmpty()) {
                        return@withTimeout ProviderSearchOutcome(
                            status = LyricsProviderSearchStatus.FOUND,
                            results = results,
                        )
                    }
                }
                ProviderSearchOutcome(LyricsProviderSearchStatus.NO_MATCH)
            }
        } catch (_: TimeoutCancellationException) {
            config.onTimeout(provider.name, "search")
            ProviderSearchOutcome(LyricsProviderSearchStatus.TIMED_OUT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            config.onError(error)
            ProviderSearchOutcome(LyricsProviderSearchStatus.FAILED)
        }

    private data class ProviderSearchOutcome(
        val status: LyricsProviderSearchStatus,
        val results: List<LyricsResult> = emptyList(),
    )

    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"
    }
}

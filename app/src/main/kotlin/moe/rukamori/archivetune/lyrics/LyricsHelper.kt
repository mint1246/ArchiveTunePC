/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.constants.EnablePaxsenixLyricsKey
import moe.rukamori.archivetune.constants.LyricsProviderOrderKey
import moe.rukamori.archivetune.constants.PaxsenixApiKeyKey
import moe.rukamori.archivetune.constants.PreferredLyricsProvider
import moe.rukamori.archivetune.constants.deserializeLyricsProviderOrder
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.utils.GlobalLog
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.reportException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val networkConnectivity: NetworkConnectivityObserver,
    ) {
        private val baseProviders =
            listOf(
                BetterLyricsProvider,
                BetterLyricsPortatoProvider,
                YouLyPlusLyricsProvider,
                LrcLibLyricsProvider,
                KuGouLyricsProvider,
                MegalobizLyricsProvider,
                SimpMusicLyricsProvider,
                UnisonLyricsProvider,
                PaxsenixAppleMusicLyricsProvider,
                PaxsenixSpotifyLyricsProvider,
                PaxsenixMusixmatchLyricsProvider,
                YouTubeSubtitleLyricsProvider,
                YouTubeLyricsProvider,
            )

        private val requestCache = LyricsRequestCache()
        private val providerPermits = Semaphore(MAX_CONCURRENT_PROVIDERS)

        suspend fun getLyrics(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): String {
            val activeProviders = orderedProviders().filter { it.isEnabled(context) }
            requestCache.alignProviderConfiguration(activeProviders.map(LyricsProvider::name))
            val providers = if (preferredProviderOnly) activeProviders.take(1) else activeProviders
            val request = mediaMetadata.toRequestKey(providers)
            return requestCache.getOrFetchSingle(
                request = request,
                forceRefresh = forceRefresh,
                cacheable = ::isMeaningfulLyrics,
            ) {
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "Fetching lyrics for ${mediaMetadata.title} (Artist: ${mediaMetadata.artists.joinToString { it.name }}, " +
                        "Album: ${mediaMetadata.album?.title})",
                )
                if (!isNetworkAvailable()) {
                    GlobalLog.append(Log.WARN, "LyricsHelper", "Network unavailable, aborting lyrics fetch")
                    LYRICS_NOT_FOUND
                } else {
                    fetchPriorityLyrics(providers, mediaMetadata)
                }
            }
        }

        suspend fun getAllLyrics(
            mediaId: String,
            songTitle: String,
            songArtists: String,
            songAlbum: String?,
            duration: Int,
            forceRefresh: Boolean = false,
            callback: (LyricsResult) -> Unit,
        ) {
            searchAllLyrics(
                mediaId = mediaId,
                songTitle = songTitle,
                songArtists = songArtists,
                songAlbum = songAlbum,
                duration = duration,
                forceRefresh = forceRefresh,
                onProviderUpdate = {},
            ).forEach(callback)
        }

        suspend fun searchAllLyrics(
            mediaId: String,
            songTitle: String,
            songArtists: String,
            songAlbum: String?,
            duration: Int,
            forceRefresh: Boolean = false,
            onProviderUpdate: (LyricsProviderSearchUpdate) -> Unit,
        ): List<LyricsResult> {
            val providers = orderedProviders().filter { it.isEnabled(context) }
            requestCache.alignProviderConfiguration(providers.map(LyricsProvider::name))
            providers.forEachIndexed { index, provider ->
                onProviderUpdate(
                    LyricsProviderSearchUpdate(
                        providerIndex = index,
                        providerName = provider.name,
                        status = LyricsProviderSearchStatus.SEARCHING,
                    ),
                )
            }
            val request =
                LyricsRequestKey(
                    mediaId = mediaId,
                    title = songTitle,
                    artists = songArtists,
                    album = songAlbum,
                    duration = duration,
                    providers = providers.map(LyricsProvider::name),
                )
            var fetchedFresh = false
            val results =
                requestCache.getOrFetchSearch(
                    request = request,
                    forceRefresh = forceRefresh,
                ) {
                    fetchedFresh = true
                    if (!isNetworkAvailable()) {
                        providers.forEachIndexed { index, provider ->
                            onProviderUpdate(
                                LyricsProviderSearchUpdate(
                                    providerIndex = index,
                                    providerName = provider.name,
                                    status = LyricsProviderSearchStatus.FAILED,
                                ),
                            )
                        }
                        return@getOrFetchSearch emptyList()
                    }

                    supervisorScope {
                        providers
                            .mapIndexed { index, provider ->
                                async(Dispatchers.IO) {
                                    val outcome =
                                        providerPermits.withPermit {
                                            fetchAllProviderLyrics(
                                                provider = provider,
                                                mediaId = mediaId,
                                                songTitle = songTitle,
                                                songArtists = songArtists,
                                                songAlbum = songAlbum,
                                                duration = duration,
                                            )
                                        }
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
                            .flatMap(ProviderSearchOutcome::results)
                    }
                }

            if (!fetchedFresh) {
                val resultsByProvider = results.groupBy(LyricsResult::providerName)
                providers.forEachIndexed { index, provider ->
                    val providerResults = resultsByProvider[provider.name].orEmpty()
                    onProviderUpdate(
                        LyricsProviderSearchUpdate(
                            providerIndex = index,
                            providerName = provider.name,
                            status =
                                if (providerResults.isEmpty()) {
                                    LyricsProviderSearchStatus.NO_MATCH
                                } else {
                                    LyricsProviderSearchStatus.FOUND
                                },
                            results = providerResults,
                        ),
                    )
                }
            }
            return results
        }

        private suspend fun fetchAllProviderLyrics(
            provider: LyricsProvider,
            mediaId: String,
            songTitle: String,
            songArtists: String,
            songAlbum: String?,
            duration: Int,
        ): ProviderSearchOutcome =
            try {
                withTimeout(LyricsQueryPolicy.PROVIDER_TIMEOUT_MS) {
                    for (title in LyricsQueryPolicy.titleVariants(songTitle)) {
                        val results = mutableListOf<LyricsResult>()
                        provider.getAllLyrics(mediaId, title, songArtists, songAlbum, duration) lyricsCallback@{ lyrics ->
                            val normalizedLyrics = LyricsUtils.lyricsOrNotFound(lyrics)
                            if (normalizedLyrics == LYRICS_NOT_FOUND) return@lyricsCallback
                            results += LyricsResult(provider.name, normalizedLyrics)
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
                GlobalLog.append(Log.WARN, "LyricsHelper", "${provider.name} timed out during lyrics search")
                ProviderSearchOutcome(LyricsProviderSearchStatus.TIMED_OUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                ProviderSearchOutcome(LyricsProviderSearchStatus.FAILED)
            }

        private suspend fun fetchPriorityLyrics(
            providers: List<LyricsProvider>,
            mediaMetadata: MediaMetadata,
        ): String {
            if (providers.isEmpty()) return LYRICS_NOT_FOUND

            val artist = mediaMetadata.artists.joinToString { it.name }
            val results =
                supervisorScope {
                    providers
                        .map { provider ->
                            async(Dispatchers.IO) {
                                providerPermits.withPermit {
                                    fetchProviderLyrics(provider, mediaMetadata, artist)
                                }
                            }
                        }.map { it.await() }
                }
            // Provider order is authoritative, matching desktop. A lower-priority provider must
            // not silently win just because it returned a richer sync format.
            return selectProviderOrderedLyrics(results) ?: LYRICS_NOT_FOUND
        }

        private suspend fun fetchProviderLyrics(
            provider: LyricsProvider,
            mediaMetadata: MediaMetadata,
            artist: String,
        ): String? =
            try {
                withTimeout(LyricsQueryPolicy.PROVIDER_TIMEOUT_MS) {
                    for (title in LyricsQueryPolicy.titleVariants(mediaMetadata.title)) {
                        val lyrics =
                            provider
                                .getLyrics(
                                    mediaMetadata.id,
                                    title,
                                    artist,
                                    mediaMetadata.album?.title,
                                    mediaMetadata.duration,
                                ).fold(
                                    onSuccess = { value ->
                                        LyricsUtils.lyricsOrNotFound(value).takeIf { it != LYRICS_NOT_FOUND }
                                    },
                                    onFailure = {
                                        reportException(it)
                                        null
                                    },
                                )
                        if (lyrics != null) return@withTimeout lyrics
                    }
                    null
                }
            } catch (_: TimeoutCancellationException) {
                GlobalLog.append(Log.WARN, "LyricsHelper", "${provider.name} timed out during automatic lookup")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                null
            }

        private suspend fun orderedProviders(): List<LyricsProvider> {
            val preferences = context.dataStore.data.first()
            val orderStr = preferences[LyricsProviderOrderKey]
            val orderedEnums = deserializeLyricsProviderOrder(orderStr)
            val providerMap: Map<PreferredLyricsProvider, LyricsProvider> =
                mapOf(
                    PreferredLyricsProvider.LRCLIB to LrcLibLyricsProvider,
                    PreferredLyricsProvider.KUGOU to KuGouLyricsProvider,
                    PreferredLyricsProvider.MEGALOBIZ to MegalobizLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS to BetterLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS_PORTATO to BetterLyricsPortatoProvider,
                    PreferredLyricsProvider.YOULY_PLUS to YouLyPlusLyricsProvider,
                    PreferredLyricsProvider.SIMPMUSIC to SimpMusicLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC to PaxsenixAppleMusicLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_SPOTIFY to PaxsenixSpotifyLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_MUSIXMATCH to PaxsenixMusixmatchLyricsProvider,
                    PreferredLyricsProvider.UNISON to UnisonLyricsProvider,
                )
            val userOrdered = orderedEnums.mapNotNull { providerMap[it] }
            val rest = baseProviders.filterNot { it in userOrdered }
            val paxsenixEnabled = preferences[EnablePaxsenixLyricsKey] ?: true
            val paxsenixApiKeyConfigured = !preferences[PaxsenixApiKeyKey].isNullOrBlank()
            return (userOrdered + rest).filter { provider ->
                provider !in paxsenixProviders || (paxsenixEnabled && paxsenixApiKeyConfigured)
            }
        }

        private fun isMeaningfulLyrics(lyrics: String): Boolean = LyricsUtils.hasMeaningfulLyricsContent(lyrics)

        private fun isNetworkAvailable(): Boolean =
            try {
                networkConnectivity.isCurrentlyConnected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                true
            }

        fun clearCache() {
            requestCache.clear()
        }

        private fun MediaMetadata.toRequestKey(providers: List<LyricsProvider>): LyricsRequestKey =
            LyricsRequestKey(
                mediaId = id,
                title = title,
                artists = artists.joinToString { it.name },
                album = album?.title,
                duration = duration,
                providers = providers.map(LyricsProvider::name),
            )

        companion object {
            private const val MAX_CONCURRENT_PROVIDERS = 6
            private val paxsenixProviders =
                setOf(
                    PaxsenixAppleMusicLyricsProvider,
                    PaxsenixSpotifyLyricsProvider,
                    PaxsenixMusixmatchLyricsProvider,
                )
        }

        private data class ProviderSearchOutcome(
            val status: LyricsProviderSearchStatus,
            val results: List<LyricsResult> = emptyList(),
        )
    }

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

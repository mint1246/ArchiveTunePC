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
import kotlinx.coroutines.flow.first
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

/**
 * Android lyrics lookup facade. Provider ordering, bounded concurrency, per-provider timeouts,
 * priority selection and cancellation now live in the headless [LyricsFetchCoordinator] in
 * `lyrics-sdk`; this class retains only platform concerns: enabled/ordered preferences, the
 * request cache, connectivity, logging and error reporting.
 */
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
        private val fetchCoordinator =
            LyricsFetchCoordinator(
                LyricsFetchCoordinatorConfig(
                    normalize = ::normalizeForLookup,
                    isMeaningful = ::isMeaningfulLyrics,
                    onError = ::reportException,
                    onTimeout = { providerName, phase ->
                        GlobalLog.append(
                            Log.WARN,
                            "LyricsHelper",
                            "$providerName timed out during ${if (phase == "search") "lyrics search" else "automatic lookup"}",
                        )
                    },
                    isNetworkAvailable = ::isNetworkAvailable,
                ),
            )

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
                    fetchCoordinator.fetchOrdered(providers, mediaMetadata.toQuery())
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
                    fetchCoordinator.searchAll(
                        providers = providers,
                        query =
                            LyricsQuery(
                                mediaId = mediaId,
                                title = songTitle,
                                artists = songArtists,
                                album = songAlbum,
                                duration = duration,
                            ),
                        onProviderUpdate = onProviderUpdate,
                    )
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

        private fun normalizeForLookup(lyrics: String): String = LyricsUtils.lyricsOrNotFound(lyrics)

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

        private fun MediaMetadata.toQuery(): LyricsQuery =
            LyricsQuery(
                mediaId = id,
                title = title,
                artists = artists.joinToString { it.name },
                album = album?.title,
                duration = duration,
            )

        companion object {
            private val paxsenixProviders =
                setOf(
                    PaxsenixAppleMusicLyricsProvider,
                    PaxsenixSpotifyLyricsProvider,
                    PaxsenixMusixmatchLyricsProvider,
                )
        }
    }

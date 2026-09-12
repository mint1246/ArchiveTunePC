/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
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
                PaxsenixNeteaseLyricsProvider,
                PaxsenixSpotifyLyricsProvider,
                PaxsenixMusixmatchLyricsProvider,
                PaxsenixYouTubeLyricsProvider,
                YouTubeSubtitleLyricsProvider,
                YouTubeLyricsProvider,
            )

        private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
        private val singleLyricsCache = LruCache<String, String>(MAX_CACHE_SIZE)
        private val providerPermits = Semaphore(MAX_CONCURRENT_PROVIDERS)

        suspend fun getLyrics(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): String {
            val cacheKey = mediaMetadata.lyricsCacheKey
            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                singleLyricsCache.get(cacheKey)?.let { lyrics ->
                    GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                    return lyrics
                }

                val cached = cache.get(cacheKey)?.firstOrNull()
                if (cached != null) {
                    GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                    return cached.lyrics
                }
            }

            GlobalLog.append(
                Log.DEBUG,
                "LyricsHelper",
                "Fetching lyrics for ${mediaMetadata.title} (Artist: ${mediaMetadata.artists.joinToString {
                    it.name
                }}, Album: ${mediaMetadata.album?.title})",
            )

            val isNetworkAvailable =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                GlobalLog.append(Log.WARN, "LyricsHelper", "Network unavailable, aborting lyrics fetch")
                return LYRICS_NOT_FOUND
            }

            val ordered = orderedProviders().filter { it.isEnabled(context) }
            val providers = if (preferredProviderOnly) ordered.take(1) else ordered
            val lyrics = fetchPriorityLyrics(providers, mediaMetadata)
            if (isMeaningfulLyrics(lyrics)) {
                singleLyricsCache.put(cacheKey, lyrics)
            }

            return lyrics
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
            val cacheKey = LyricsQueryPolicy.cacheKey(mediaId, songTitle, songArtists)
            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                cache.get(cacheKey)?.let { results ->
                    results.forEach(callback)
                    return
                }
            }

            val isNetworkAvailable =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                return
            }

            val providers = orderedProviders().filter { it.isEnabled(context) }
            val resultsByProvider =
                supervisorScope {
                    providers.map { provider ->
                        async(Dispatchers.IO) {
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
                        }
                    }.map { it.await() }
                }
            val allResult = resultsByProvider.flatten()
            allResult.forEach(callback)
            cache.put(cacheKey, allResult.toList())
        }

        private suspend fun fetchAllProviderLyrics(
            provider: LyricsProvider,
            mediaId: String,
            songTitle: String,
            songArtists: String,
            songAlbum: String?,
            duration: Int,
        ): List<LyricsResult> =
            try {
                withTimeout(LyricsQueryPolicy.PROVIDER_TIMEOUT_MS) {
                    for (title in LyricsQueryPolicy.titleVariants(songTitle)) {
                        val results = mutableListOf<LyricsResult>()
                        provider.getAllLyrics(mediaId, title, songArtists, songAlbum, duration) lyricsCallback@{ lyrics ->
                            val normalizedLyrics = LyricsUtils.lyricsOrNotFound(lyrics)
                            if (normalizedLyrics == LYRICS_NOT_FOUND) return@lyricsCallback
                            results += LyricsResult(provider.name, normalizedLyrics)
                        }
                        if (results.isNotEmpty()) return@withTimeout results
                    }
                    emptyList()
                }
            } catch (_: TimeoutCancellationException) {
                GlobalLog.append(Log.WARN, "LyricsHelper", "${provider.name} timed out during lyrics search")
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                emptyList()
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
                        }.mapNotNull { it.await() }
                }

            if (results.isEmpty()) return LYRICS_NOT_FOUND

            // Provider order is authoritative, matching desktop. A lower-priority provider must
            // not silently win just because it returned a richer sync format.
            return results.first()
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
                    PreferredLyricsProvider.PAXSENIX_NETEASE to PaxsenixNeteaseLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_SPOTIFY to PaxsenixSpotifyLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_MUSIXMATCH to PaxsenixMusixmatchLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_YOUTUBE to PaxsenixYouTubeLyricsProvider,
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

        fun clearCache() {
            cache.evictAll()
            singleLyricsCache.evictAll()
        }

        private fun invalidateCache(cacheKey: String) {
            cache.remove(cacheKey)
            singleLyricsCache.remove(cacheKey)
        }

        private val MediaMetadata.lyricsCacheKey: String
            get() = LyricsQueryPolicy.cacheKey(id, title, artists.joinToString { it.name })

        companion object {
            private const val MAX_CACHE_SIZE = 16
            private const val MAX_CONCURRENT_PROVIDERS = 6
            private val paxsenixProviders =
                setOf(
                    PaxsenixAppleMusicLyricsProvider,
                    PaxsenixNeteaseLyricsProvider,
                    PaxsenixSpotifyLyricsProvider,
                    PaxsenixMusixmatchLyricsProvider,
                    PaxsenixYouTubeLyricsProvider,
                )
        }
    }

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

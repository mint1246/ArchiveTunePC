/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.AccountInfo
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.YouTubeLocale
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.innertube.pages.AlbumPage
import moe.rukamori.archivetune.innertube.pages.ArtistItemsContinuationPage
import moe.rukamori.archivetune.innertube.pages.ArtistItemsPage
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.ChartsPage
import moe.rukamori.archivetune.innertube.pages.ExplorePage
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.innertube.pages.LibraryPage
import moe.rukamori.archivetune.innertube.pages.NextResult
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.innertube.pages.PodcastPage
import moe.rukamori.archivetune.innertube.pages.RelatedPage
import moe.rukamori.archivetune.innertube.pages.SearchResult
import moe.rukamori.archivetune.innertube.pages.SearchSummaryPage
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.utils.completed
import moe.rukamori.archivetune.morideobfuscator.MoriCipherConfig
import moe.rukamori.archivetune.morideobfuscator.MoriCipherRuntime
import moe.rukamori.archivetune.stream.PlaybackStreamResolver
import moe.rukamori.archivetune.stream.StreamAudioQuality
import moe.rukamori.archivetune.stream.StreamClientPreference
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * JVM entry point for ArchiveTune's real YouTube Music backend.
 *
 * The Android app can continue using [YouTube] directly. Desktop clients use this class to get the
 * same browse models plus a resolved, expiring audio URL and the HTTP headers required to consume it.
 * No Android runtime or AndroidX type is referenced by this API.
 */
class ArchiveTuneJvmClient(
    cacheDirectory: File,
    private val streamClients: List<YouTubeClient> = DEFAULT_STREAM_CLIENTS,
    private val streamUrlResolver: StreamUrlResolver = NewPipeStreamUrlResolver,
    networkPolicy: DesktopNetworkPolicy = DesktopNetworkPolicy(),
    sessionProvider: DesktopSessionProvider? = null,
) {
    @Volatile
    private var sessionProvider: DesktopSessionProvider? = sessionProvider
    private val failedStreamClientsUntil = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastSuccessfulStreamClientKey: String? = null

    /**
     * The account endpoints used by [verifyAccountSession].
     *
     * Injectable so the validation flow can be exercised without a live YouTube connection, and
     * so every validation request can carry an explicit session snapshot.
     */
    @Volatile
    internal var accountEndpoint: DesktopAccountEndpoint = YouTubeAccountEndpoint
    internal var accountValidationTimeoutMillis: Long = 45_000L
    internal var accountContextTimeoutMillis: Long = 8_000L

    init {
        require(cacheDirectory.path.isNotBlank()) { "Cipher cache directory is required" }
        require(streamClients.isNotEmpty()) { "At least one YouTube stream client is required" }
        // Mirror Android's default: apply the signed-in cookie to browse calls such as explore,
        // charts, new releases and library pages that do not explicitly pass setLogin = true.
        YouTube.useLoginForBrowse = true
        NetworkGatekeeper.setConnectionBlocked(networkPolicy.gatekeeperEnabled)
        MoriCipherRuntime.initialize(
            MoriCipherConfig(
                cacheDirectory = cacheDirectory,
                proxyProvider = { YouTube.streamProxy },
            ),
        )
    }

    var authState: PlaybackAuthState
        get() {
            applyProvidedSession()
            return YouTube.authState
        }
        set(value) {
            YouTube.authState = value
        }

    /**
     * Binds an optional desktop-owned session source. The source is consulted immediately before
     * each backend operation, so logout and account changes take effect without recreating this
     * client. Passing `null` restores the existing direct [authState] behavior.
     */
    fun bindSessionProvider(provider: DesktopSessionProvider?) {
        sessionProvider = provider
        applyProvidedSession()
    }

    suspend fun search(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG,
    ): Result<SearchResult> =
        run {
            prepareProvidedSession()
            normalizedQuery(query).fold(
            onSuccess = { YouTube.search(it, filter) },
            onFailure = { Result.failure(it) },
            )
        }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> = run {
        prepareProvidedSession()
        normalizedQuery(query).fold(
            onSuccess = { YouTube.searchSummary(it) },
            onFailure = { Result.failure(it) },
        )
    }

    /** Song-only search used by desktop importers that need Android-equivalent source matching. */
    suspend fun searchSongs(query: String): Result<List<SongItem>> = run {
        prepareProvidedSession()
        normalizedQuery(query).fold(
            onSuccess = { normalized ->
                YouTube.search(normalized, YouTube.SearchFilter.FILTER_SONG)
                    .map { page -> page.items.filterIsInstance<SongItem>().distinctBy(SongItem::id) }
            },
            onFailure = { Result.failure(it) },
        )
    }

    /** Loads another song-search page without account-specific recommendation context. */
    suspend fun searchContinuation(continuation: String): Result<SearchResult> = run {
        prepareProvidedSession()
        val normalizedContinuation = continuation.trim()
        if (normalizedContinuation.isEmpty()) {
            return Result.failure(IllegalArgumentException("Search continuation is required"))
        }
        YouTube.searchContinuation(normalizedContinuation, useAccountContext = false)
    }

    /** Account-neutral song search used to build playlist recommendations. */
    suspend fun searchSuggestionSongs(query: String): Result<SearchResult> = run {
        prepareProvidedSession()
        normalizedQuery(query).fold(
            onSuccess = { YouTube.search(it, YouTube.SearchFilter.FILTER_SONG, useAccountContext = false) },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun home(
        continuation: String? = null,
        params: String? = null,
    ): Result<HomePage> = run {
        prepareProvidedSession()
        YouTube.home(continuation, params)
    }

    suspend fun explore(): Result<ExplorePage> = run {
        prepareProvidedSession()
        YouTube.explore()
    }

    suspend fun charts(continuation: String? = null): Result<ChartsPage> = run {
        prepareProvidedSession()
        YouTube.getChartsPage(continuation)
    }

    suspend fun musicHistory(): Result<HistoryPage> = run {
        prepareProvidedSession()
        YouTube.musicHistory()
    }

    suspend fun newReleaseAlbums(): Result<List<AlbumItem>> = run {
        prepareProvidedSession()
        YouTube.newReleaseAlbums()
    }

    // Log a fixed stage, the error class, and an HTTP status only. Never log exception messages,
    // URLs, headers, request/response bodies, accounts, cookie names, or session data.
    private val accountValidationLogger = Logger.getLogger("moe.rukamori.archivetune.desktop.auth")

    private suspend fun <T> probeAccountValidationStage(
        stage: String,
        request: suspend () -> Result<T>,
    ): T {
        val result = request()
        result.exceptionOrNull()?.let { failure ->
            if (failure is CancellationException) throw failure
            val status = (failure as? ResponseException)?.response?.status?.value
            val category = when {
                status != null -> "http"
                failure is java.io.IOException -> "network"
                else -> "processing"
            }
            accountValidationLogger.warning(
                "account_validation stage=$stage result=failure category=$category status=${status ?: "none"} exception=${failure.javaClass.simpleName}",
            )
        }
        val value = result.getOrThrow()
        accountValidationLogger.info("account_validation stage=$stage result=success")
        return value
    }

    private suspend fun <T> optionalAccountValidationStage(
        stage: String,
        request: suspend () -> Result<T>,
    ): T? = try {
        withTimeoutOrNull(accountContextTimeoutMillis) {
            probeAccountValidationStage(stage, request)
        }.also { value ->
            if (value == null) accountValidationLogger.info("account_validation stage=$stage result=timeout")
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * Verifies a user-supplied authenticated YouTube session without exposing its secret values.
     *
     * The candidate is never published to the process-wide session and is never written to the
     * secure store from here. Every request carries the candidate snapshot explicitly, so a
     * concurrent browse request that refreshes the shared session cannot replace the cookie of an
     * in-flight validation. The verified session becomes active only after the desktop session
     * owner has persisted it.
     */
    suspend fun verifyAccountSession(candidate: PlaybackAuthState): Result<VerifiedBackendAccount> {
        var authenticatedAccount: AccountInfo? = null
        return try {
            val verified = withTimeoutOrNull(accountValidationTimeoutMillis) {
                require(candidate.hasLoginCookie) { "YouTube login cookie is missing" }
                val initial = candidate.normalized()
                // Only the account menu proves that Google accepted the cookie. Channel and
                // visitor lookups enrich that authenticated session; they cannot authenticate it.
                val account = probeAccountValidationStage("account_menu") {
                    accountEndpoint.accountInfo(initial.copy(dataSyncId = null))
                }
                authenticatedAccount = account
                VerifiedBackendAccount(resolveAccountContext(initial, refreshVisitor = true), account)
            } ?: authenticatedAccount?.let {
                // Optional enrichment must not invalidate an account Google already authenticated.
                // A restored-session preparation can retry the missing context on the next use.
                VerifiedBackendAccount(candidate.normalized(), it)
            } ?: throw AccountValidationTimeoutException()
            Result.success(verified)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun album(browseId: String): Result<AlbumPage> =
        run {
            prepareProvidedSession()
            normalizedId(browseId, "Album browse ID").fold(
            onSuccess = { YouTube.album(it) },
            onFailure = { Result.failure(it) },
            )
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        run {
            prepareProvidedSession()
            normalizedId(browseId, "Artist browse ID").fold(
                onSuccess = { artistId ->
                    YouTube.artist(artistId).fold(
                        onSuccess = { page -> Result.success(recoverUnlistedArtistAlbums(page)) },
                        onFailure = { Result.failure(it) },
                    )
                },
                onFailure = { Result.failure(it) },
            )
        }

    /**
     * Artist pages can leak album browse IDs through songs even when those albums are omitted from
     * the Albums carousel. Recover those profile-derived albums first. Some regional artist pages no
     * longer leak enough context, so an anonymous US title search is only a last-resort fallback.
     */
    private suspend fun recoverUnlistedArtistAlbums(page: ArtistPage): ArtistPage {
        val albumSectionIndex = page.sections.indexOfFirst { section ->
            section.items.any { it is AlbumItem }
        }
        if (albumSectionIndex < 0) return page

        val albumSection = page.sections[albumSectionIndex]

        val knownAlbumIds =
            page.sections
                .asSequence()
                .flatMap { it.items.asSequence() }
                .filterIsInstance<AlbumItem>()
                .mapTo(mutableSetOf()) { it.browseId }

        val profileLeakedAlbumIds =
            page.sections
                .asSequence()
                .flatMap { it.items.asSequence() }
                .filterIsInstance<SongItem>()
                .mapNotNull { it.album?.id }
                .filterNot(knownAlbumIds::contains)
                .distinct()
                .toList()

        val fallbackPage =
            YouTube.artist(
                browseId = page.artist.id,
                useAccountContext = false,
                localeOverride = ARTIST_ALBUM_RECOVERY_LOCALE,
            ).getOrNull()
        val fallbackAlbums =
            fallbackPage
                ?.sections
                ?.firstOrNull { section -> section.items.any { it is AlbumItem } }
                ?.items
                ?.filterIsInstance<AlbumItem>()
                .orEmpty()
        knownAlbumIds += fallbackAlbums.map { it.browseId }

        val fallbackSearchAlbumIds =
            listOfNotNull(fallbackPage?.artist?.title, page.artist.title)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .flatMap { query ->
                    YouTube.search(
                        query = query,
                        filter = YouTube.SearchFilter.FILTER_SONG,
                        useAccountContext = false,
                        localeOverride = ARTIST_ALBUM_RECOVERY_LOCALE,
                    ).getOrNull()
                        ?.items
                        .orEmpty()
                }.asSequence()
                .filterIsInstance<SongItem>()
                .filter { song -> song.artists.any { it.id == page.artist.id } }
                .mapNotNull { it.album?.id }
                .filterNot(knownAlbumIds::contains)
                .distinct()
                .toList()

        val candidateAlbumIds = (profileLeakedAlbumIds + fallbackSearchAlbumIds).distinct()

        val searchedAlbums =
            candidateAlbumIds.mapNotNull { albumId ->
                val albumPage = YouTube.album(albumId).getOrNull() ?: return@mapNotNull null
                val belongsToArtist =
                    albumPage.album.artists.orEmpty().any { it.id == page.artist.id } ||
                        albumPage.songs.any { song -> song.artists.any { it.id == page.artist.id } }
                if (!belongsToArtist || albumPage.songs.size < MIN_FULL_ALBUM_TRACKS) return@mapNotNull null
                albumPage.album
            }
        val recovered = (fallbackAlbums + searchedAlbums).distinctBy { it.browseId }
        if (recovered.isEmpty()) return page

        val mergedItems =
            (albumSection.items + recovered)
                .distinctBy { it.id }
                .sortedByDescending { (it as? AlbumItem)?.year ?: Int.MIN_VALUE }
        val updatedSections = page.sections.toMutableList()
        updatedSections[albumSectionIndex] = albumSection.copy(items = mergedItems)
        return page.copy(sections = updatedSections)
    }

    /** Loads the full artist section behind a section's "more" browse endpoint. */
    suspend fun artistItems(endpoint: BrowseEndpoint): Result<ArtistItemsPage> =
        run {
            prepareProvidedSession()
            normalizedId(endpoint.browseId, "Artist items browse ID").fold(
                onSuccess = { browseId -> YouTube.artistItems(endpoint.copy(browseId = browseId)) },
                onFailure = { Result.failure(it) },
            )
        }

    /** Loads another page from an artist-items section. */
    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> =
        run {
            prepareProvidedSession()
            normalizedId(continuation, "Artist items continuation").fold(
                onSuccess = { YouTube.artistItemsContinuation(it) },
                onFailure = { Result.failure(it) },
            )
        }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        run {
            prepareProvidedSession()
            normalizedId(playlistId, "Playlist ID").fold(
            onSuccess = { id ->
                val normalizedPlaylistId = id.removePrefix("VL")
                val initial = YouTube.authState
                YouTube.authState = initial.copy(dataSyncId = null)
                val channels = YouTube.accountChannels().getOrNull().orEmpty()
                YouTube.authState = initial.copy(dataSyncId = null)
                val refreshedDataSyncId = YouTube.accountDataSyncId().getOrNull()
                val candidates =
                    (listOfNotNull(initial.dataSyncId) +
                        channels.sortedByDescending { it.isSelected }.map { it.dataSyncId } +
                        listOfNotNull(refreshedDataSyncId) + "")
                        .distinct()
                var lastFailure: Throwable? = null

                for (dataSyncId in candidates.ifEmpty { listOf("") }) {
                    val candidateAuthState = initial.copy(dataSyncId = dataSyncId.ifBlank { null }).normalized()
                    YouTube.authState = candidateAuthState
                    val result = YouTube.playlist(normalizedPlaylistId).completed()
                    if (result.isSuccess) {
                        persistProvidedSessionRepair(initial, candidateAuthState)
                        return@fold result
                    }
                    lastFailure = result.exceptionOrNull()
                }

                Result.failure(lastFailure ?: IllegalStateException("YouTube Music playlist is unavailable"))
            },
            onFailure = { Result.failure(it) },
            )
        }

    suspend fun podcast(browseId: String): Result<PodcastPage> = run {
        prepareProvidedSession()
        val normalizedBrowseId = browseId.trim()
        if (normalizedBrowseId.isEmpty()) return Result.failure(IllegalArgumentException("Podcast ID is required"))
        YouTube.podcast(normalizedBrowseId)
    }

    suspend fun podcastContinuation(continuation: String): Result<PodcastPage.Continuation> = run {
        prepareProvidedSession()
        val normalizedContinuation = continuation.trim()
        if (normalizedContinuation.isEmpty()) return Result.failure(IllegalArgumentException("Podcast continuation is required"))
        YouTube.podcastContinuation(normalizedContinuation)
    }

    suspend fun setPodcastSaved(playlistId: String, saved: Boolean): Result<Unit> = run {
        prepareProvidedSession()
        val normalizedPlaylistId = playlistId.trim().removePrefix("VL")
        if (normalizedPlaylistId.isEmpty()) return Result.failure(IllegalArgumentException("Podcast playlist ID is required"))
        YouTube.likePlaylist(normalizedPlaylistId, saved).map { Unit }
    }

    /** Returns one account-scoped YouTube Music library page for the supplied browse id. */
    suspend fun library(browseId: String): Result<LibraryPage> = run {
        prepareProvidedSession()
        val initial = YouTube.authState
        // The account-list response can contain both a generic main-app DataSync value and one
        // value per YouTube channel. Private Music library pages are scoped to a channel, so use
        // the explicitly selected channel first instead of the ambiguous main-app value.
        YouTube.authState = initial.copy(dataSyncId = null)
        val channels = YouTube.accountChannels().getOrNull().orEmpty()
        YouTube.authState = initial.copy(dataSyncId = null)
        val refreshedDataSyncId = YouTube.accountDataSyncId().getOrNull()
        val candidates =
            (channels.sortedByDescending { it.isSelected }.map { it.dataSyncId } +
                listOfNotNull(refreshedDataSyncId, initial.dataSyncId) + "")
                .distinct()
        var lastFailure: Throwable? = null
        val pages = mutableListOf<LibraryPage>()
        var successfulAuthState: PlaybackAuthState? = null

        for (dataSyncId in candidates.ifEmpty { listOf("") }) {
            YouTube.authState = initial.copy(dataSyncId = dataSyncId.ifBlank { null }).normalized()
            val result = YouTube.library(browseId).completed()
            result.onSuccess { page ->
                pages += page
                if (successfulAuthState == null && dataSyncId.isNotBlank()) {
                    successfulAuthState = YouTube.currentPlaybackAuthState()
                }
            }.onFailure { lastFailure = it }
        }

        successfulAuthState?.let { repaired ->
            YouTube.authState = repaired
            persistProvidedSessionRepair(initial, repaired)
        } ?: run {
            YouTube.authState = initial
        }

        if (pages.isNotEmpty()) {
            Result.success(
                LibraryPage(
                    items = pages.flatMap { it.items }.distinctBy { it::class to it.id },
                    continuation = null,
                ),
            )
        } else {
            Result.failure(lastFailure ?: IllegalStateException("YouTube Music library is unavailable"))
        }
    }

    /** Returns the signed-in user's YouTube Music playlists. */
    suspend fun libraryPlaylists(): Result<LibraryPage> = library("FEmusic_liked_playlists")

    /** Creates a playlist in the currently selected signed-in YouTube Music account. */
    suspend fun createPlaylist(
        title: String,
        videoIds: List<String> = emptyList(),
    ): Result<String> = run {
        prepareProvidedSession()
        YouTube.createPlaylist(title, videoIds)
    }

    /** Adds one song to an existing playlist through Android/core's normal mutation path. */
    suspend fun addSongToPlaylist(
        playlistId: String,
        videoId: String,
    ): Result<String> = run {
        prepareProvidedSession()
        YouTube.addToPlaylist(playlistId, videoId)
    }

    /** Adds an ordered group of songs in one edit_playlist mutation. */
    suspend fun addSongsToPlaylist(
        playlistId: String,
        videoIds: List<String>,
    ): Result<Unit> = run {
        prepareProvidedSession()
        if (videoIds.isEmpty()) return Result.success(Unit)
        YouTube.addSongsToPlaylist(
            playlistId = playlistId,
            videoIds = videoIds,
            batchSize = videoIds.size,
            fallbackToIndividual = false,
        ).map { Unit }
    }

    /** Removes the selected playlist entry, resolving its setVideoId if the page omitted it. */
    suspend fun removeSongFromPlaylist(
        playlistId: String,
        song: SongItem,
    ): Result<Unit> = run {
        prepareProvidedSession()
        val setVideoId =
            song.setVideoId?.takeIf(String::isNotBlank)
                ?: YouTube.playlistEntrySetVideoIds(playlistId, song.id)
                    .getOrElse { return Result.failure(it) }
                    .firstOrNull()
                    ?.takeIf(String::isNotBlank)
                ?: return Result.failure(IllegalStateException("Could not identify this playlist entry"))
        YouTube.removeFromPlaylist(playlistId, song.id, setVideoId).map { Unit }
    }

    suspend fun renamePlaylist(playlistId: String, name: String): Result<Unit> = run {
        prepareProvidedSession()
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return Result.failure(IllegalArgumentException("Playlist name is required"))
        YouTube.renamePlaylist(playlistId, normalizedName).map { Unit }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> = run {
        prepareProvidedSession()
        YouTube.deletePlaylist(playlistId).map { Unit }
    }

    suspend fun setSongLiked(videoId: String, liked: Boolean): Result<Unit> = run {
        prepareProvidedSession()
        YouTube.likeVideo(videoId, liked).map { Unit }
    }

    suspend fun sendNotInterestedFeedback(item: YTItem): Result<Unit> = run {
        val feedbackToken =
            item.feedbackToken?.takeIf(String::isNotBlank)
                ?: return Result.failure(IllegalArgumentException("This recommendation has no YouTube feedback token"))
        prepareProvidedSession()
        YouTube.sendFeedback(feedbackToken).map { Unit }
    }

    suspend fun setArtistSubscribed(channelId: String, subscribed: Boolean): Result<Unit> = run {
        prepareProvidedSession()
        YouTube.subscribeChannel(channelId, subscribed).map { Unit }
    }

    /** Copies an externally supplied image into YouTube Music's custom playlist-cover endpoint. */
    suspend fun uploadCustomPlaylistCover(
        playlistId: String,
        image: ByteArray,
    ): Result<String> = run {
        prepareProvidedSession()
        YouTube.uploadCustomPlaylistCover(playlistId, image)
    }

    /** Liked albums, mirroring Android's syncLikedAlbums source. */
    suspend fun libraryAlbums(): Result<LibraryPage> = library("FEmusic_liked_albums")

    /** Subscribed artists, mirroring Android's syncArtistsSubscriptions source. */
    suspend fun libraryArtists(): Result<LibraryPage> = library("FEmusic_library_corpus_artists")

    /** Library songs, mirroring Android's syncLibrarySongs source. */
    suspend fun librarySongs(): Result<LibraryPage> = library("FEmusic_liked_videos")

    /** Liked songs, mirroring Android's syncLikedSongs source (the private LM playlist). */
    suspend fun likedSongs(): Result<PlaylistPage> = playlist("LM")

    /** Next/watch response used to discover the related-items endpoint. */
    suspend fun next(videoId: String): Result<NextResult> = run {
        prepareProvidedSession()
        YouTube.next(WatchEndpoint(videoId = videoId))
    }

    /**
     * Android-style single-song Up Next queue used by search playback.
     *
     * Android first loads a normal YouTubeQueue with automix-preview following disabled. When that
     * initial queue has no continuation and is already within three songs of its end, MusicService
     * immediately bootstraps the infinite/radio queue with automix-preview following enabled. Do
     * the same here so desktop doesn't get stuck with the common one-song "Mix" response.
     */
    suspend fun upNext(videoId: String): Result<NextResult> = run {
        prepareProvidedSession()
        val endpoint = WatchEndpoint(videoId = videoId)
        YouTube.next(
            endpoint = endpoint,
            followAutomixPreview = false,
        ).mapCatching { initial ->
            val currentIndex = (initial.currentIndex ?: 0).coerceAtLeast(0)
            val remainingFromCurrent = initial.items.size - currentIndex
            if (initial.continuation == null && remainingFromCurrent <= 3) {
                YouTube.next(
                    endpoint = endpoint,
                    followAutomixPreview = true,
                ).getOrNull()
                    ?.takeIf { expanded -> expanded.items.size > initial.items.size }
                    ?: initial
            } else {
                initial
            }
        }
    }

    /** Related artists/songs/albums/playlists for a seed. */
    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> = run {
        prepareProvidedSession()
        YouTube.related(endpoint)
    }

    /** Resolve an actual YouTube audio stream suitable for a desktop media engine. */
    suspend fun resolveAudioStream(
        videoId: String,
        playlistId: String? = null,
        quality: JvmAudioQuality = JvmAudioQuality.HIGH,
    ): Result<ResolvedAudioStream> =
        try {
            prepareProvidedSession()
            val resolvedVideoId = normalizedId(videoId, "Video ID").getOrThrow()
            val resolvedPlaylistId = playlistId?.trim()?.takeIf(String::isNotEmpty)
            val stream = resolveStreamWithinTimeout(STREAM_RESOLUTION_TIMEOUT_MILLIS) {
                resolveAudioStreamOrThrow(resolvedVideoId, resolvedPlaylistId, quality)
            }
            Result.success(stream)
        } catch (timeout: TimeoutCancellationException) {
            val failure = StreamResolutionTimeoutException()
            Result.failure(failure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    /**
     * Resolves a stream and, only when YouTube explicitly says the stored video id is unavailable,
     * performs one strict metadata lookup for a replacement upload of the same song. This is aimed
     * at persisted queues/library rows whose old YouTube Music id was retired while the track itself
     * still exists under a new upload. Network/extractor/auth failures never trigger title matching.
     */
    suspend fun resolveAudioStreamWithMetadataFallback(
        videoId: String,
        title: String,
        artistNames: List<String>,
        albumTitle: String? = null,
        durationHintMs: Long? = null,
        playlistId: String? = null,
        quality: JvmAudioQuality = JvmAudioQuality.HIGH,
    ): Result<ResolvedAudioStream> {
        val original = resolveAudioStream(videoId, playlistId, quality)
        val originalFailure = original.exceptionOrNull() ?: return original
        if (!isExplicitlyUnavailableStreamFailure(originalFailure)) return original

        val normalizedTitle = normalizeStreamIdentity(title)
        val normalizedArtists = artistNames.map(::normalizeStreamIdentity).filter(String::isNotEmpty).toSet()
        if (normalizedTitle.isEmpty() || normalizedArtists.isEmpty()) return original

        val query = listOf(title, artistNames.first()).joinToString(" ")
        val candidates =
            searchSongs(query)
                .getOrNull()
                .orEmpty()
                .asSequence()
                .filter { it.id != videoId }
                .filter { normalizeStreamIdentity(it.title) == normalizedTitle }
                .filter { song -> song.artists.any { normalizeStreamIdentity(it.name) in normalizedArtists } }
                .filter { song ->
                    val candidateDurationMs = song.duration?.times(1_000L)
                    durationHintMs == null || candidateDurationMs == null ||
                        kotlin.math.abs(candidateDurationMs - durationHintMs) <= STREAM_REPLACEMENT_DURATION_TOLERANCE_MS
                }
                .sortedWith(
                    compareByDescending<SongItem> { song ->
                        albumTitle != null && normalizeStreamIdentity(song.album?.name.orEmpty()) == normalizeStreamIdentity(albumTitle)
                    }.thenBy { song ->
                        val candidateDurationMs = song.duration?.times(1_000L)
                        if (durationHintMs == null || candidateDurationMs == null) Long.MAX_VALUE
                        else kotlin.math.abs(candidateDurationMs - durationHintMs)
                    },
                )
                .take(MAX_STREAM_REPLACEMENT_CANDIDATES)
                .toList()

        for (candidate in candidates) {
            val replacement = resolveAudioStream(candidate.id, playlistId, quality)
            if (replacement.isSuccess) return replacement
        }
        return original
    }

    private suspend fun resolveAudioStreamOrThrow(
        videoId: String,
        playlistId: String?,
        quality: JvmAudioQuality,
    ): ResolvedAudioStream {
        var authState = ensureSessionAuthContext(YouTube.currentPlaybackAuthState())
        authState = preparePlaybackAuth(videoId, authState)
        val signatureTimestamp = resolveSignatureTimestamp(videoId)
        val failures = mutableListOf<Throwable>()

        val clients = orderedStreamClients(authState, videoId)
        for (wave in clients.chunked(MAX_PARALLEL_STREAM_CLIENTS)) {
            val waveResult =
                resolveStreamClientWave(
                    clients = wave,
                    videoId = videoId,
                    playlistId = playlistId,
                    quality = quality,
                    authState = authState,
                    signatureTimestamp = signatureTimestamp,
                )
            failures += waveResult.failures
            waveResult.stream?.let { stream ->
                lastSuccessfulStreamClientKey = stream.clientKey
                return stream
            }
        }

        throw StreamResolutionException(videoId, failures)
    }

    private suspend fun resolveSignatureTimestamp(videoId: String): Int? =
        try {
            withTimeout(SIGNATURE_TIMESTAMP_TIMEOUT_MILLIS) {
                streamUrlResolver.signatureTimestamp(videoId).getOrNull()
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        }

    private suspend fun preparePlaybackAuth(
        videoId: String,
        initial: PlaybackAuthState,
    ): PlaybackAuthState =
        try {
            withTimeout(PLAYBACK_AUTH_PREP_TIMEOUT_MILLIS) {
                ensurePlaybackAuth(videoId, initial)
            }
        } catch (_: TimeoutCancellationException) {
            initial
        } catch (cancellation: CancellationException) {
            throw cancellation
        }

    private suspend fun resolveStreamClientWave(
        clients: List<YouTubeClient>,
        videoId: String,
        playlistId: String?,
        quality: JvmAudioQuality,
        authState: PlaybackAuthState,
        signatureTimestamp: Int?,
    ): StreamClientWaveResult = coroutineScope {
        if (clients.isEmpty()) return@coroutineScope StreamClientWaveResult()

        val results = Channel<StreamClientAttempt>(capacity = clients.size)
        val jobs =
            clients.map { client ->
                launch {
                    results.send(
                        StreamClientAttempt(
                            result =
                                try {
                                    Result.success(
                                        withTimeout(STREAM_CLIENT_TIMEOUT_MILLIS) {
                                            resolveAudioStreamForClient(
                                                client = client,
                                                videoId = videoId,
                                                playlistId = playlistId,
                                                quality = quality,
                                                authState = authState,
                                                signatureTimestamp = signatureTimestamp,
                                            )
                                        },
                                    )
                                } catch (_: TimeoutCancellationException) {
                                    Result.failure(StreamClientTimeoutException(client.clientName))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (failure: Throwable) {
                                    Result.failure(failure)
                                },
                        ),
                    )
                }
            }

        val failures = mutableListOf<Throwable>()
        try {
            repeat(clients.size) {
                val attempt = results.receive()
                val stream = attempt.result.getOrNull()
                if (stream != null) {
                    jobs.forEach { job -> if (job.isActive) job.cancel() }
                    return@coroutineScope StreamClientWaveResult(stream = stream, failures = failures)
                }
                attempt.result.exceptionOrNull()?.let(failures::add)
            }
            StreamClientWaveResult(failures = failures)
        } finally {
            jobs.forEach { job -> if (job.isActive) job.cancel() }
            results.close()
        }
    }

    private suspend fun resolveAudioStreamForClient(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        quality: JvmAudioQuality,
        authState: PlaybackAuthState,
        signatureTimestamp: Int?,
    ): ResolvedAudioStream {
        if (client.loginRequired && !authState.hasPlaybackLoginContext) {
            throw StreamPlaybackUnavailableException(videoId, client.clientName, "LOGIN_REQUIRED", null)
        }
        val useLogin = authState.hasLoginCookie && client.supportsCookieAuthentication
        val playerResponse =
            YouTube.player(
                videoId = videoId,
                playlistId = playlistId,
                client = client,
                signatureTimestamp = signatureTimestamp.takeIf { client.useSignatureTimestamp },
                setLogin = useLogin,
                authState = authState,
            ).getOrThrow()

        if (playerResponse.playabilityStatus.status != "OK") {
            throw StreamPlaybackUnavailableException(
                videoId = videoId,
                clientName = client.clientName,
                status = playerResponse.playabilityStatus.status,
                reason = playerResponse.playabilityStatus.reason,
            )
        }

        val audioFormats = selectAudioFormatCandidates(playerResponse, quality)
        if (audioFormats.isEmpty()) throw StreamAudioFormatUnavailableException(videoId, client.clientName)

        val formatFailures = mutableListOf<Throwable>()
        for (format in audioFormats) {
            val streamUrlResult = streamUrlResolver.resolve(format, videoId, client, authState)
            val streamUrlFailure = streamUrlResult.exceptionOrNull()
            if (streamUrlFailure != null) {
                formatFailures += streamUrlFailure
                continue
            }
            val streamUrl = streamUrlResult.getOrThrow()
            if (!streamUrl.isHttpUrl()) {
                formatFailures += IllegalStateException("Resolved stream URL was not HTTP(S)")
                continue
            }

            val expiresInSeconds = resolveExpirySeconds(playerResponse.streamingData?.expiresInSeconds, streamUrl)
            val stream = ResolvedAudioStream(
                videoId = videoId,
                url = streamUrl,
                format = format,
                videoDetails = playerResponse.videoDetails,
                playbackTracking = playerResponse.playbackTracking,
                audioConfig = playerResponse.playerConfig?.audioConfig,
                clientName = client.clientName,
                clientVersion = client.clientVersion,
                requestHeaders = requestHeaders(client),
                expiresAtEpochMillis = System.currentTimeMillis() + expiresInSeconds * 1_000L,
                authFingerprint = authState.fingerprint,
            )
            val probeFailure = probeResolvedAudioStream(stream).exceptionOrNull()
            if (probeFailure == null) return stream

            val probeHttpStatus = (probeFailure as? AudioStreamProbeHttpException)?.httpStatus
            if (probeHttpStatus != null && probeHttpStatus in RETRYABLE_STREAM_RESPONSE_CODES) {
                markStreamClientFailed(
                    videoId = videoId,
                    clientKey = stream.clientKey,
                    httpStatusCode = probeHttpStatus,
                    authFingerprint = authState.fingerprint,
                )
            }
            formatFailures += probeFailure
        }

        throw StreamResolutionException(videoId, formatFailures)
    }

    private fun applyProvidedSession() {
        sessionProvider?.currentAuthState()?.let { YouTube.authState = it }
    }

    private suspend fun persistProvidedSessionRepair(
        initialAuthState: PlaybackAuthState,
        repairedAuthState: PlaybackAuthState,
    ) {
        val provider = sessionProvider ?: return
        try {
            provider.persistAuthRepair(initialAuthState, repairedAuthState)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Repair persistence is best-effort and must never turn a successful request into a failure.
        }
    }

    /**
     * Gives a desktop-owned session source a chance to revalidate or renew its credentials before
     * every suspendable backend operation. The default provider implementation is a no-op, so the
     * Android/core callers and direct desktop authState users retain their existing behavior.
     */
    private suspend fun prepareProvidedSession() {
        sessionProvider?.refreshIfNeeded()
        applyProvidedSession()
        ensureSessionAuthContext(YouTube.currentPlaybackAuthState())
    }

    /**
     * A restored cookie-only session can be valid for account browse calls but lacks the selected
     * YouTube channel context required by player clients. Derive missing visitor/channel context and
     * persist the repair through the desktop session provider.
     */
    private suspend fun ensureSessionAuthContext(initial: PlaybackAuthState): PlaybackAuthState {
        if (!initial.hasLoginCookie) return initial
        if (!initial.visitorData.isNullOrBlank() && initial.hasPlaybackLoginContext) return initial
        val repaired = withTimeoutOrNull(PLAYBACK_AUTH_PREP_TIMEOUT_MILLIS) {
            resolveAccountContext(initial)
        } ?: return initial
        if (repaired.fingerprint != initial.fingerprint) {
            persistProvidedSessionRepair(initial, repaired)
            // A suspended repair must not restore an account that was signed out or switched.
            if (sessionProvider != null) applyProvidedSession()
            else if (YouTube.authState.cookie == initial.cookie) YouTube.authState = repaired
        }
        return repaired
    }

    /** Shared by imports and restored sessions, including the authenticated homepage fallback. */
    private suspend fun resolveAccountContext(
        initial: PlaybackAuthState,
        refreshVisitor: Boolean = false,
    ): PlaybackAuthState {
        val probeState = initial.copy(dataSyncId = null)
        val channel = initial.dataSyncId ?: optionalAccountValidationStage("accounts_list") {
            accountEndpoint.accountDataSyncId(probeState)
        }?.takeIf(String::isNotBlank)
        val bootstrap = if (refreshVisitor || initial.visitorData.isNullOrBlank() || channel == null) {
            optionalAccountValidationStage("visitor_context") { accountEndpoint.visitorContext(probeState) }
        } else null
        val visitor = bootstrap?.visitorData ?: initial.visitorData
            ?: optionalAccountValidationStage("visitor_fallback") { accountEndpoint.visitorData() }
        // The anonymous homepage can contain a delegated-only identifier. Only a full session
        // identifier is usable as a fallback; an authenticated account menu is still required.
        val bootstrapChannel = bootstrap?.dataSyncId?.takeIf { it.contains(DATA_SYNC_ID_SESSION_SEPARATOR) }
        return initial.copy(visitorData = visitor, dataSyncId = channel ?: bootstrapChannel).normalized()
    }

    private suspend fun ensurePlaybackAuth(
        videoId: String,
        initial: PlaybackAuthState,
    ): PlaybackAuthState {
        val provider = sessionProvider ?: return initial
        val repaired =
            try {
                provider.ensurePlaybackAuth(videoId, initial).normalized()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return initial
            }

        // A proof provider may enrich the current session, but it must never switch accounts or
        // replace the login cookie while a request is being prepared.
        if (repaired.cookie != initial.cookie) return initial
        if (repaired.fingerprint != initial.fingerprint) {
            YouTube.authState = repaired
            persistProvidedSessionRepair(initial, repaired)
        }
        return repaired
    }

    /**
     * Mirrors Android's failed-stream-client backoff. A googlevideo URL can be syntactically valid
     * yet return 403/404/410/416 when the media engine actually opens it. Once desktop reports that
     * concrete failure, keep that client out of the candidate order for this video/session for ten
     * minutes so the transparent retry resolves through another client instead of returning the
     * same rejected URL again.
     */
    fun markStreamClientFailed(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int?,
        authFingerprint: String = YouTube.currentPlaybackAuthState().fingerprint,
    ): Boolean {
        if (httpStatusCode != null && httpStatusCode !in RETRYABLE_STREAM_RESPONSE_CODES) return false
        val normalizedClientKey = PlaybackStreamResolver.normalizeClientKey(clientKey)
        if (normalizedClientKey.isEmpty()) return false
        failedStreamClientsUntil[failedStreamClientKey(videoId, normalizedClientKey, authFingerprint)] =
            System.currentTimeMillis() + FAILED_CLIENT_BACKOFF_MS
        return true
    }

    /**
     * Handles a concrete media-CDN failure before desktop performs its one transparent retry.
     *
     * A logged-in Web stream that was resolved without GVS proof gets one chance to mint the same
     * per-video proof Android uses and retry the same client. If proof was already present (or the
     * client cannot use Web proof), fall back to the normal failed-client backoff instead.
     */
    suspend fun prepareStreamHttpRecovery(
        videoId: String,
        clientKey: String?,
        httpStatusCode: Int,
        authFingerprint: String,
        failedUrlHadPoToken: Boolean,
    ): StreamHttpRecoveryAction {
        if (httpStatusCode !in RETRYABLE_STREAM_RESPONSE_CODES) return StreamHttpRecoveryAction.RETRY_ONLY

        prepareProvidedSession()
        val normalizedClientKey = PlaybackStreamResolver.normalizeClientKey(clientKey)
        val currentAuth = YouTube.currentPlaybackAuthState()
        val sameAuthGeneration = currentAuth.fingerprint == authFingerprint
        val failedClient =
            orderedStreamClients(currentAuth)
                .firstOrNull { PlaybackStreamResolver.clientKey(it) == normalizedClientKey }

        if (
            httpStatusCode == 403 &&
                !failedUrlHadPoToken &&
                currentAuth.hasPlaybackLoginContext &&
                failedClient != null &&
                PlaybackAuthState.supportsGvsPoToken(failedClient)
        ) {
            val repaired = ensurePlaybackAuth(videoId, currentAuth)
            if (
                repaired.fingerprint != currentAuth.fingerprint &&
                    !repaired.resolveGvsPoToken(failedClient, videoId).isNullOrBlank()
            ) {
                return StreamHttpRecoveryAction.PROOF_REFRESHED
            }
        }

        // The URL already proved that this concrete client is unusable for this video. If session
        // repair changed the fingerprint after resolution, block the client in both generations so
        // the transparent retry cannot immediately choose the same rejected client again. A
        // successful proof refresh above is the one exception: in that case retrying the same Web
        // client is intentional because its URL will now be minted with different proof.
        if (!sameAuthGeneration) {
            val blockedFailedGeneration =
                markStreamClientFailed(videoId, normalizedClientKey, httpStatusCode, authFingerprint)
            val blockedCurrentGeneration =
                markStreamClientFailed(videoId, normalizedClientKey, httpStatusCode, currentAuth.fingerprint)
            return if (blockedFailedGeneration || blockedCurrentGeneration) {
                StreamHttpRecoveryAction.CLIENT_BACKOFF
            } else {
                StreamHttpRecoveryAction.RETRY_ONLY
            }
        }

        return if (markStreamClientFailed(videoId, normalizedClientKey, httpStatusCode, authFingerprint)) {
            StreamHttpRecoveryAction.CLIENT_BACKOFF
        } else {
            StreamHttpRecoveryAction.RETRY_ONLY
        }
    }

    private fun orderedStreamClients(
        authState: PlaybackAuthState,
        videoId: String? = null,
    ): List<YouTubeClient> =
        PlaybackStreamResolver.orderClients(
            preference = if (authState.hasLoginCookie) StreamClientPreference.WEB_REMIX else StreamClientPreference.ANDROID_VR,
            authState = authState,
            fallbackClients = streamClients,
            lastSuccessfulClientKey = lastSuccessfulStreamClientKey,
        ).filterNot { client ->
            videoId != null && isStreamClientTemporarilyBlocked(
                videoId = videoId,
                clientKey = PlaybackStreamResolver.clientKey(client),
                authFingerprint = authState.fingerprint,
            )
        }

    private fun isStreamClientTemporarilyBlocked(
        videoId: String,
        clientKey: String?,
        authFingerprint: String,
    ): Boolean {
        val normalizedClientKey = PlaybackStreamResolver.normalizeClientKey(clientKey)
        if (normalizedClientKey.isEmpty()) return false
        val key = failedStreamClientKey(videoId, normalizedClientKey, authFingerprint)
        val until = failedStreamClientsUntil[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedStreamClientsUntil.remove(key, until)
            return false
        }
        return true
    }

    private fun failedStreamClientKey(videoId: String, clientKey: String, authFingerprint: String): String =
        "$authFingerprint:$videoId:${PlaybackStreamResolver.normalizeClientKey(clientKey)}"

    internal fun orderedStreamClientsForTest(
        authState: PlaybackAuthState,
        videoId: String? = null,
    ): List<YouTubeClient> = orderedStreamClients(authState, videoId)

    companion object {
        /**
         * The same broad fallback families used by Android, ordered for anonymous desktop use.
         * Native clients are tried before Web clients because Web playback may legitimately require
         * a proof token; this is a compatibility fallback, not a token or login bypass.
         */
        val DEFAULT_STREAM_CLIENTS: List<YouTubeClient>
            get() = PlaybackStreamResolver.desktopFallbackClients
    }
}

/**
 * The account endpoints used to prove and enrich a candidate session.
 *
 * Every call receives the exact session snapshot it must use, so a candidate session never has to
 * be published to the process-wide session while it is being validated.
 */
internal interface DesktopAccountEndpoint {
    /** Returns the signed-in account. This is the only proof that Google accepted the cookie. */
    suspend fun accountInfo(authState: PlaybackAuthState): Result<AccountInfo>

    /** Returns the selected channel context. This is account enrichment, not proof of sign-in. */
    suspend fun accountDataSyncId(authState: PlaybackAuthState): Result<String>

    /** Returns the visitor and locale context of the session's bootstrap page. */
    suspend fun visitorContext(authState: PlaybackAuthState): Result<ResolvedVisitorContext>

    /** Returns the anonymous visitor identifier used when the bootstrap page is unavailable. */
    suspend fun visitorData(): Result<String>
}

/** The live YouTube implementation of [DesktopAccountEndpoint]. */
internal object YouTubeAccountEndpoint : DesktopAccountEndpoint {
    override suspend fun accountInfo(authState: PlaybackAuthState): Result<AccountInfo> = YouTube.accountInfo(authState)

    override suspend fun accountDataSyncId(authState: PlaybackAuthState): Result<String> = YouTube.accountDataSyncId(authState)

    override suspend fun visitorContext(authState: PlaybackAuthState): Result<ResolvedVisitorContext> = YouTube.resolveVisitorContext(authState)

    override suspend fun visitorData(): Result<String> = YouTube.visitorData()
}

/**
 * Explicit desktop control for ArchiveTune's process-wide network gatekeeper.
 *
 * The official Android build currently defaults `BuildConfig.GATEKEEPER_ENABLED` to false, so
 * desktop follows that default. Applications that enable the gatekeeper must complete their own
 * verification before making backend calls; this module deliberately supplies no bypass for that
 * mode.
 */
data class DesktopNetworkPolicy(
    val gatekeeperEnabled: Boolean = false,
)

/** A desktop-owned, redaction-safe session source accepted by [ArchiveTuneJvmClient]. */
fun interface DesktopSessionProvider {
    /** Never log or persist the returned value outside the provider's secure storage boundary. */
    fun currentAuthState(): PlaybackAuthState

    /**
     * Called immediately before a backend operation. Implementations may revalidate/renew a
     * session, but should fail soft so an offline user can keep using the last verified state.
     */
    suspend fun refreshIfNeeded() = Unit

    /**
     * Gives the desktop session owner a chance to attach per-video playback proof (for example a
     * Web/GVS PO Token) before a signed media URL is resolved. The default is deliberately a no-op
     * so non-browser providers and tests retain their existing behavior.
     */
    suspend fun ensurePlaybackAuth(
        videoId: String,
        authState: PlaybackAuthState,
    ): PlaybackAuthState = authState

    /**
     * Persists non-cookie auth context repaired by core/backend work. Implementations should only
     * accept repairs that still belong to [initialAuthState]'s login cookie and must never replace
     * the cookie itself from this callback.
     */
    suspend fun persistAuthRepair(
        initialAuthState: PlaybackAuthState,
        repairedAuthState: PlaybackAuthState,
    ) = Unit
}

enum class StreamResolutionFailureKind(val userMessage: String) {
    AUTH_REQUIRED("Sign in is required for this YouTube track"),
    EXTRACTOR_INCOMPATIBLE("YouTube changed its playback response; try again after updating ArchiveTune"),
    NETWORK_TIMEOUT("YouTube did not respond in time; check your connection and retry"),
    NETWORK("Could not reach YouTube; check your connection and retry"),
    UNAVAILABLE("This YouTube track is currently unavailable for playback"),
}

enum class StreamHttpRecoveryAction {
    PROOF_REFRESHED,
    CLIENT_BACKOFF,
    RETRY_ONLY,
}

class StreamResolutionTimeoutException : IllegalStateException("YouTube stream resolution timed out")

class StreamClientTimeoutException(clientName: String) : IllegalStateException("YouTube player client timed out: $clientName")

private data class StreamClientAttempt(val result: Result<ResolvedAudioStream>)

private data class StreamClientWaveResult(
    val stream: ResolvedAudioStream? = null,
    val failures: List<Throwable> = emptyList(),
)

/** Classifies only exception types and explicit playability states; never inspect/log URL-bearing messages. */
fun classifyStreamResolutionFailure(failure: Throwable): StreamResolutionFailureKind {
    val all = generateSequence(failure) { it.cause }.toList() + failure.suppressed.toList()
    if (all.any { it is StreamResolutionTimeoutException }) {
        return StreamResolutionFailureKind.NETWORK_TIMEOUT
    }
    // A concrete player playability response is stronger evidence than one fallback client's
    // extractor/parsing failure. Do not tell the user to update ArchiveTune merely because NewPipe
    // failed after YouTube had already returned an explicit unavailable/login state.
    if (all.filterIsInstance<StreamPlaybackUnavailableException>().any { !it.status.equals("LOGIN_REQUIRED", ignoreCase = true) }) {
        return StreamResolutionFailureKind.UNAVAILABLE
    }
    if (all.filterIsInstance<StreamPlaybackUnavailableException>().any { it.status.equals("LOGIN_REQUIRED", ignoreCase = true) }) {
        return StreamResolutionFailureKind.AUTH_REQUIRED
    }
    if (all.any { throwable ->
            throwable is StreamAudioFormatUnavailableException ||
                throwable::class.simpleName.orEmpty().let { name ->
                    name.contains("Parsing", ignoreCase = true) ||
                        name.contains("Cipher", ignoreCase = true) ||
                        name.contains("Signature", ignoreCase = true)
                }
        }
    ) return StreamResolutionFailureKind.EXTRACTOR_INCOMPATIBLE
    if (all.any { it::class.simpleName.orEmpty().contains("Timeout", ignoreCase = true) }) {
        return StreamResolutionFailureKind.NETWORK_TIMEOUT
    }
    if (all.any { it is java.io.IOException }) return StreamResolutionFailureKind.NETWORK
    return StreamResolutionFailureKind.UNAVAILABLE
}

internal fun isExplicitlyUnavailableStreamFailure(failure: Throwable): Boolean {
    val all = generateSequence(failure) { it.cause }.toList() + failure.suppressed.toList()
    return all.filterIsInstance<StreamPlaybackUnavailableException>().any { unavailable ->
        !unavailable.status.equals("LOGIN_REQUIRED", ignoreCase = true) &&
            unavailable.reason.orEmpty().contains("unavailable", ignoreCase = true)
    }
}

internal suspend fun <T> resolveStreamWithinTimeout(timeoutMillis: Long, block: suspend () -> T): T =
    withTimeout(timeoutMillis) { block() }

enum class JvmAudioQuality {
    LOW,
    HIGH,
    HIGHEST,
}

data class ResolvedAudioStream(
    val videoId: String,
    val url: String,
    val format: PlayerResponse.StreamingData.Format,
    val videoDetails: PlayerResponse.VideoDetails?,
    val playbackTracking: PlayerResponse.PlaybackTracking?,
    val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
    val clientName: String,
    val clientVersion: String,
    val requestHeaders: Map<String, String>,
    val expiresAtEpochMillis: Long,
    val authFingerprint: String,
) {
    val mimeType: String
        get() = format.mimeType

    val bitrate: Int
        get() = format.bitrate

    val contentLength: Long?
        get() = format.contentLength

    val clientKey: String
        get() = PlaybackStreamResolver.normalizeClientKey("$clientName@$clientVersion")
}

/** A successfully validated account session. Do not log [authState]. */
data class VerifiedBackendAccount(
    val authState: PlaybackAuthState,
    val account: AccountInfo,
)

class AccountValidationTimeoutException : IllegalStateException("YouTube account validation timed out")

enum class AccountValidationFailureKind { NETWORK, TIMEOUT, INVALID_REQUEST, SERVICE_UNAVAILABLE, UNKNOWN }

/** Classifies types/status only; server messages and cookie material never reach the import UI. */
fun classifyAccountValidationFailure(failure: Throwable): AccountValidationFailureKind {
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    if (causes.any {
            it is AccountValidationTimeoutException || it is io.ktor.client.plugins.HttpRequestTimeoutException ||
                it is java.net.SocketTimeoutException ||
                it is java.net.http.HttpTimeoutException
        }) return AccountValidationFailureKind.TIMEOUT
    val status = causes.filterIsInstance<ResponseException>().firstOrNull()?.response?.status?.value
    if (status == 400) return AccountValidationFailureKind.INVALID_REQUEST
    if (status == 429 || status != null && status >= 500) return AccountValidationFailureKind.SERVICE_UNAVAILABLE
    if (causes.any { it is java.io.IOException }) return AccountValidationFailureKind.NETWORK
    return AccountValidationFailureKind.UNKNOWN
}

fun interface StreamUrlResolver {
    suspend fun resolve(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient,
        authState: PlaybackAuthState,
    ): Result<String>

    suspend fun signatureTimestamp(videoId: String): Result<Int?> = Result.success(null)
}

private object NewPipeStreamUrlResolver : StreamUrlResolver {
    override suspend fun resolve(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient,
        authState: PlaybackAuthState,
    ): Result<String> = PlaybackStreamResolver.resolveUrl(format, videoId, client, authState)

    override suspend fun signatureTimestamp(videoId: String): Result<Int?> =
        PlaybackStreamResolver.signatureTimestamp(videoId)
}

class StreamPlaybackUnavailableException(
    val videoId: String,
    val clientName: String,
    val status: String,
    val reason: String?,
) : IllegalStateException(
        buildString {
            append("YouTube playback unavailable for ")
            append(videoId)
            append(" using ")
            append(clientName)
            append(": ")
            append(status)
            reason?.takeIf(String::isNotBlank)?.let { append(" (").append(it).append(')') }
        },
)

class StreamAudioFormatUnavailableException(
    val videoId: String,
    val clientName: String,
) : IllegalStateException("YouTube returned no usable audio formats for $videoId using $clientName")

class StreamResolutionException(
    val videoId: String,
    failures: List<Throwable>,
) : IllegalStateException("No playable YouTube audio stream was resolved for $videoId") {
    init {
        failures.distinctBy { it::class to it.message }.take(MAX_REPORTED_FAILURES).forEach(::addSuppressed)
    }

    private companion object {
        const val MAX_REPORTED_FAILURES = 12
    }
}

internal fun selectAudioFormatCandidates(
    playerResponse: PlayerResponse,
    quality: JvmAudioQuality,
): List<PlayerResponse.StreamingData.Format> =
    PlaybackStreamResolver.selectAudioFormatCandidates(
        playerResponse = playerResponse,
        quality =
            when (quality) {
                JvmAudioQuality.LOW -> StreamAudioQuality.LOW
                JvmAudioQuality.HIGH -> StreamAudioQuality.HIGH
                JvmAudioQuality.HIGHEST -> StreamAudioQuality.HIGHEST
            },
        includeMuxedFormats = false,
    )

internal fun requestHeaders(client: YouTubeClient): Map<String, String> =
    PlaybackStreamResolver.requestHeaders(client)

private fun resolveExpirySeconds(
    apiExpirySeconds: Int?,
    url: String,
): Long = PlaybackStreamResolver.resolveExpirySeconds(apiExpirySeconds, url).toLong()

private fun String.isHttpUrl(): Boolean =
    runCatching { URI(this).scheme?.lowercase(Locale.US) in setOf("http", "https") }.getOrDefault(false)

private fun normalizedQuery(query: String): Result<String> =
    query.trim().takeIf(String::isNotEmpty)?.let(Result.Companion::success)
        ?: Result.failure(IllegalArgumentException("Search query is required"))

private fun normalizedId(
    id: String,
    field: String,
): Result<String> =
    id.trim().takeIf(String::isNotEmpty)?.let(Result.Companion::success)
        ?: Result.failure(IllegalArgumentException("$field is required"))

private fun normalizeStreamIdentity(value: String): String =
    value
        .lowercase(Locale.US)
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")

private const val MIN_FULL_ALBUM_TRACKS = 7
private val ARTIST_ALBUM_RECOVERY_LOCALE = YouTubeLocale(gl = "US", hl = "en")
private const val MAX_STREAM_REPLACEMENT_CANDIDATES = 3
private const val STREAM_REPLACEMENT_DURATION_TOLERANCE_MS = 12_000L
private const val STREAM_RESOLUTION_TIMEOUT_MILLIS = 45_000L
private const val STREAM_CLIENT_TIMEOUT_MILLIS = 12_000L
private const val SIGNATURE_TIMESTAMP_TIMEOUT_MILLIS = 5_000L
private const val PLAYBACK_AUTH_PREP_TIMEOUT_MILLIS = 15_000L
private const val MAX_PARALLEL_STREAM_CLIENTS = 4
private const val FAILED_CLIENT_BACKOFF_MS = 10 * 60 * 1000L

/** Separates the delegated session id from the signed-in session id of a DataSyncId value. */
private const val DATA_SYNC_ID_SESSION_SEPARATOR = "||"
private val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)

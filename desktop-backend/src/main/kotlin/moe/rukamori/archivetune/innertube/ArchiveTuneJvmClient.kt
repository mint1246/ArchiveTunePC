/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.AccountInfo
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YouTubeClient
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
import moe.rukamori.archivetune.innertube.pages.RelatedPage
import moe.rukamori.archivetune.innertube.pages.SearchResult
import moe.rukamori.archivetune.innertube.pages.SearchSummaryPage
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.utils.completed
import moe.rukamori.archivetune.morideobfuscator.MoriCipherConfig
import moe.rukamori.archivetune.morideobfuscator.MoriCipherRuntime
import java.io.File
import java.net.URI
import java.util.Locale

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
    private val streamDiagnostics: StreamResolutionDiagnosticSink = StreamResolutionDiagnosticSink { },
) {
    @Volatile
    private var sessionProvider: DesktopSessionProvider? = sessionProvider

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

    /** Sends a recommendation feedback token (for example, "Not interested"). */
    suspend fun feedback(feedbackToken: String): Result<Unit> = run {
        prepareProvidedSession()
        YouTube.feedback(feedbackToken)
    }

    /** Verifies a user-supplied authenticated YouTube session without exposing its secret values. */
    suspend fun verifyAccountSession(candidate: PlaybackAuthState): Result<VerifiedBackendAccount> =
        try {
            require(candidate.hasLoginCookie) { "YouTube login cookie is missing" }
            val initial = candidate.normalized()
            // Account metadata can be requested before a selected channel/DataSyncId is known.
            YouTube.authState = initial.copy(dataSyncId = null)
            val account = YouTube.accountInfo().getOrThrow()
            val dataSyncId = YouTube.accountDataSyncId().getOrThrow()
            // Match the browser's personalized-browse context: the region-encoded visitor
            // identifier and the account region/language both come from the sw.js lookup. The raw
            // VISITOR_INFO1_LIVE cookie is a different identifier and downgrades the feed.
            val resolved =
                YouTube.resolveVisitorContext().getOrNull()
                    ?: YouTube.visitorData().getOrNull()?.let { visitor ->
                        moe.rukamori.archivetune.innertube.ResolvedVisitorContext(
                            visitorData = visitor,
                            locale = YouTube.locale,
                        )
                    }
            resolved?.let { YouTube.locale = it.locale }
            // Always prefer the fresh sw.js visitor identifier. A persisted value may carry the
            // raw VISITOR_INFO1_LIVE cookie from an older import, which downgrades browse results.
            val visitorData = resolved?.visitorData ?: initial.visitorData
            val verified = initial.copy(visitorData = visitorData, dataSyncId = dataSyncId).normalized()
            YouTube.authState = verified
            Result.success(VerifiedBackendAccount(verified, account))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
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
     * Some YouTube Music artist responses expose only a fixed Albums carousel and no "more"
     * endpoint, even while songs still point at additional valid album browse IDs. Recover those
     * full albums from the artist's song search so desktop does not silently hide them.
     */
    private suspend fun recoverUnlistedArtistAlbums(page: ArtistPage): ArtistPage {
        val albumSectionIndex = page.sections.indexOfFirst { section ->
            section.items.any { it is AlbumItem }
        }
        if (albumSectionIndex < 0) return page

        val albumSection = page.sections[albumSectionIndex]
        if (albumSection.moreEndpoint != null) return page

        val knownAlbumIds =
            page.sections
                .asSequence()
                .flatMap { it.items.asSequence() }
                .filterIsInstance<AlbumItem>()
                .mapTo(mutableSetOf()) { it.browseId }

        val searchItems =
            YouTube.search(page.artist.title, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                .orEmpty()

        val candidateAlbumIds =
            searchItems
                .asSequence()
                .filterIsInstance<SongItem>()
                .filter { song -> song.artists.any { it.id == page.artist.id } }
                .mapNotNull { it.album?.id }
                .filterNot(knownAlbumIds::contains)
                .distinct()
                .take(MAX_RECOVERED_ARTIST_ALBUMS)
                .toList()
        if (candidateAlbumIds.isEmpty()) return page

        val recovered =
            candidateAlbumIds.mapNotNull { albumId ->
                val albumPage = YouTube.album(albumId).getOrNull() ?: return@mapNotNull null
                val belongsToArtist =
                    albumPage.album.artists.orEmpty().any { it.id == page.artist.id } ||
                        albumPage.songs.any { song -> song.artists.any { it.id == page.artist.id } }
                if (!belongsToArtist || albumPage.songs.size < MIN_FULL_ALBUM_TRACKS) return@mapNotNull null
                albumPage.album
            }
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
                    YouTube.authState = initial.copy(dataSyncId = dataSyncId.ifBlank { null }).normalized()
                    val result = YouTube.playlist(normalizedPlaylistId).completed()
                    if (result.isSuccess) return@fold result
                    lastFailure = result.exceptionOrNull()
                }

                Result.failure(lastFailure ?: IllegalStateException("YouTube Music playlist is unavailable"))
            },
            onFailure = { Result.failure(it) },
            )
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

        for (dataSyncId in candidates.ifEmpty { listOf("") }) {
            YouTube.authState = initial.copy(dataSyncId = dataSyncId.ifBlank { null }).normalized()
            val result =
                YouTube.library(browseId) { shape ->
                    streamDiagnostics.record(
                        StreamResolutionDiagnostic.LibraryResponse(
                            singleTabs = shape.singleTabs,
                            twoColumnTabs = shape.twoColumnTabs,
                            directSections = shape.directSections,
                            secondarySections = shape.secondarySections,
                            selectedSections = shape.selectedSections,
                            rendererItems = shape.rendererItems,
                            parsedItems = shape.parsedItems,
                            rendererKeys = shape.rendererKeys,
                        ),
                    )
                }.completed()
            result.onSuccess(pages::add).onFailure { lastFailure = it }
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
            streamDiagnostics.record(StreamResolutionDiagnostic.Success(stream.clientName))
            Result.success(stream)
        } catch (timeout: TimeoutCancellationException) {
            val failure = StreamResolutionTimeoutException()
            streamDiagnostics.record(StreamResolutionDiagnostic.Failure(StreamResolutionFailureKind.NETWORK_TIMEOUT))
            Result.failure(failure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            streamDiagnostics.record(StreamResolutionDiagnostic.Failure(classifyStreamResolutionFailure(failure)))
            Result.failure(failure)
        }

    private suspend fun resolveAudioStreamOrThrow(
        videoId: String,
        playlistId: String?,
        quality: JvmAudioQuality,
    ): ResolvedAudioStream {
        val authState = ensurePlaybackAuthContext(YouTube.currentPlaybackAuthState())
        val signatureTimestamp = streamUrlResolver.signatureTimestamp(videoId).getOrNull()
        val failures = mutableListOf<Throwable>()

        for (client in orderedStreamClients(authState)) {
            if (client.loginRequired && !authState.hasPlaybackLoginContext) continue
            val useLogin = authState.hasLoginCookie && client.supportsCookieAuthentication
            val playerResult =
                YouTube.player(
                    videoId = videoId,
                    playlistId = playlistId,
                    client = client,
                    signatureTimestamp = signatureTimestamp.takeIf { client.useSignatureTimestamp },
                    setLogin = useLogin,
                    authState = authState,
                )
            val playerFailure = playerResult.exceptionOrNull()
            if (playerFailure != null) {
                failures += playerFailure
                streamDiagnostics.record(StreamResolutionDiagnostic.Attempt(client.clientName, classifyStreamResolutionFailure(playerFailure)))
                continue
            }
            val playerResponse = playerResult.getOrThrow()
            streamDiagnostics.record(playerResponse.toDiagnostic(client.clientName))

            if (playerResponse.playabilityStatus.status != "OK") {
                failures +=
                    StreamPlaybackUnavailableException(
                        videoId = videoId,
                        clientName = client.clientName,
                        status = playerResponse.playabilityStatus.status,
                        reason = playerResponse.playabilityStatus.reason,
                    )
                streamDiagnostics.record(
                    StreamResolutionDiagnostic.Attempt(
                        client.clientName,
                        if (playerResponse.playabilityStatus.status.equals("LOGIN_REQUIRED", ignoreCase = true)) {
                            StreamResolutionFailureKind.AUTH_REQUIRED
                        } else {
                            StreamResolutionFailureKind.UNAVAILABLE
                        },
                    ),
                )
                continue
            }

            val audioFormats = selectAudioFormatCandidates(playerResponse, quality)
            if (audioFormats.isEmpty()) {
                failures += StreamAudioFormatUnavailableException(videoId, client.clientName)
                streamDiagnostics.record(StreamResolutionDiagnostic.Attempt(client.clientName, StreamResolutionFailureKind.EXTRACTOR_INCOMPATIBLE))
                continue
            }
            for (format in audioFormats) {
                val streamUrlResult = streamUrlResolver.resolve(format, videoId, client, authState)
                val streamUrlFailure = streamUrlResult.exceptionOrNull()
                if (streamUrlFailure != null) {
                    failures += streamUrlFailure
                    streamDiagnostics.record(
                        StreamResolutionDiagnostic.FormatAttempt(
                            client = client.clientName,
                            format = if (format.url != null) "direct" else "cipher",
                            classification = classifyStreamResolutionFailure(streamUrlFailure),
                        ),
                    )
                    continue
                }
                val streamUrl = streamUrlResult.getOrThrow().patchClientVersion(client.clientVersion)

                if (!streamUrl.isHttpUrl()) {
                    failures += IllegalStateException("Resolved stream URL was not HTTP(S)")
                    streamDiagnostics.record(StreamResolutionDiagnostic.Attempt(client.clientName, StreamResolutionFailureKind.EXTRACTOR_INCOMPATIBLE))
                    continue
                }

                val expiresInSeconds = resolveExpirySeconds(playerResponse.streamingData?.expiresInSeconds, streamUrl)
                return ResolvedAudioStream(
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
            }
        }

        throw StreamResolutionException(videoId, failures)
    }

    private fun applyProvidedSession() {
        sessionProvider?.currentAuthState()?.let { YouTube.authState = it }
    }

    /**
     * Gives a desktop-owned session source a chance to revalidate or renew its credentials before
     * every suspendable backend operation. The default provider implementation is a no-op, so the
     * Android/core callers and direct desktop authState users retain their existing behavior.
     */
    private suspend fun prepareProvidedSession() {
        sessionProvider?.refreshIfNeeded()
        applyProvidedSession()
    }

    /**
     * A restored cookie-only session can be valid for account browse calls but lacks the selected
     * YouTube channel context required by player clients. Derive it through ArchiveTune's existing
     * account-switcher endpoint; values remain in memory and are never logged or persisted here.
     */
    private suspend fun ensurePlaybackAuthContext(initial: PlaybackAuthState): PlaybackAuthState {
        if (!initial.hasLoginCookie || initial.hasPlaybackLoginContext) return initial
        val dataSyncId = YouTube.accountDataSyncId().getOrNull() ?: return initial
        return initial.copy(dataSyncId = dataSyncId).normalized().also { YouTube.authState = it }
    }

    private fun orderedStreamClients(authState: PlaybackAuthState): List<YouTubeClient> =
        if (!authState.hasLoginCookie) {
            streamClients
        } else {
            (listOf(YouTubeClient.WEB_PRIMARY) + streamClients)
                .distinctBy { it.clientName to it.clientVersion }
                .sortedWith(
                compareByDescending<YouTubeClient> { it.supportsCookieAuthentication }
                    .thenByDescending { it.loginSupported },
                )
        }

    internal fun orderedStreamClientsForTest(authState: PlaybackAuthState): List<YouTubeClient> =
        orderedStreamClients(authState)

    companion object {
        /**
         * The same broad fallback families used by Android, ordered for anonymous desktop use.
         * Native clients are tried before Web clients because Web playback may legitimately require
         * a proof token; this is a compatibility fallback, not a token or login bypass.
         */
        val DEFAULT_STREAM_CLIENTS: List<YouTubeClient>
            get() =
                listOf(
                    YouTubeClient.ANDROID_VR_1_65_10,
                    YouTubeClient.IOS,
                    YouTubeClient.ANDROID_MUSIC,
                    YouTubeClient.IOS_MUSIC,
                    YouTubeClient.MOBILE,
                    YouTubeClient.WEB_REMIX,
                    YouTubeClient.IPADOS,
                    YouTubeClient.VISIONOS,
                    YouTubeClient.TVHTML5,
                    YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
                    YouTubeClient.WEB,
                )
    }
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
}

/** Redacted stream-resolution events suitable for a per-user application log. */
fun interface StreamResolutionDiagnosticSink {
    fun record(event: StreamResolutionDiagnostic)
}

sealed interface StreamResolutionDiagnostic {
    data class Attempt(val client: String, val classification: StreamResolutionFailureKind) : StreamResolutionDiagnostic
    data class FormatAttempt(val client: String, val format: String, val classification: StreamResolutionFailureKind) : StreamResolutionDiagnostic
    data class PlayerResponse(
        val client: String,
        val status: String,
        val adaptiveFormats: Int,
        val audioFormats: Int,
        val directUrls: Int,
        val ciphers: Int,
    ) : StreamResolutionDiagnostic
    data class LibraryResponse(
        val singleTabs: Int,
        val twoColumnTabs: Int,
        val directSections: Int,
        val secondarySections: Int,
        val selectedSections: Int,
        val rendererItems: Int,
        val parsedItems: Int,
        val rendererKeys: List<String>,
    ) : StreamResolutionDiagnostic
    data class Success(val client: String) : StreamResolutionDiagnostic
    data class Failure(val classification: StreamResolutionFailureKind) : StreamResolutionDiagnostic
}

enum class StreamResolutionFailureKind(val userMessage: String) {
    AUTH_REQUIRED("Sign in is required for this YouTube track"),
    EXTRACTOR_INCOMPATIBLE("YouTube changed its playback response; try again after updating ArchiveTune"),
    NETWORK_TIMEOUT("YouTube did not respond in time; check your connection and retry"),
    NETWORK("Could not reach YouTube; check your connection and retry"),
    UNAVAILABLE("This YouTube track is currently unavailable for playback"),
}

class StreamResolutionTimeoutException : IllegalStateException("YouTube stream resolution timed out")

/** Classifies only exception types and explicit playability states; never inspect/log URL-bearing messages. */
fun classifyStreamResolutionFailure(failure: Throwable): StreamResolutionFailureKind {
    val all = generateSequence(failure) { it.cause }.toList() + failure.suppressed.toList()
    if (all.any { it is StreamResolutionTimeoutException || it::class.simpleName.orEmpty().contains("Timeout", ignoreCase = true) }) {
        return StreamResolutionFailureKind.NETWORK_TIMEOUT
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
    // When an authenticated Web profile has a concrete non-login failure, preserve it over a
    // later native fallback's expected AUTH_REQUIRED result.
    if (all.filterIsInstance<StreamPlaybackUnavailableException>().any { !it.status.equals("LOGIN_REQUIRED", ignoreCase = true) }) {
        return StreamResolutionFailureKind.UNAVAILABLE
    }
    if (all.filterIsInstance<StreamPlaybackUnavailableException>().any { it.status.equals("LOGIN_REQUIRED", ignoreCase = true) }) {
        return StreamResolutionFailureKind.AUTH_REQUIRED
    }
    if (all.any { it is java.io.IOException }) return StreamResolutionFailureKind.NETWORK
    return StreamResolutionFailureKind.UNAVAILABLE
}

private fun PlayerResponse.toDiagnostic(client: String): StreamResolutionDiagnostic.PlayerResponse {
    val formats = streamingData?.adaptiveFormats.orEmpty() + streamingData?.formats.orEmpty()
    return StreamResolutionDiagnostic.PlayerResponse(
        client = client,
        // Status is a stable enum-like protocol field. Do not expose playability reason text.
        status = playabilityStatus.status.uppercase(Locale.US).take(64),
        adaptiveFormats = streamingData?.adaptiveFormats?.size ?: 0,
        audioFormats = formats.count { it.isAudio },
        directUrls = formats.count { it.url != null },
        ciphers = formats.count { it.signatureCipher != null || it.cipher != null },
    )
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
}

/** A successfully validated account session. Do not log [authState]. */
data class VerifiedBackendAccount(
    val authState: PlaybackAuthState,
    val account: AccountInfo,
)

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
    ): Result<String> = NewPipeUtils.getStreamUrl(format, videoId, client, authState)

    override suspend fun signatureTimestamp(videoId: String): Result<Int?> =
        NewPipeUtils.getSignatureTimestamp(videoId).map { it }
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
): List<PlayerResponse.StreamingData.Format> {
    val audioFormats =
        playerResponse.streamingData
            ?.let { streamingData -> streamingData.adaptiveFormats + streamingData.formats.orEmpty() }
            ?.filter { format ->
                format.isAudio &&
                    format.bitrate > 0 &&
                    (format.url != null || format.signatureCipher != null || format.cipher != null)
            }.orEmpty()
    if (audioFormats.isEmpty()) {
        return playerResponse.streamingData?.formats.orEmpty()
            .filter { it.url != null && !it.isAudio && it.mimeType.hasAudioCodec() }
            .sortedByDescending { it.bitrate }
    }

    val targetBitrate =
        when (quality) {
            JvmAudioQuality.LOW -> 70_000
            JvmAudioQuality.HIGH -> 160_000
            JvmAudioQuality.HIGHEST -> null
        }
    val preferHigher =
        compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
            .thenByDescending { it.bitrate }
            .thenByDescending { codecRank(it.mimeType) }
            .thenByDescending { it.audioSampleRate ?: 0 }
    if (targetBitrate == null) return audioFormats.sortedWith(preferHigher)

    val preferred = audioFormats.filter { it.bitrate <= targetBitrate }.sortedWith(preferHigher)
    val fallback =
        audioFormats
            .filter { it.bitrate > targetBitrate }
            .sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                    .thenBy { it.bitrate }
                    .thenByDescending { codecRank(it.mimeType) }
                    .thenByDescending { it.audioSampleRate ?: 0 },
            )
    return preferred + fallback
}

private fun codecRank(mimeType: String): Int =
    when {
        mimeType.contains("opus", ignoreCase = true) -> 3
        mimeType.contains("mp4a", ignoreCase = true) -> 2
        else -> 1
    }

private fun String.hasAudioCodec(): Boolean =
    contains("mp4a", ignoreCase = true) || contains("opus", ignoreCase = true) ||
        contains("vorbis", ignoreCase = true) || contains("aac", ignoreCase = true)

internal fun requestHeaders(client: YouTubeClient): Map<String, String> =
    buildMap {
        put("User-Agent", client.userAgent)
        when {
            client.clientName.uppercase(Locale.US).startsWith("WEB") -> {
                put("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
                put("Referer", YouTubeClient.REFERER_YOUTUBE_MUSIC)
            }

            client.clientName.uppercase(Locale.US).startsWith("TVHTML5") -> {
                put("Origin", YouTubeClient.ORIGIN_YOUTUBE)
                put("Referer", YouTubeClient.REFERER_YOUTUBE_TV)
            }
        }
    }

private fun resolveExpirySeconds(
    apiExpirySeconds: Int?,
    url: String,
): Long {
    apiExpirySeconds?.takeIf { it > 0 }?.let { return it.toLong() }
    val expiryEpochSeconds =
        runCatching {
            URI(url).rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("expire=") }
                ?.substringAfter('=')
                ?.toLongOrNull()
        }.getOrNull()
    val secondsFromUrl = expiryEpochSeconds?.minus(System.currentTimeMillis() / 1_000L)
    return secondsFromUrl?.takeIf { it > 0 } ?: DEFAULT_STREAM_EXPIRY_SECONDS
}

private fun String.patchClientVersion(clientVersion: String): String =
    if (contains("cver=")) replace(Regex("cver=[^&]+"), "cver=$clientVersion") else this

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

private const val DEFAULT_STREAM_EXPIRY_SECONDS = 300L
private const val MAX_RECOVERED_ARTIST_ALBUMS = 4
private const val MIN_FULL_ALBUM_TRACKS = 7
private const val STREAM_RESOLUTION_TIMEOUT_MILLIS = 45_000L

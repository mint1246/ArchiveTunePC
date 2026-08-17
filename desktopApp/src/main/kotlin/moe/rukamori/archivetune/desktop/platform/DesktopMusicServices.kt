package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.StreamResolutionFailureKind
import moe.rukamori.archivetune.innertube.classifyStreamResolutionFailure
import moe.rukamori.archivetune.desktopauth.ArchiveTuneSessionVerifier
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.desktopauth.DpapiFileSessionStore
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.BuiltInLyricsProviders
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPresenter
import moe.rukamori.archivetune.desktop.library.DesktopLibrary
import moe.rukamori.archivetune.desktop.library.DesktopLibraryFolderStore
import moe.rukamori.archivetune.desktop.library.PropertiesDesktopLibraryFolderStore
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.home.DesktopHomeStore
import moe.rukamori.archivetune.innertube.home.DesktopPlayEvent
import moe.rukamori.archivetune.innertube.home.DesktopSimilarRecommendation
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.desktop.DesktopConcretePlaybackSourceResolver
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import moe.rukamori.archivetune.playback.desktop.PlaybackQueueStore
import moe.rukamori.archivetune.windowsmedia.PlaybackMediaCommandRouter
import moe.rukamori.archivetune.windowsmedia.WindowsMediaControls
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The small desktop-only seam between ArchiveTune's backend and the native playback engine. */
class DesktopMusicServices(private val runtime: DesktopRuntime) : AutoCloseable {
    private val settings = runtime.settings
    val settingsStore get() = settings
    val backend = ArchiveTuneJvmClient(runtime.paths.cache.resolve("cipher").toFile().also(File::mkdirs))
    /** The backend consults this provider immediately before each request, including downloads. */
    val account = DesktopSessionManager(
        secureStore = DpapiFileSessionStore(runtime.paths.data.resolve("youtube-session.dpapi")),
        verifier = ArchiveTuneSessionVerifier(backend),
    )
    @Volatile private var completedDownloadLookup: ((String) -> Path?)? = null
    /** Signed sources live only in memory and are discarded before their server expiry. */
    private val resolvedSourceCache = ConcurrentHashMap<String, CachedResolvedSource>()
    val playback = VlcjPlaybackController(
        sourceResolver = PlaybackSourceResolver { item -> resolve(item) },
        onStreamHttpFailure = { item, source, httpStatus ->
            recoverFromStreamHttpFailure(item, source, httpStatus)
        },
    )
    private val queueStore = PlaybackQueueStore(runtime.paths.data.resolve("persistent-queue.json"))
    private val lyricsScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val lyricsPresenter = lazy {
        DesktopLyricsPresenter(playback, configuredLyricsClient(), lyricsScope.value, initiallyActive = false)
    }
    val lyrics: DesktopLyricsPresenter
        get() = lyricsPresenter.value
    private val legacyLibraryFile = runtime.paths.data.resolve("local-library.properties")
    val library = DesktopLibrary(legacyLibraryFile, folderStore = SettingsFolderStore(settings, legacyLibraryFile))
    /** Account-scoped personalization data for the Home surface (synced + recorded locally). */
    val homeStore = DesktopHomeStore(runtime.paths.data.resolve("home-store.json"))
    /** Song→album knowledge for backfilling album links on tracks served without album metadata. */
    val albumIndex = DesktopAlbumIndex(runtime.paths.data.resolve("album-index.json"))
    private val _similarRecommendations = MutableStateFlow<List<DesktopSimilarRecommendation>>(emptyList())
    val similarRecommendations = _similarRecommendations.asStateFlow()
    private var playHistoryCurrent: Pair<SongItem, Long>? = null
    // This is intentionally hardware-key only. Windows SMTC/flyout publishing is not implemented.
    private val windowsMediaControls = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsMediaControls()
    } else {
        null
    }
    private val mediaCommandRouter = windowsMediaControls?.let { PlaybackMediaCommandRouter(playback) }

    init {
        backend.bindSessionProvider(account)
        // Restore the DPAPI session synchronously before the first UI-driven backend call can race
        // ahead of it. Network revalidation stays asynchronous so startup is not blocked on it.
        account.restore()
        runtime.applicationScope.launch {
            account.refreshRestoredSession()
        }
        startPlayHistoryRecorder()
        startHomeSyncObserver()
        val restored = settings.snapshot()
        runtime.applicationScope.launch {
            playback.setVolume(restored.volume)
            playback.setSpeed(restored.speed)
            playback.setRepeatMode(restored.repeatMode.toPlaybackMode())
            playback.setShuffleEnabled(restored.shuffleEnabled)
        }
        runtime.applicationScope.launch {
            playback.state
                .map { state ->
                    PlaybackPreferences(
                        volume = state.volume,
                        speed = state.speed,
                        repeatMode = state.repeatMode,
                        shuffleEnabled = state.shuffleEnabled,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .debounce(500)
                .collect { state ->
                    settings.update {
                        it.copy(
                            volume = state.volume,
                            speed = state.speed,
                            repeatMode = state.repeatMode.toDesktopMode(),
                            shuffleEnabled = state.shuffleEnabled,
                        )
                    }
                }
        }
        runtime.applicationScope.launch {
            playback.state
                .map { state ->
                    PersistedQueueKey(
                        title = state.queueTitle,
                        queue = state.queue,
                        currentIndex = state.currentIndex,
                        positionBucket = state.positionMs / POSITION_BUCKET_MS,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { queueStore.save(playback.state.value) }
        }
        windowsMediaControls?.let { controls ->
            controls.start()
            runtime.applicationScope.launch {
                playback.state.collect(controls::update)
            }
            runtime.applicationScope.launch {
                controls.commands.collect { command -> mediaCommandRouter?.handle(command) }
            }
        }
    }

    private suspend fun restoreQueueIfPresent() {
        val persisted = queueStore.load() ?: return
        if (persisted.items.isEmpty()) return
        playback.replaceQueue(
            items = persisted.items,
            startIndex = persisted.currentIndex.coerceIn(0, persisted.items.lastIndex),
            startPositionMs = persisted.positionMs,
            title = persisted.title,
            playWhenReady = false,
        )
    }

    /** Connects the download service after both platform coordinators have been constructed. */
    fun attachCompletedDownloadLookup(lookup: (String) -> Path?) {
        completedDownloadLookup = lookup
        // Queue restoration must happen after the download service is attached, otherwise a
        // persisted deferred item can start remote resolution before local-first lookup exists.
        runtime.applicationScope.launch { restoreQueueIfPresent() }
    }

    /** Syncs liked songs, library songs, liked albums and subscribed artists into the home store. */
    suspend fun syncHomeLibrary() {
        if (account.state.value !is DesktopAccountState.SignedIn) {
            homeStore.clearAccountData()
            _similarRecommendations.value = emptyList()
            return
        }
        backend.likedSongs().onSuccess { homeStore.replaceLikedSongs(it.songs.distinctBy(SongItem::id)) }
        backend.librarySongs().onSuccess { homeStore.replaceLibrarySongs(it.items.filterIsInstance<SongItem>().distinctBy(SongItem::id)) }
        backend.libraryAlbums().onSuccess { homeStore.replaceLikedAlbums(it.items.filterIsInstance<AlbumItem>().distinctBy(AlbumItem::id)) }
        backend.libraryArtists().onSuccess { homeStore.replaceSubscribedArtists(it.items.filterIsInstance<ArtistItem>().distinctBy(ArtistItem::id)) }
    }

    /**
     * Builds "Similar to ..." shelves from the user's most-played artists and songs, mirroring
     * Android's loadSimilarRecommendations. Artist seeds expand their top sections; song seeds use
     * the related-items endpoint.
     */
    suspend fun loadSimilarRecommendations() {
        val snapshot = homeStore.snapshot.value
        val playTimeBySong =
            snapshot.playHistory.groupBy { it.song.id }.mapValues { (_, events) -> events.sumOf { it.playTimeMs.toLong() } }
        val mostPlayedSongs =
            snapshot.playHistory.map { it.song }.distinctBy { it.id }
                .sortedByDescending { playTimeBySong[it.id] ?: 0L }
                .take(2)
        val recommendations = mutableListOf<DesktopSimilarRecommendation>()

        // Artist seeds, mirroring Android's artistRecommendations: expand the last two sections of
        // each most-played artist's page into a "Similar to ..." shelf.
        val playTimeByArtist = mutableMapOf<String, Pair<Artist, Long>>()
        snapshot.playHistory.forEach { event ->
            event.song.artists.forEach { artist ->
                artist.id?.let { artistId ->
                    val previous = playTimeByArtist[artistId]
                    playTimeByArtist[artistId] = artist to ((previous?.second ?: 0L) + event.playTimeMs)
                }
            }
        }
        val mostPlayedArtists =
            playTimeByArtist.values
                .sortedByDescending { it.second }
                .map { it.first }
                .distinctBy { it.id }
                .take(3)
        for (artist in mostPlayedArtists) {
            val artistId = artist.id ?: continue
            val page = backend.artist(artistId).getOrNull() ?: continue
            val items =
                (page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty() + page.sections.lastOrNull()?.items.orEmpty())
                    .shuffled()
                    .takeIf { it.isNotEmpty() }
                    ?: continue
            recommendations += DesktopSimilarRecommendation(title = page.artist, items = items)
        }

        // Song seeds: fetch related artists/albums/playlists through the related-items endpoint.
        for (song in mostPlayedSongs) {
            val relatedEndpoint = backend.next(song.id).getOrNull()?.relatedEndpoint ?: continue
            val page = backend.related(relatedEndpoint).getOrNull() ?: continue
            val items =
                (page.songs.shuffled().take(8) + page.albums.shuffled().take(4) + page.artists.shuffled().take(4) + page.playlists.shuffled().take(4))
                    .shuffled()
                    .takeIf { it.isNotEmpty() }
                    ?: continue
            recommendations += DesktopSimilarRecommendation(title = song, items = items)
        }

        _similarRecommendations.value = recommendations
    }

    private fun startHomeSyncObserver() {
        runtime.applicationScope.launch {
            account.state
                .map { state -> (state as? DesktopAccountState.SignedIn)?.account }
                .distinctUntilChanged()
                .collectLatest { signedInAccount ->
                if (signedInAccount != null) {
                    syncHomeLibrary()
                    loadSimilarRecommendations()
                } else {
                    homeStore.clearAccountData()
                    _similarRecommendations.value = emptyList()
                }
            }
        }
    }

    private fun startPlayHistoryRecorder() {
        runtime.applicationScope.launch {
            playback.events.collect { event ->
                if (event !is PlaybackEvent.MediaTransition) return@collect
                val now = System.currentTimeMillis()
                flushPlayHistoryCurrent(now)
                playHistoryCurrent =
                    event.current
                        ?.let(::songFromPlaybackItem)
                        ?.takeIf { it.id.isNotBlank() }
                        ?.let { it to now }
            }
        }
    }

    private fun flushPlayHistoryCurrent(now: Long) {
        val current = playHistoryCurrent ?: return
        playHistoryCurrent = null
        val playTimeMs = (now - current.second).coerceIn(0L, MAX_PLAY_HISTORY_EVENT_MS)
        if (playTimeMs < MIN_PLAY_HISTORY_EVENT_MS) return
        homeStore.recordPlay(DesktopPlayEvent(current.first, current.second, playTimeMs))
    }

    private suspend fun resolve(item: PlaybackItem): Result<ResolvedPlaybackSource> = when (val source = item.source) {
        is PlaybackSource.Deferred -> completedDownloadLookup?.invoke(source.key)?.let { path ->
            Result.success(ResolvedPlaybackSource(path.toUri().toASCIIString()))
        } ?: cachedSourceFor(source.key)?.let { Result.success(it) }
            ?: backend.resolveAudioStream(source.key).fold(
                onSuccess = {
                    val resolved = ResolvedPlaybackSource(it.url, it.mimeType, it.requestHeaders, it.expiresAtEpochMillis)
                    cacheSource(
                        key = source.key,
                        source = resolved,
                        streamClientKey = it.clientKey,
                        streamAuthFingerprint = it.authFingerprint,
                    )
                    Result.success(resolved)
                },
                onFailure = { failure ->
                    val classification = classifyStreamResolutionFailure(failure)
                    runtime.logger.warning("stream_resolution event=player_handoff result=${classification.name}")
                    Result.failure(DesktopStreamResolutionException(classification, failure))
                },
            )
        is PlaybackSource.Remote, is PlaybackSource.LocalFile -> DesktopConcretePlaybackSourceResolver.resolve(item)
    }

    private fun cachedSourceFor(key: String): ResolvedPlaybackSource? {
        val cached = resolvedSourceCache[key] ?: return null
        if (cached.sessionFingerprint != account.currentAuthState().fingerprint) {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        val expiresAt = cached.source.expiresAtEpochMs ?: run {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        if (expiresAt <= System.currentTimeMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS) {
            resolvedSourceCache.remove(key, cached)
            return null
        }
        return cached.source
    }

    private suspend fun recoverFromStreamHttpFailure(
        item: PlaybackItem,
        failedSource: ResolvedPlaybackSource,
        httpStatus: Int,
    ): Boolean {
        if (httpStatus !in RETRYABLE_STREAM_RESPONSE_CODES) return false
        val deferred = item.source as? PlaybackSource.Deferred ?: return false
        val key = deferred.key
        val cached = resolvedSourceCache[key]
        val matchingCachedSource = cached?.takeIf { it.source.uri == failedSource.uri }

        // Android drops every cached URL for this media ID before resolving the retry. Desktop has
        // only this auth-scoped in-memory source cache, so remove it unconditionally as well.
        resolvedSourceCache.remove(key)

        val nearExpiry =
            failedSource.expiresAtEpochMs?.let {
                it <= System.currentTimeMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS
            } == true
        val markedFailed =
            if (!nearExpiry && matchingCachedSource?.streamClientKey?.isNotBlank() == true) {
                backend.markStreamClientFailed(
                    videoId = key,
                    clientKey = matchingCachedSource.streamClientKey,
                    httpStatusCode = httpStatus,
                    authFingerprint = matchingCachedSource.streamAuthFingerprint
                        ?: account.currentAuthState().fingerprint,
                )
            } else {
                false
            }

        runtime.logger.info(
            "stream_resolution event=media_http_failure videoId=$key http=$httpStatus " +
                "expired=$nearExpiry clientBackoff=$markedFailed action=retry",
        )
        // Match Android: even an expired/unknown-profile URL gets one fresh resolve; the failed
        // client is only backoff-marked when the concrete profile is known and the URL was current.
        return true
    }

    private fun cacheSource(
        key: String,
        source: ResolvedPlaybackSource,
        streamClientKey: String,
        streamAuthFingerprint: String,
    ) {
        val expiresAt = source.expiresAtEpochMs ?: return
        if (resolvedSourceCache.size >= MAX_RESOLVED_SOURCE_CACHE_ENTRIES) {
            resolvedSourceCache.entries.removeIf { (_, value) ->
                value.source.expiresAtEpochMs?.let { it <= System.currentTimeMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS } != false
            }
        }
        if (resolvedSourceCache.size >= MAX_RESOLVED_SOURCE_CACHE_ENTRIES) resolvedSourceCache.clear()
        resolvedSourceCache[key] = CachedResolvedSource(
            source = source,
            sessionFingerprint = account.currentAuthState().fingerprint,
            streamClientKey = streamClientKey,
            streamAuthFingerprint = streamAuthFingerprint,
        )
    }

    override fun close() {
        val state = playback.state.value
        flushPlayHistoryCurrent(System.currentTimeMillis())
        queueStore.save(state)
        settings.update {
            it.copy(
                volume = state.volume,
                speed = state.speed,
                repeatMode = state.repeatMode.toDesktopMode(),
                shuffleEnabled = state.shuffleEnabled,
            )
        }
        if (lyricsPresenter.isInitialized()) {
            lyricsPresenter.value.close()
            lyricsScope.value.cancel()
        }
        windowsMediaControls?.close()
        albumIndex.close()
        playback.close()
        resolvedSourceCache.clear()
        backend.bindSessionProvider(null)
    }

    private fun configuredLyricsClient(): JvmLyricsClient {
        val defaults = BuiltInLyricsProviders.defaults
        val selected = settings.snapshot().lyricsProviderIds
        if (selected.isEmpty()) return JvmLyricsClient(defaults)
        val byId = defaults.associateBy { it.id }
        val configured = selected.mapNotNull(byId::get)
        return JvmLyricsClient(configured.ifEmpty { defaults })
    }

    private fun songFromPlaybackItem(item: PlaybackItem): SongItem =
        SongItem(
            id = item.metadata.id,
            title = item.metadata.title,
            artists = item.metadata.artists.map { Artist(id = it.id, name = it.name) },
            album = item.metadata.album?.let { Album(id = it.id.orEmpty(), name = it.title) },
            duration = item.metadata.durationHintMs?.div(1_000L)?.toInt(),
            thumbnail = item.metadata.artworkUri.orEmpty(),
            explicit = item.metadata.explicit,
        )

    private companion object {
        const val MAX_RESOLVED_SOURCE_CACHE_ENTRIES = 128
        const val STREAM_CACHE_EXPIRY_LEAD_MILLIS = 60_000L
        const val POSITION_BUCKET_MS = 5_000L
        const val MIN_PLAY_HISTORY_EVENT_MS = 5_000L
        const val MAX_PLAY_HISTORY_EVENT_MS = 1_800_000L
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
    }
}

private data class PlaybackPreferences(
    val volume: Float,
    val speed: Float,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
)

private data class PersistedQueueKey(
    val title: String?,
    val queue: List<PlaybackItem>,
    val currentIndex: Int,
    val positionBucket: Long,
)

private data class CachedResolvedSource(
    val source: ResolvedPlaybackSource,
    val sessionFingerprint: String?,
    val streamClientKey: String,
    val streamAuthFingerprint: String?,
)

private fun moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode.toPlaybackMode(): RepeatMode =
    RepeatMode.valueOf(name)

private fun RepeatMode.toDesktopMode(): moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode =
    moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode.valueOf(name)

private class DesktopStreamResolutionException(
    classification: StreamResolutionFailureKind,
    cause: Throwable,
) : IllegalStateException(classification.userMessage, cause)

private class SettingsFolderStore(
    private val settings: moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore,
    private val legacyFile: Path,
) : DesktopLibraryFolderStore {
    override fun load(): List<Path> {
        val saved = settings.snapshot().libraryFolders
        if (saved.isNotEmpty()) return saved
        val legacy = PropertiesDesktopLibraryFolderStore(legacyFile).load()
        if (legacy.isEmpty()) return emptyList()
        settings.update { it.copy(libraryFolders = legacy) }
        Files.deleteIfExists(legacyFile)
        return legacy
    }

    override fun save(folders: List<Path>) {
        settings.update { it.copy(libraryFolders = folders) }
        Files.deleteIfExists(legacyFile)
    }
}

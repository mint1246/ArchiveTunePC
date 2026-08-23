package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.StreamHttpRecoveryAction
import moe.rukamori.archivetune.innertube.StreamResolutionFailureKind
import moe.rukamori.archivetune.innertube.classifyStreamResolutionFailure
import moe.rukamori.archivetune.innertube.utils.sha1
import moe.rukamori.archivetune.desktopauth.ArchiveTuneSessionVerifier
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.desktopauth.DpapiFileSessionStore
import moe.rukamori.archivetune.desktopauth.ManagedChromiumLoginSource
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.BuiltInLyricsProviders
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPresenter
import moe.rukamori.archivetune.desktop.library.DesktopLibrary
import moe.rukamori.archivetune.desktop.library.DesktopLibraryFolderStore
import moe.rukamori.archivetune.desktop.library.PropertiesDesktopLibraryFolderStore
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.home.DesktopHomeRepository
import moe.rukamori.archivetune.innertube.home.DesktopHomeStore
import moe.rukamori.archivetune.innertube.home.DesktopLibraryRepository
import moe.rukamori.archivetune.innertube.home.DesktopPlayEvent
import moe.rukamori.archivetune.innertube.home.DesktopSimilarRecommendation
import moe.rukamori.archivetune.innertube.home.quickPickSeedIds
import moe.rukamori.archivetune.innertube.search.DesktopSearchRepository
import moe.rukamori.archivetune.spotify.DesktopSpotifyPlaylistImporter
import moe.rukamori.archivetune.spotify.SpotifyPlaylistImportProgress
import moe.rukamori.archivetune.spotify.SpotifyPlaylistImportResult
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.desktop.DesktopConcretePlaybackSourceResolver
import moe.rukamori.archivetune.playback.desktop.DesktopCrossfadeConfiguration
import moe.rukamori.archivetune.playback.desktop.DesktopEqualizerProfile
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import moe.rukamori.archivetune.playback.desktop.PlaybackQueueStore
import moe.rukamori.archivetune.windowsmedia.PlaybackMediaCommandRouter
import moe.rukamori.archivetune.windowsmedia.MediaCommand
import moe.rukamori.archivetune.windowsmedia.WindowsGlobalKeyBinding
import moe.rukamori.archivetune.windowsmedia.WindowsGlobalKeyBindings
import moe.rukamori.archivetune.windowsmedia.WindowsMediaControls
import moe.rukamori.archivetune.windowsmedia.windowsMediaUpdateKey
import java.io.File
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay

/** The small desktop-only seam between ArchiveTune's backend and the native playback engine. */
class DesktopMusicServices(private val runtime: DesktopRuntime) : AutoCloseable {
    private val settings = runtime.settings
    private val queuePersistencePolicy = QueuePersistencePolicy()
    val settingsStore get() = settings
    val backend = ArchiveTuneJvmClient(runtime.paths.cache.resolve("cipher").toFile().also(File::mkdirs))
    private val managedYouTubeBrowser = ManagedChromiumLoginSource(runtime.paths.data.resolve("youtube-browser-profile"))
    /** The backend consults this provider immediately before each request, including downloads. */
    val account = DesktopSessionManager(
        secureStore = DpapiFileSessionStore(runtime.paths.data.resolve("youtube-session.dpapi")),
        verifier = ArchiveTuneSessionVerifier(backend),
        interactiveLoginSource = managedYouTubeBrowser,
        cookieRefresher = managedYouTubeBrowser,
    )
    val contentCoordinator = DesktopResourceCoordinator(
        cacheDirectory = runtime.paths.cache.resolve("content"),
        maxMemoryEntries = 32,
        maxBackgroundRequests = 2,
        diagnostic = runtime.logger::info,
    )
    val libraryRepository = DesktopLibraryRepository(
        backend = backend,
        coordinator = contentCoordinator,
        scope = runtime.applicationScope,
        accountScopeProvider = ::accountCacheScope,
    )
    val homeRepository = DesktopHomeRepository(
        backend = backend,
        coordinator = contentCoordinator,
        libraryRepository = libraryRepository,
        scope = runtime.applicationScope,
        accountScopeProvider = ::accountCacheScope,
    )
    val searchRepository = DesktopSearchRepository(
        searchSummary = backend::searchSummary,
        coordinator = contentCoordinator,
        scope = runtime.applicationScope,
        accountScopeProvider = ::accountCacheScope,
    )
    internal val browseRepository = DesktopBrowseRepository(
        backend = backend,
        coordinator = contentCoordinator,
        accountScopeProvider = ::accountCacheScope,
    )
    internal val exploreRepository = DesktopExploreRepository(
        backend = backend,
        coordinator = contentCoordinator,
        scope = runtime.applicationScope,
        accountScopeProvider = ::accountCacheScope,
    )
    @Volatile private var completedDownloadLookup: ((String) -> Path?)? = null
    /** Signed sources live only in memory and are discarded before their server expiry. */
    private val resolvedSourceCache = ConcurrentHashMap<String, CachedResolvedSource>()
    private val relatedSongFetches = ConcurrentHashMap.newKeySet<String>()
    val playback = VlcjPlaybackController(
        sourceResolver = PlaybackSourceResolver { item -> resolve(item) },
        onStreamHttpFailure = { item, source, httpStatus ->
            recoverFromStreamHttpFailure(item, source, httpStatus)
        },
        initialAudioOutputDeviceId = settings.snapshot().audioOutputDeviceId,
    )
    private val nextTrackPrefetcher = NextTrackPrefetcher(runtime.applicationScope) { item, cacheAllowed ->
        resolve(item, cacheResolvedRemote = cacheAllowed, reportFailure = false)
    }
    private val queueStore = PlaybackQueueStore(runtime.paths.data.resolve("persistent-queue.json"))
    private val lyricsScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val lyricsPresenter = lazy {
        DesktopLyricsPresenter(playback, configuredLyricsClient(), lyricsScope.value, initiallyActive = false)
    }
    val lyrics: DesktopLyricsPresenter
        get() = lyricsPresenter.value
    private val legacyLibraryFile = runtime.paths.data.resolve("local-library.properties")
    private val localLibraryIndexFile = runtime.paths.data.resolve("local-library-index.properties")
    val library = DesktopLibrary(localLibraryIndexFile, folderStore = SettingsFolderStore(settings, legacyLibraryFile))
    private val spotifyPlaylistImporter =
        DesktopSpotifyPlaylistImporter(
            backend = backend,
            diagnostic = runtime.logger::info,
        )
    /** Account-scoped personalization data for the Home surface (synced + recorded locally). */
    val homeStore = DesktopHomeStore(
        runtime.paths.data.resolve("home-store.json"),
        asyncPersistence = true,
    )
    /** Song→album knowledge for backfilling album links on tracks served without album metadata. */
    val albumIndex = DesktopAlbumIndex(runtime.paths.data.resolve("album-index.json"))
    private val _similarRecommendations = MutableStateFlow<List<DesktopSimilarRecommendation>>(emptyList())
    val similarRecommendations = _similarRecommendations.asStateFlow()
    private var playHistoryCurrent: Pair<SongItem, Long>? = null

    suspend fun importSpotifyPlaylist(
        link: String,
        onProgress: (SpotifyPlaylistImportProgress) -> Unit = {},
    ): Result<SpotifyPlaylistImportResult> {
        if (account.state.value !is DesktopAccountState.SignedIn) {
            return Result.failure(IllegalStateException("Sign in to YouTube Music before importing a Spotify playlist"))
        }
        return spotifyPlaylistImporter.import(link, onProgress)
    }
    private val windowsMediaControls = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsMediaControls()
    } else {
        null
    }
    private val keybindCommands = MutableSharedFlow<DesktopKeybindAction>(extraBufferCapacity = 64)
    private val _appKeybindActions = MutableSharedFlow<DesktopKeybindAction>(extraBufferCapacity = 16)
    val appKeybindActions = _appKeybindActions.asSharedFlow()
    private val _keybindPressedKeys = MutableStateFlow<List<Int>>(emptyList())
    val keybindPressedKeys = _keybindPressedKeys.asStateFlow()
    private var lastAudibleVolume = settings.snapshot().volume.takeIf { it > 0.001f } ?: 1f
    @Volatile private var keybindCaptureActive = false
    private val globalKeyBindings = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsGlobalKeyBindings(
            onAction = { actionId ->
                DesktopKeybindAction.entries.firstOrNull { it.name == actionId }?.let(keybindCommands::tryEmit)
            },
            onPressedKeysChanged = { keys -> _keybindPressedKeys.value = keys.sorted() },
        )
    } else {
        null
    }
    @Volatile private var partyPlaybackPolicyProvider: () -> DesktopPartyPlaybackPolicy = { DesktopPartyPlaybackPolicy() }
    @Volatile private var partyPlayPauseHandler: suspend () -> Boolean = { false }
    @Volatile private var partySkipNextHandler: suspend () -> Boolean = { false }
    @Volatile private var partySkipPreviousHandler: suspend () -> Boolean = { false }
    @Volatile private var partySeekHandler: suspend (Long) -> Boolean = { false }
    private val mediaCommandRouter = windowsMediaControls?.let {
        PlaybackMediaCommandRouter(playback, commandAllowed = { partyPlaybackPolicy().localTransportAllowed })
    }

    internal fun attachPartyPlaybackPolicyProvider(provider: () -> DesktopPartyPlaybackPolicy) {
        partyPlaybackPolicyProvider = provider
    }

    internal fun attachPartyPlayPauseHandler(handler: suspend () -> Boolean) {
        partyPlayPauseHandler = handler
    }

    internal fun attachPartySkipHandlers(
        next: suspend () -> Boolean,
        previous: suspend () -> Boolean,
    ) {
        partySkipNextHandler = next
        partySkipPreviousHandler = previous
    }

    internal fun attachPartySeekHandler(handler: suspend (Long) -> Boolean) {
        partySeekHandler = handler
    }

    fun attachWindowsMediaWindow(window: Window) {
        windowsMediaControls?.attachWindow(window)
    }

    private fun partyPlaybackPolicy(): DesktopPartyPlaybackPolicy =
        runCatching(partyPlaybackPolicyProvider).getOrDefault(DesktopPartyPlaybackPolicy())

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
        runtime.applicationScope.launch {
            playback.state.collect(nextTrackPrefetcher::onSnapshot)
        }
        if (library.folders.isNotEmpty()) {
            runtime.applicationScope.launch(Dispatchers.IO) {
                contentCoordinator.singleFlight("local-library-scan", DesktopRequestPriority.MAINTENANCE) {
                    runCatching { library.rescan() }
                }
            }
        }
        val restored = settings.snapshot()
        runtime.applicationScope.launch {
            playback.setVolume(restored.volume)
            playback.setSpeed(restored.speed)
            playback.setRepeatMode(restored.repeatMode.toPlaybackMode())
            playback.setShuffleEnabled(restored.shuffleEnabled)
            playback.audioEffects.applyEqualizer(restored.equalizer.toPlaybackProfile())
            playback.audioEffects.setLoudnessNormalizationEnabled(restored.loudnessNormalizationEnabled)
            playback.setCrossfadeConfiguration(
                DesktopCrossfadeConfiguration(
                    enabled = restored.crossfadeEnabled,
                    durationMs = (restored.crossfadeDurationSeconds * 1_000f).toLong(),
                    preserveGaplessAlbumTransitions = restored.crossfadeGaplessAlbumTransitions,
                ),
            )
        }
        runtime.applicationScope.launch {
            playback.audioOutputState
                .filter { it.resolved }
                .map { it.selectedDeviceId }
                .distinctUntilChanged()
                .collect { selectedDeviceId ->
                    if (settings.snapshot().audioOutputDeviceId != selectedDeviceId) {
                        settings.update { it.copy(audioOutputDeviceId = selectedDeviceId) }
                    }
                }
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
                    val policy = partyPlaybackPolicy()
                    settings.update {
                        it.copy(
                            volume = state.volume,
                            speed = if (policy.persistSpeedAndRepeat) state.speed else it.speed,
                            repeatMode = if (policy.persistSpeedAndRepeat) state.repeatMode.toDesktopMode() else it.repeatMode,
                            shuffleEnabled = if (policy.persistShuffle) state.shuffleEnabled else it.shuffleEnabled,
                        )
                    }
                }
        }
        runtime.applicationScope.launch {
            playback.state.collect { snapshot ->
                if (queuePersistencePolicy.shouldPersist(snapshot)) {
                    withContext(Dispatchers.IO) { queueStore.save(snapshot) }
                }
            }
        }
        windowsMediaControls?.let { controls ->
            controls.start()
            runtime.applicationScope.launch {
                playback.state
                    .distinctUntilChangedBy { it.windowsMediaUpdateKey() }
                    .collect(controls::update)
            }
            runtime.applicationScope.launch {
                controls.commands.collect { command ->
                    val policy = partyPlaybackPolicy()
                    if (policy.localTransportAllowed) {
                        mediaCommandRouter?.handle(command)
                    } else {
                        val snapshot = playback.state.value
                        when (command) {
                            MediaCommand.PLAY -> if (!snapshot.isPlaying) partyPlayPauseHandler()
                            MediaCommand.PAUSE -> if (snapshot.isPlaying) partyPlayPauseHandler()
                            MediaCommand.PLAY_PAUSE -> partyPlayPauseHandler()
                            MediaCommand.NEXT -> partySkipNextHandler()
                            MediaCommand.PREVIOUS -> partySkipPreviousHandler()
                            MediaCommand.SEEK_FORWARD -> partySeekHandler(snapshot.positionMs + 10_000L)
                            MediaCommand.SEEK_BACKWARD -> partySeekHandler((snapshot.positionMs - 10_000L).coerceAtLeast(0L))
                        }
                    }
                }
            }
        }
        runtime.applicationScope.launch {
            keybindCommands.collect(::handleKeybindAction)
        }
        refreshKeybindings()
    }

    fun refreshKeybindings() {
        runCatching {
            globalKeyBindings?.updateBindings(
                settings.snapshot().keybinds.map { binding ->
                    WindowsGlobalKeyBinding(
                        actionId = binding.action.name,
                        virtualKeys = binding.virtualKeys.toSet(),
                        repeatWhileHeld = binding.repeatWhileHeld,
                    )
                },
            )
        }.onFailure { failure ->
            runtime.logger.warning("Could not update global keybinds: ${failure.message}")
        }
    }

    fun setKeybindCaptureActive(active: Boolean) {
        if (keybindCaptureActive == active) return
        keybindCaptureActive = active
        globalKeyBindings?.setCaptureActive(active)
        if (!active) _keybindPressedKeys.value = emptyList()
    }

    private suspend fun handleKeybindAction(action: DesktopKeybindAction) {
        val policy = partyPlaybackPolicy()
        val snapshot = playback.state.value
        when (action) {
            DesktopKeybindAction.PLAY_PAUSE -> if (policy.localTransportAllowed) {
                if (snapshot.isPlaying) playback.pause() else playback.play()
            } else {
                partyPlayPauseHandler()
            }
            DesktopKeybindAction.NEXT -> if (policy.localTransportAllowed) playback.skipNext() else partySkipNextHandler()
            DesktopKeybindAction.PREVIOUS -> if (policy.localTransportAllowed) playback.skipPrevious() else partySkipPreviousHandler()
            DesktopKeybindAction.VOLUME_UP -> {
                val volume = (snapshot.volume + KEYBIND_VOLUME_STEP).coerceAtMost(1f)
                if (volume > 0.001f) lastAudibleVolume = volume
                playback.setVolume(volume)
            }
            DesktopKeybindAction.VOLUME_DOWN -> {
                val volume = (snapshot.volume - KEYBIND_VOLUME_STEP).coerceAtLeast(0f)
                if (volume > 0.001f) lastAudibleVolume = volume
                playback.setVolume(volume)
            }
            DesktopKeybindAction.MUTE_TOGGLE -> {
                if (snapshot.volume > 0.001f) {
                    lastAudibleVolume = snapshot.volume
                    playback.setVolume(0f)
                } else {
                    playback.setVolume(lastAudibleVolume.coerceIn(KEYBIND_VOLUME_STEP, 1f))
                }
            }
            DesktopKeybindAction.SEEK_FORWARD -> if (policy.localTransportAllowed) {
                playback.seekTo(snapshot.positionMs + KEYBIND_SEEK_STEP_MS)
            } else {
                partySeekHandler(snapshot.positionMs + KEYBIND_SEEK_STEP_MS)
            }
            DesktopKeybindAction.SEEK_BACKWARD -> if (policy.localTransportAllowed) {
                playback.seekTo((snapshot.positionMs - KEYBIND_SEEK_STEP_MS).coerceAtLeast(0L))
            } else {
                partySeekHandler((snapshot.positionMs - KEYBIND_SEEK_STEP_MS).coerceAtLeast(0L))
            }
            DesktopKeybindAction.SPEED_UP -> if (policy.persistSpeedAndRepeat) {
                playback.setSpeed((snapshot.speed + KEYBIND_SPEED_STEP).coerceAtMost(4f))
            }
            DesktopKeybindAction.SPEED_DOWN -> if (policy.persistSpeedAndRepeat) {
                playback.setSpeed((snapshot.speed - KEYBIND_SPEED_STEP).coerceAtLeast(0.25f))
            }
            DesktopKeybindAction.SPEED_RESET -> if (policy.persistSpeedAndRepeat) playback.setSpeed(1f)
            DesktopKeybindAction.SHUFFLE_TOGGLE -> if (policy.persistShuffle) {
                playback.setShuffleEnabled(!snapshot.shuffleEnabled)
            }
            DesktopKeybindAction.REPEAT_CYCLE -> if (policy.persistSpeedAndRepeat) {
                playback.setRepeatMode(
                    when (snapshot.repeatMode) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    },
                )
            }
            DesktopKeybindAction.TOGGLE_LYRICS,
            DesktopKeybindAction.TOGGLE_QUEUE,
            -> _appKeybindActions.emit(action)
        }
    }

    private suspend fun restoreQueueIfPresent() {
        val persisted = queueStore.load() ?: return
        if (persisted.items.isEmpty()) return
        val resumeAfterUpdate = Files.deleteIfExists(runtime.paths.data.resolve(RESUME_AFTER_UPDATE_MARKER))
        playback.replaceQueue(
            items = persisted.items,
            startIndex = persisted.currentIndex.coerceIn(0, persisted.items.lastIndex),
            startPositionMs = persisted.positionMs,
            title = persisted.title,
            playWhenReady = resumeAfterUpdate,
        )
    }

    suspend fun prepareForUpdateRestart() = withContext(Dispatchers.IO) {
        queueStore.save(playback.state.value)
        Files.writeString(runtime.paths.data.resolve(RESUME_AFTER_UPDATE_MARKER), "resume")
    }

    /** Connects the download service after both platform coordinators have been constructed. */
    fun attachCompletedDownloadLookup(lookup: (String) -> Path?) {
        completedDownloadLookup = lookup
        // Queue restoration must happen after the download service is attached, otherwise a
        // persisted deferred item can start remote resolution before local-first lookup exists.
        runtime.applicationScope.launch(Dispatchers.IO) { restoreQueueIfPresent() }
    }

    suspend fun upNext(videoId: String) =
        contentCoordinator.singleFlight(
            key = "up-next:${accountCacheScope()}:$videoId",
            priority = DesktopRequestPriority.FOREGROUND,
        ) { backend.upNext(videoId) }

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
        val accountScope = accountCacheScope()
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
            val page =
                contentCoordinator.singleFlight(
                    "recommendation-artist:$accountScope:$artistId",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.artist(artistId) }.getOrNull() ?: continue
            val items =
                (page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty() + page.sections.lastOrNull()?.items.orEmpty())
                    .shuffled()
                    .takeIf { it.isNotEmpty() }
                    ?: continue
            recommendations += DesktopSimilarRecommendation(title = page.artist, items = items)
        }

        // Song seeds: fetch related artists/albums/playlists through the related-items endpoint.
        for (song in mostPlayedSongs) {
            val relatedEndpoint =
                contentCoordinator.singleFlight(
                    "related-next:$accountScope:${song.id}",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.next(song.id) }.getOrNull()?.relatedEndpoint ?: continue
            val page =
                contentCoordinator.singleFlight(
                    "related-page:$accountScope:${song.id}",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.related(relatedEndpoint) }.getOrNull() ?: continue
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
                // Session-cookie/PoToken refreshes must not replay the expensive Home maintenance
                // pipeline. Only an actual account identity change should invalidate these stores.
                .map { state ->
                    (state as? DesktopAccountState.SignedIn)?.account?.cacheScope()
                }
                .distinctUntilChanged()
                .collectLatest { signedInScope ->
                    libraryRepository.onAccountScopeChanged()
                    homeRepository.onAccountScopeChanged()
                    searchRepository.onAccountScopeChanged()
                    browseRepository.clearAccountCache()
                    exploreRepository.onAccountScopeChanged()
                    if (signedInScope != null) {
                        // Tie all startup children to collectLatest so a real account switch
                        // cancels stale work instead of leaving old-session maintenance running.
                        coroutineScope {
                            launch(Dispatchers.IO) { homeRepository.activate() }
                            launch(Dispatchers.IO) { libraryRepository.activate() }
                            launch(Dispatchers.IO) {
                                delay(BACKGROUND_MAINTENANCE_START_DELAY_MS)
                                contentCoordinator.singleFlight(
                                    "home-library-sync:$signedInScope",
                                    DesktopRequestPriority.MAINTENANCE,
                                ) {
                                    runCatching {
                                        syncHomeLibrary()
                                        Unit
                                    }
                                }
                            }
                            launch(Dispatchers.IO) {
                                delay(BACKGROUND_MAINTENANCE_START_DELAY_MS)
                                runCatching {
                                    warmQuickPickRelatedCache()
                                    loadSimilarRecommendations()
                                }
                            }
                        }
                    } else {
                        homeStore.clearAccountData()
                        _similarRecommendations.value = emptyList()
                    }
            }
        }
    }

    private fun accountCacheScope(): String =
        when (val state = account.state.value) {
            is DesktopAccountState.SignedIn -> state.account.cacheScope()
            is DesktopAccountState.Expired -> state.account?.cacheScope() ?: "signed-out"
            else -> "signed-out"
        }

    internal fun currentBrowseAccountScope(): String = accountCacheScope()

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
                        ?.also { song ->
                            runtime.applicationScope.launch { cacheRelatedSongsIfNeeded(song.id) }
                        }
                        ?.let { it to now }
            }
        }
    }

    /** Mirrors Android MusicService: cache related songs the first time a played seed is seen. */
    private suspend fun cacheRelatedSongsIfNeeded(videoId: String) {
        if (homeStore.hasRelatedSongs(videoId)) return
        if (!relatedSongFetches.add(videoId)) return
        try {
            val accountScope = accountCacheScope()
            val relatedEndpoint =
                contentCoordinator.singleFlight(
                    "related-next:$accountScope:$videoId",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.next(videoId) }.getOrNull()?.relatedEndpoint ?: return
            val relatedSongs =
                contentCoordinator.singleFlight(
                    "related-page:$accountScope:$videoId",
                    DesktopRequestPriority.MAINTENANCE,
                ) { backend.related(relatedEndpoint) }.getOrNull()?.songs.orEmpty()
            if (relatedSongs.isNotEmpty()) {
                homeStore.replaceRelatedSongs(videoId, relatedSongs)
            }
        } finally {
            relatedSongFetches.remove(videoId)
        }
    }

    /** Backfills Android-style related rows for desktop listens recorded before this cache existed. */
    private suspend fun warmQuickPickRelatedCache() {
        for (videoId in quickPickSeedIds(homeStore.snapshot.value)) {
            cacheRelatedSongsIfNeeded(videoId)
        }
    }

    private fun flushPlayHistoryCurrent(now: Long) {
        val current = playHistoryCurrent ?: return
        playHistoryCurrent = null
        val playTimeMs = (now - current.second).coerceIn(0L, MAX_PLAY_HISTORY_EVENT_MS)
        if (playTimeMs < MIN_PLAY_HISTORY_EVENT_MS) return
        homeStore.recordPlay(DesktopPlayEvent(current.first, current.second, playTimeMs))
    }

    private suspend fun resolve(
        item: PlaybackItem,
        cacheResolvedRemote: () -> Boolean = { true },
        reportFailure: Boolean = true,
    ): Result<ResolvedPlaybackSource> = when (val source = item.source) {
        is PlaybackSource.Deferred -> resolveDeferredPreferCompletedDownload(
            item = item,
            completedDownloadLookup = completedDownloadLookup,
        ) {
            cachedSourceFor(source.key)?.let { Result.success(it) }
                ?: backend.resolveAudioStreamWithMetadataFallback(
                    videoId = source.key,
                    title = item.metadata.title,
                    artistNames = item.metadata.artists.map { it.name },
                    albumTitle = item.metadata.album?.title,
                    durationHintMs = item.metadata.durationHintMs,
                )
                    .fold(
                        onSuccess = {
                            runtime.logger.info(
                                "stream_resolution event=resolved videoId=${source.key} resolvedVideoId=${it.videoId} client=${it.clientKey} " +
                                    "itag=${it.format.itag} mime=${it.mimeType.substringBefore(';')} bitrate=${it.bitrate} " +
                                    "hasPot=${it.url.hasQueryParameter("pot")} hasN=${it.url.hasQueryParameter("n")}",
                            )
                            val resolved = ResolvedPlaybackSource(
                                uri = it.url,
                                mimeType = it.mimeType,
                                headers = it.requestHeaders,
                                expiresAtEpochMs = it.expiresAtEpochMillis,
                                loudnessDb = it.audioConfig?.loudnessDb,
                                perceptualLoudnessDb = it.audioConfig?.perceptualLoudnessDb,
                            )
                            if (cacheResolvedRemote()) {
                                cacheSource(
                                    key = source.key,
                                    source = resolved,
                                    resolvedVideoId = it.videoId,
                                    streamClientKey = it.clientKey,
                                    streamAuthFingerprint = it.authFingerprint,
                                )
                            }
                            Result.success(resolved)
                        },
                        onFailure = { failure ->
                            val classification = classifyStreamResolutionFailure(failure)
                            if (reportFailure) {
                                runtime.logger.warning("stream_resolution event=player_handoff result=${classification.name}")
                            }
                            Result.failure(DesktopStreamResolutionException(classification, failure))
                        },
                    )
        }
        is PlaybackSource.Remote, is PlaybackSource.LocalFile -> DesktopConcretePlaybackSourceResolver.resolve(item)
    }

    private fun String.hasQueryParameter(name: String): Boolean =
        contains("?$name=") || contains("&$name=")

    private fun cachedSourceFor(key: String): ResolvedPlaybackSource? {
        val cached = resolvedSourceCache[key] ?: return null
        if (cached.sessionFingerprint != account.currentAuthState().streamCacheSessionFingerprint()) {
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
        val resolvedVideoId = matchingCachedSource?.resolvedVideoId ?: key

        // Android drops every cached URL for this media ID before resolving the retry. Desktop has
        // only this auth-scoped in-memory source cache, so remove it unconditionally as well.
        resolvedSourceCache.remove(key)

        val nearExpiry =
            failedSource.expiresAtEpochMs?.let {
                it <= System.currentTimeMillis() + STREAM_CACHE_EXPIRY_LEAD_MILLIS
            } == true
        val recoveryAction =
            if (!nearExpiry && matchingCachedSource?.streamClientKey?.isNotBlank() == true) {
                backend.prepareStreamHttpRecovery(
                    videoId = resolvedVideoId,
                    clientKey = matchingCachedSource.streamClientKey,
                    httpStatusCode = httpStatus,
                    authFingerprint = matchingCachedSource.streamAuthFingerprint
                        ?: account.currentAuthState().fingerprint,
                    failedUrlHadPoToken = failedSource.uri.hasQueryParameter("pot"),
                )
            } else {
                StreamHttpRecoveryAction.RETRY_ONLY
            }

        runtime.logger.info(
            "stream_resolution event=media_http_failure videoId=$key http=$httpStatus " +
                "expired=$nearExpiry recovery=${recoveryAction.name} action=retry",
        )
        // Match Android: even an expired/unknown-profile URL gets one fresh resolve; the failed
        // client is only backoff-marked when the concrete profile is known and the URL was current.
        return true
    }

    private fun cacheSource(
        key: String,
        source: ResolvedPlaybackSource,
        resolvedVideoId: String,
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
            // Per-video PoTokens rotate every time another song is prepared. They must not evict a
            // still-valid signed source for the previous song; only stable account/session context
            // determines whether a cached URL belongs to the current login.
            sessionFingerprint = account.currentAuthState().streamCacheSessionFingerprint(),
            resolvedVideoId = resolvedVideoId,
            streamClientKey = streamClientKey,
            streamAuthFingerprint = streamAuthFingerprint,
        )
    }

    override fun close() {
        val state = playback.state.value
        flushPlayHistoryCurrent(System.currentTimeMillis())
        if (queuePersistencePolicy.shouldPersist(state, force = true)) queueStore.save(state)
        val policy = partyPlaybackPolicy()
        settings.update {
            it.copy(
                volume = state.volume,
                speed = if (policy.persistSpeedAndRepeat) state.speed else it.speed,
                repeatMode = if (policy.persistSpeedAndRepeat) state.repeatMode.toDesktopMode() else it.repeatMode,
                shuffleEnabled = if (policy.persistShuffle) state.shuffleEnabled else it.shuffleEnabled,
            )
        }
        if (lyricsPresenter.isInitialized()) {
            lyricsPresenter.value.close()
            lyricsScope.value.cancel()
        }
        nextTrackPrefetcher.close()
        globalKeyBindings?.close()
        windowsMediaControls?.close()
        homeStore.close()
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
        const val MIN_PLAY_HISTORY_EVENT_MS = 5_000L
        const val MAX_PLAY_HISTORY_EVENT_MS = 1_800_000L
        const val BACKGROUND_MAINTENANCE_START_DELAY_MS = 1_500L
        const val KEYBIND_VOLUME_STEP = 0.02f
        const val KEYBIND_SPEED_STEP = 0.05f
        const val KEYBIND_SEEK_STEP_MS = 5_000L
        const val RESUME_AFTER_UPDATE_MARKER = "resume-after-update"
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
    }
}

internal data class DesktopPartyPlaybackPolicy(
    val localTransportAllowed: Boolean = true,
    val persistSpeedAndRepeat: Boolean = true,
    val persistShuffle: Boolean = true,
)

/**
 * Resolves a completed download through the normal LocalFile path before touching any signed-stream
 * fallback. The lookup already filters stale journal entries, but validation here also covers a file
 * disappearing between journal lookup and playback without turning a recoverable cache miss into a
 * player error.
 */
internal suspend fun resolveDeferredPreferCompletedDownload(
    item: PlaybackItem,
    completedDownloadLookup: ((String) -> Path?)?,
    fallback: suspend () -> Result<ResolvedPlaybackSource>,
): Result<ResolvedPlaybackSource> {
    val deferred = item.source as? PlaybackSource.Deferred ?: return fallback()
    val completedPath = runCatching { completedDownloadLookup?.invoke(deferred.key) }.getOrNull()
    if (completedPath != null) {
        val localResult = DesktopConcretePlaybackSourceResolver.resolve(
            item.copy(source = PlaybackSource.LocalFile(completedPath.toString())),
        )
        if (localResult.isSuccess) return localResult
    }
    return fallback()
}

private fun moe.rukamori.archivetune.desktopauth.DesktopAccount.cacheScope(): String =
    "account-${sha1(listOf(email.orEmpty(), channelHandle.orEmpty(), name).joinToString(separator = "\u0000"))}"

private data class PlaybackPreferences(
    val volume: Float,
    val speed: Float,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
)

internal data class QueuePersistenceKey(
    val title: String?,
    val queue: List<PlaybackItem>,
    val currentIndex: Int,
)

internal class QueuePersistencePolicy(
    private val checkpointMs: Long = 25_000L,
    private val seekJumpMs: Long = 5_000L,
) {
    private data class Observation(
        val key: QueuePersistenceKey,
        val playWhenReady: Boolean,
        val stopped: Boolean,
        val positionMs: Long,
        val positionBucket: Long,
    )

    private var previous: Observation? = null

    init {
        require(checkpointMs > 0L) { "Queue checkpoint interval must be positive" }
        require(seekJumpMs > 0L) { "Queue seek threshold must be positive" }
    }

    fun shouldPersist(snapshot: PlaybackSnapshot, force: Boolean = false): Boolean {
        val position = snapshot.positionMs.coerceAtLeast(0L)
        val current = Observation(
            key = QueuePersistenceKey(snapshot.queueTitle, snapshot.queue, snapshot.currentIndex),
            playWhenReady = snapshot.playWhenReady,
            stopped = snapshot.status == PlaybackStatus.IDLE || snapshot.status == PlaybackStatus.ENDED,
            positionMs = position,
            positionBucket = position / checkpointMs,
        )
        val last = previous
        previous = current
        if (force) return true
        if (last == null) return false
        if (current.key != last.key) return true
        if (current.playWhenReady != last.playWhenReady) return true
        if (!last.stopped && current.stopped) return true
        if (current.key.queue.isEmpty() || current.key.currentIndex !in current.key.queue.indices) return false
        if (kotlin.math.abs(current.positionMs - last.positionMs) >= seekJumpMs) return true
        return current.positionBucket != last.positionBucket
    }
}

private data class CachedResolvedSource(
    val source: ResolvedPlaybackSource,
    val sessionFingerprint: String?,
    val resolvedVideoId: String,
    val streamClientKey: String,
    val streamAuthFingerprint: String?,
)

private fun PlaybackAuthState.streamCacheSessionFingerprint(): String =
    sha1(
        listOf(
            cookie.orEmpty(),
            visitorData.orEmpty(),
            dataSyncId.orEmpty(),
        ).joinToString(separator = "\u0000"),
    )

private fun moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode.toPlaybackMode(): RepeatMode =
    RepeatMode.valueOf(name)

private fun RepeatMode.toDesktopMode(): moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode =
    moe.rukamori.archivetune.desktop.settings.DesktopRepeatMode.valueOf(name)

private fun moe.rukamori.archivetune.desktop.settings.DesktopEqualizerSettings.toPlaybackProfile(): DesktopEqualizerProfile =
    DesktopEqualizerProfile(
        enabled = enabled,
        preampDb = preampDb,
        bandGainsDb = bandGainsDb,
    )

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

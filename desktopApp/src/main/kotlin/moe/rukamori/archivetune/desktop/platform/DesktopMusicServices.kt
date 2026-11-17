package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.utils.sha1
import moe.rukamori.archivetune.desktopauth.ArchiveTuneSessionVerifier
import moe.rukamori.archivetune.desktopauth.DesktopSessionManager
import moe.rukamori.archivetune.desktopauth.secureSessionStore
import moe.rukamori.archivetune.desktopauth.ManagedChromiumLoginSource
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.BuiltInLyricsProviders
import moe.rukamori.archivetune.lyrics.JvmLyricsTranslationCache
import moe.rukamori.archivetune.desktop.lyrics.DesktopLyricsPresenter
import moe.rukamori.archivetune.desktop.library.DesktopLibrary
import moe.rukamori.archivetune.desktop.library.DesktopLibraryFolderStore
import moe.rukamori.archivetune.desktop.library.PropertiesDesktopLibraryFolderStore
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.sdk.settings.DesktopSettingsPolicy
import moe.rukamori.archivetune.desktop.statistics.DesktopListenTracker
import moe.rukamori.archivetune.desktop.statistics.DesktopStatisticsStore
import moe.rukamori.archivetune.innertube.cache.DesktopRequestPriority
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.home.DesktopHomeRepository
import moe.rukamori.archivetune.innertube.home.DesktopHomeStore
import moe.rukamori.archivetune.innertube.home.DesktopLibraryRepository
import moe.rukamori.archivetune.innertube.home.DesktopPersonalizationCoordinator
import moe.rukamori.archivetune.innertube.home.DesktopSimilarRecommendation
import moe.rukamori.archivetune.innertube.search.DesktopSearchRepository
import moe.rukamori.archivetune.spotify.DesktopSpotifyPlaylistImporter
import moe.rukamori.archivetune.spotify.SpotifyPlaylistImportProgress
import moe.rukamori.archivetune.spotify.SpotifyPlaylistImportResult
import moe.rukamori.archivetune.playback.api.NextTrackPrefetcher
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.session.QueuePersistencePolicy
import moe.rukamori.archivetune.playback.desktop.DesktopCrossfadeConfiguration
import moe.rukamori.archivetune.playback.desktop.DesktopEqualizerProfile
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import moe.rukamori.archivetune.playback.desktop.PlaybackQueueStore
import moe.rukamori.archivetune.playback.desktop.stream.DesktopStreamDiagnostics
import moe.rukamori.archivetune.playback.desktop.stream.DesktopStreamSourceResolver
import moe.rukamori.archivetune.windowsmedia.PlaybackMediaCommandRouter
import moe.rukamori.archivetune.windowsmedia.MediaCommand
import moe.rukamori.archivetune.windowsmedia.WindowsGlobalKeyBinding
import moe.rukamori.archivetune.windowsmedia.WindowsGlobalKeyBindings
import moe.rukamori.archivetune.windowsmedia.WindowsMediaControls
import moe.rukamori.archivetune.windowsmedia.windowsMediaUpdateKey
import moe.rukamori.archivetune.linuxmedia.LinuxGlobalKeyBinding
import moe.rukamori.archivetune.linuxmedia.LinuxGlobalKeyBindings
import moe.rukamori.archivetune.linuxmedia.LinuxMediaControls
import java.io.File
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
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
import kotlinx.coroutines.flow.StateFlow
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
        secureStore = secureSessionStore(runtime.paths.data.resolve("youtube-session.dpapi")),
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
    internal val podcastRepository = DesktopPodcastRepository(
        backend = backend,
        coordinator = contentCoordinator,
        scope = runtime.applicationScope,
        accountScopeProvider = ::accountCacheScope,
    )
    internal val playlistSuggestions = DesktopPlaylistSuggestionsRepository(
        backend, contentCoordinator, runtime.applicationScope, ::accountCacheScope,
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
    private val streamSourceResolver = DesktopStreamSourceResolver(
        backend = backend,
        sessionProvider = account,
        diagnostics = object : DesktopStreamDiagnostics {
            override fun info(message: String) = runtime.logger.info(message)

            override fun warning(message: String) = runtime.logger.warning(message)
        },
    )
    val playback = VlcjPlaybackController(
        mediaHttpClient = moe.rukamori.archivetune.innertube.desktopStreamHttpClient(),
        sourceResolver = streamSourceResolver,
        onStreamHttpFailure = streamSourceResolver::recoverFromStreamHttpFailure,
        initialAudioOutputDeviceId = settings.snapshot().audioOutputDeviceId,
        initialPitchSemitones = settings.snapshot().pitchSemitones,
    )
    val stems = DesktopStemCoordinator(
        playback = VlcjStemPlaybackBridge(playback),
        applicationScope = runtime.applicationScope,
        modelsDirectory = DesktopStemStorage.modelsDirectory(runtime.paths),
        stemsDirectory = DesktopStemStorage.stemsDirectory(runtime.paths),
        modelManifestUrl = DesktopBuildInfo.MODEL_MANIFEST_URL,
        diagnostics = { level, message, error -> runtime.logger.log(level, message, error) },
    )
    val canvas = DesktopCanvasRepository(settings, runtime.paths.cache.resolve("canvas"), runtime.applicationScope)
    private val nextTrackPrefetcher = NextTrackPrefetcher(runtime.applicationScope) { item, cacheAllowed ->
        streamSourceResolver.resolve(item, cacheResolvedRemote = cacheAllowed, reportFailure = false)
    }
    private val queueStore = PlaybackQueueStore(runtime.paths.data.resolve("persistent-queue.json"))
    private val lyricsScope = lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val neuralChordAnalyzer = DesktopNeuralChordAnalyzer(
        cacheDirectory = runtime.paths.cache,
        modelDirectory = DesktopStemStorage.modelsDirectory(runtime.paths),
        manifestUrl = DesktopBuildInfo.MODEL_MANIFEST_URL,
        decodeInput = { item, output, sampleRate, channels ->
            playback.decodeAnalysisInput(item, output, sampleRate, channels)
        },
        onFailure = { item, error ->
            runtime.logger.log(java.util.logging.Level.FINE, "Local neural chord analysis unavailable for " + item.metadata.id, error)
        },
    )
    val paxsenixCredentialService = DesktopPaxsenixCredentialService(
        secureSessionStore(runtime.paths.data.resolve("paxsenix-lyrics.dpapi")),
    )
    val lyricsTranslationService = DesktopLyricsTranslationService(
        settings,
        secureSessionStore(runtime.paths.data.resolve("lyrics-translation.dpapi")),
        JvmLyricsTranslationCache(runtime.paths.cache.resolve("lyrics-translations.json")),
    )
    val lastFm = DesktopLastFmService(
        settings = settings,
        credentialStore = secureSessionStore(runtime.paths.data.resolve("lastfm.dpapi")),
        parentScope = runtime.applicationScope,
        defaultApiKey = DesktopLastFmBuildCredentials.API_KEY,
        defaultSecret = DesktopLastFmBuildCredentials.SECRET,
    )
    private val lyricsPresenter = lazy {
        DesktopLyricsPresenter(
            playback,
            configuredLyricsClient(),
            lyricsScope.value,
            initiallyActive = false,
            localChordAnalyzer = neuralChordAnalyzer::analyze,
            lyricsTranslator = lyricsTranslationService,
            translationTargetLanguage = { settings.snapshot().lyricsTranslationTargetLanguage },
        )
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
    val statistics = DesktopStatisticsStore(runtime.paths.data.resolve("statistics.json"))
    private val statisticsTracker = DesktopListenTracker(statistics)
    @Volatile private var statisticsPartyActive = false
    /** Song→album knowledge for backfilling album links on tracks served without album metadata. */
    val albumIndex = DesktopAlbumIndex(runtime.paths.data.resolve("album-index.json"))
    val personalization = DesktopPersonalizationCoordinator(
        backend = backend,
        homeStore = homeStore,
        coordinator = contentCoordinator,
        scope = runtime.applicationScope,
        accountScopeProvider = ::accountCacheScope,
        isSignedInProvider = { account.state.value is DesktopAccountState.SignedIn },
    )
    val similarRecommendations: StateFlow<List<DesktopSimilarRecommendation>>
        get() = personalization.similarRecommendations

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
    private val linuxMediaControls = if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
        LinuxMediaControls()
    } else null
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
    private val linuxGlobalKeyBindings = if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
        LinuxGlobalKeyBindings(onAction = { actionId ->
            DesktopKeybindAction.entries.firstOrNull { it.name == actionId }?.let(keybindCommands::tryEmit)
        })
    } else null
    val keybindStatus: StateFlow<String?> = linuxGlobalKeyBindings?.status ?: MutableStateFlow(null)
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

    fun setStatisticsPartyActive(active: Boolean) {
        if (statisticsPartyActive == active) return
        statisticsPartyActive = active
        statisticsTracker.onSnapshot(playback.state.value, statisticsListeningEligible())
    }

    fun refreshStatisticsEligibility() {
        statisticsTracker.onSnapshot(playback.state.value, statisticsListeningEligible())
    }

    private fun statisticsListeningEligible(): Boolean =
        settings.snapshot().countPartyListeningInStatistics || !statisticsPartyActive

    private val sponsorBlock = DesktopSponsorBlockCoordinator(
        playback = playback,
        settingsProvider = settings::snapshot,
        canAutoSeek = { partyPlaybackPolicy().automaticSeekingAllowed },
    )

    init {
        sponsorBlock.start()
        runCatching { lastFm.restore() }
            .onFailure { failure -> runtime.logger.warning("Last.fm credentials restore failed (${failure.javaClass.simpleName})") }
        runCatching { paxsenixCredentialService.restore() }
            .onFailure { failure -> runtime.logger.warning("Paxsenix API key restore failed (${failure.javaClass.simpleName})") }
        runtime.applicationScope.launch {
            var previousCanvasKey: List<Any?>? = null
            while (true) {
                val item = playback.state.value.currentItem
                val configuration = settings.snapshot()
                val key = listOf(item?.metadata, configuration.desktopCanvasEnabled, configuration.desktopCanvasSource, configuration.desktopCanvasCacheLimitMb)
                if (key != previousCanvasKey) {
                    previousCanvasKey = key
                    if (item == null || item.metadata.isPodcast || item.source is PlaybackSource.LocalFile) canvas.clear()
                    else canvas.load(item.metadata.id, item.metadata.title, item.metadata.artists.firstOrNull()?.name.orEmpty(), item.metadata.artworkUri)
                }
                delay(500L)
            }
        }
        backend.bindSessionProvider(account)
        // Restore the DPAPI session synchronously before the first UI-driven backend call can race
        // ahead of it. Network revalidation stays asynchronous so startup is not blocked on it.
        account.restore()
        runtime.applicationScope.launch {
            account.refreshRestoredSession()
        }
        if (statistics.discardLegacyImportedData()) {
            runtime.logger.info("statistics event=discarded_legacy_home_history")
        }
        startPlayHistoryRecorder()
        startHomeSyncObserver()
        runtime.applicationScope.launch {
            playback.state.collect(nextTrackPrefetcher::onSnapshot)
        }
        runtime.applicationScope.launch {
            playback.state.collect { snapshot ->
                statisticsTracker.onSnapshot(snapshot, statisticsListeningEligible())
                lastFm.onSnapshot(
                    snapshot = snapshot,
                    eligible = statisticsListeningEligible() && snapshot.currentItem?.metadata?.isPodcast != true,
                )
            }
        }
        runtime.applicationScope.launch {
            while (true) {
                delay(STATISTICS_FLUSH_INTERVAL_MS)
                statisticsTracker.flush()
            }
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
                    durationMs = DesktopSettingsPolicy.crossfadeDurationMillis(restored),
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
        linuxMediaControls?.let { controls ->
            val router = DesktopLinuxMediaCommandRouter(
                playback,
                policy = ::partyPlaybackPolicy,
                partyToggle = { partyPlayPauseHandler() },
                partyNext = { partySkipNextHandler() },
                partyPrevious = { partySkipPreviousHandler() },
                partySeek = { partySeekHandler(it) },
            )
            runtime.applicationScope.launch { controls.commands.collect(router::handle) }
            runtime.applicationScope.launch { playback.state.collect(controls::update) }
            controls.start()
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
            linuxGlobalKeyBindings?.updateBindings(
                settings.snapshot().keybinds.map { binding ->
                    LinuxGlobalKeyBinding(binding.action.name, binding.virtualKeys.toSet(), binding.repeatWhileHeld)
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
        linuxGlobalKeyBindings?.setCaptureActive(active)
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
        streamSourceResolver.attachCompletedDownloadLookup(lookup)
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
    suspend fun syncHomeLibrary() = personalization.syncHomeLibrary()

    /**
     * Builds "Similar to ..." shelves from the user's most-played artists and songs, mirroring
     * Android's loadSimilarRecommendations. Artist seeds expand their top sections; song seeds use
     * the related-items endpoint.
     */
    suspend fun loadSimilarRecommendations() = personalization.loadSimilarRecommendations()

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
                    podcastRepository.onAccountScopeChanged()
                    playlistSuggestions.onAccountScopeChanged()
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
                                    personalization.warmQuickPickRelatedCache()
                                    loadSimilarRecommendations()
                                }
                            }
                        }
                    } else {
                        personalization.clearAccountData()
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

    internal fun playlistMutations(browseEffects: DesktopPlaylistBrowseEffects): DesktopPlaylistMutationRepository =
        DesktopPlaylistMutationRepository(
            backend = backend,
            libraryRepository = libraryRepository,
            browseEffects = browseEffects,
            accountScopeProvider = ::accountCacheScope,
            accountEpochProvider = { account.accountRevision },
        )

    private fun startPlayHistoryRecorder() {
        runtime.applicationScope.launch {
            playback.events.collect { event ->
                if (event !is PlaybackEvent.MediaTransition) return@collect
                statisticsTracker.onMediaTransition()
                val snapshot = playback.state.value
                statisticsTracker.onSnapshot(snapshot, statisticsListeningEligible())
                lastFm.onMediaTransition(
                    snapshot = snapshot,
                    eligible = statisticsListeningEligible() && snapshot.currentItem?.metadata?.isPodcast != true,
                    previousTrackId = event.previous?.metadata?.id,
                )
                personalization.onMediaTransition(event.current)
            }
        }
    }

    override fun close() {
        val state = playback.state.value
        personalization.flushPlayHistoryCurrent()
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
        sponsorBlock.close()
        lastFm.close()
        canvas.close()
        statisticsTracker.flush()
        statistics.close()
        globalKeyBindings?.close()
        linuxGlobalKeyBindings?.close()
        windowsMediaControls?.close()
        linuxMediaControls?.close()
        homeStore.close()
        albumIndex.close()
        stems.close()
        playback.close()
        streamSourceResolver.close()
        backend.bindSessionProvider(null)
    }

    private fun configuredLyricsClient(): JvmLyricsClient {
        val allProviders = BuiltInLyricsProviders.defaults
        val defaults = if (paxsenixCredentialService.hasApiKey()) {
            allProviders
        } else {
            allProviders.filterNot { it.id in BuiltInLyricsProviders.paxsenixProviderIds }
        }
        val selected = BuiltInLyricsProviders.normalizeProviderIds(settings.snapshot().lyricsProviderIds)
        if (selected.isEmpty()) return JvmLyricsClient(defaults)
        val byId = defaults.associateBy { it.id }
        val configured = selected.mapNotNull(byId::get)
        return JvmLyricsClient(configured.ifEmpty { defaults })
    }

    fun hasPaxsenixApiKey(): Boolean = paxsenixCredentialService.hasApiKey()

    fun updatePaxsenixApiKey(value: String?) {
        paxsenixCredentialService.setApiKey(value)
        if (lyricsPresenter.isInitialized()) {
            lyricsPresenter.value.updateLyricsClient(configuredLyricsClient())
        }
    }

    private companion object {
        const val BACKGROUND_MAINTENANCE_START_DELAY_MS = 1_500L
        const val KEYBIND_VOLUME_STEP = 0.02f
        const val KEYBIND_SPEED_STEP = 0.05f
        const val KEYBIND_SEEK_STEP_MS = 5_000L
        const val STATISTICS_FLUSH_INTERVAL_MS = 5_000L
        const val RESUME_AFTER_UPDATE_MARKER = "resume-after-update"
    }
}

internal data class DesktopPartyPlaybackPolicy(
    val localTransportAllowed: Boolean = true,
    val persistSpeedAndRepeat: Boolean = true,
    val persistShuffle: Boolean = true,
    val automaticSeekingAllowed: Boolean = true,
)

private fun moe.rukamori.archivetune.desktopauth.DesktopAccount.cacheScope(): String =
    "account-${sha1(listOf(email.orEmpty(), channelHandle.orEmpty(), name).joinToString(separator = "\u0000"))}"

private data class PlaybackPreferences(
    val volume: Float,
    val speed: Float,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
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

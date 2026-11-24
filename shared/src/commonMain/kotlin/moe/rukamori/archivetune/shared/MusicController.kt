package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.api.PlaybackParameterPersistence
import moe.rukamori.archivetune.playback.api.PlaybackQueue
import moe.rukamori.archivetune.playback.api.PlaybackStartupContentionGate
import moe.rukamori.archivetune.playback.api.RepeatMode
import moe.rukamori.archivetune.playback.api.SleepTimerController
import moe.rukamori.archivetune.playback.api.fallbackClickedItemQueue
import moe.rukamori.archivetune.playback.api.selectClickedItemQueue
import moe.rukamori.archivetune.playback.ios.IosPlaybackController
import moe.rukamori.archivetune.playback.ios.IosPlaybackEngine
import moe.rukamori.archivetune.playback.ios.IosRemoteCommandHandlers
import moe.rukamori.archivetune.playback.ios.validateIosPlaybackSource
import moe.rukamori.archivetune.sdk.discovery.DiscoveryContentCache
import moe.rukamori.archivetune.sdk.discovery.DiscoveryFanOut
import moe.rukamori.archivetune.sdk.discovery.DiscoveryKeys
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle

data class MusicUiState(
    val query: String = "",
    val queueTitle: String? = null,
    val results: List<MusicTrack> = emptyList(),
    val searchItems: List<MusicCatalogItem> = emptyList(),
    val searchSections: List<MusicSearchSection> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val searching: Boolean = false,
    val queue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val resolvingTrackId: String? = null,
    val error: String? = null,
    val failedPlayback: Boolean = false,
)

/**
 * Application-owned services backed by ArchiveTune SDK modules (`sdk-lifecycle`, `playback-core`,
 * `ios-playback`, `discovery-sdk`, `lyrics-sdk`, `social-sdk`/`social-session`).
 * Navigation and screen locking never dispose the player.
 */
class MusicController(
    private val catalog: MusicCatalog,
    private val player: PlayerEngine,
    scope: CoroutineScope,
    private val lyricsController: LyricsController? = null,
    private val libraryController: MusicLibrary? = null,
    private val onShareTrack: ((MusicTrack) -> Unit)? = null,
    val social: MusicSocialController? = null,
    val news: MusicNewsController? = null,
    val platformBridge: MusicPlatformBridge = NoOpPlatformBridge,
) : AutoCloseable {
    constructor(
        catalog: MusicCatalog,
        engine: IosPlaybackEngine,
        scope: CoroutineScope,
        lyricsController: LyricsController? = null,
        libraryController: MusicLibrary? = null,
        onShareTrack: ((MusicTrack) -> Unit)? = null,
        social: MusicSocialController? = null,
        news: MusicNewsController? = null,
        platformBridge: MusicPlatformBridge = NoOpPlatformBridge,
    ) : this(
        catalog = catalog,
        player = IosEnginePlayerBridge(engine, scope),
        scope = scope,
        lyricsController = lyricsController,
        libraryController = libraryController,
        onShareTrack = onShareTrack,
        social = social,
        news = news,
        platformBridge = platformBridge,
    ) {
        engine.bindRemoteCommands(
            IosRemoteCommandHandlers(
                onPlay = { if (!playback.value.isPlaying) togglePlayback() },
                onPause = { if (playback.value.isPlaying || playback.value.isBuffering) togglePlayback() },
                onTogglePlayPause = ::togglePlayback,
                onNext = ::next,
                onPrevious = ::previous,
                onSeekTo = ::seekTo,
            ),
        )
        lifecycle.onClosing("unbind-ios-remote-commands") {
            engine.bindRemoteCommands(null)
        }
        syncRemoteNavigationAvailability()
    }

    private val lifecycle = SdkLifecycle.forScope(scope)
    private val applicationScope = lifecycle.scope()
    private val playbackQueue = PlaybackQueue()
    private val browseCache = lifecycle.register(
        DiscoveryContentCache<MusicBrowsePage>(
            accountScopeProvider = { ANON_SCOPE },
            maxEntries = 8,
            loaderContext = scope.coroutineContext,
        ),
    )
    private val searchCache = lifecycle.register(
        DiscoveryContentCache<Pair<List<MusicCatalogItem>, List<MusicSearchSection>>>(
            accountScopeProvider = { ANON_SCOPE },
            maxEntries = 12,
            loaderContext = scope.coroutineContext,
        ),
    )
    private val moodArtworkFanOut = DiscoveryFanOut(batchSize = 3)

    private val mutableState = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = mutableState.asStateFlow()
    val playback: StateFlow<PlaybackState> = player.state
    private val mutablePlaybackOptions = MutableStateFlow(MusicPlaybackOptions())
    val playbackOptions: StateFlow<MusicPlaybackOptions> = mutablePlaybackOptions.asStateFlow()

    private val sleepTimer = lifecycle.register(
        SleepTimerController(
            scope = applicationScope,
            pause = {
                player.pause()
                mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = null, sleepAfterCurrent = false) }
            },
        ),
    )

    val canShare: Boolean get() = onShareTrack != null || platformBridge.canShare
    val canAdjustVolume: Boolean get() = player.canAdjustVolume
    val library: StateFlow<MusicLibraryState>? = libraryController?.state
    val lyrics: StateFlow<LyricsState> = lyricsController?.state ?: MutableStateFlow(LyricsState())
    private val mutableHome = MutableStateFlow(MusicHomeState())
    val home: StateFlow<MusicHomeState> = mutableHome.asStateFlow()
    private val mutableDownloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = mutableDownloading.asStateFlow()
    private val mutableYouTubeSigningIn = MutableStateFlow(false)
    val youtubeSigningIn: StateFlow<Boolean> = mutableYouTubeSigningIn.asStateFlow()
    private val mutableYouTubeAuthError = MutableStateFlow<String?>(null)
    val youtubeAuthError: StateFlow<String?> = mutableYouTubeAuthError.asStateFlow()
    private val downloadJobs = mutableMapOf<String, Job>()
    private val statisticsSession = AudibleStatisticsSession()
    private var homeJob: Job? = null
    private var homeMoreJob: Job? = null
    private var homeGeneration = 0
    private var browseJob: Job? = null
    private var playlistPlayJob: Job? = null
    private var radioQueueJob: Job? = null
    private var browseGeneration = 0
    private val mutableBrowse = MutableStateFlow(MusicBrowseState())
    val browse: StateFlow<MusicBrowseState> = mutableBrowse.asStateFlow()
    private val browseBackStack = mutableListOf<MusicBrowseState>()
    private val mutableDiscovery = MutableStateFlow(MusicDiscoveryState())
    val discovery: StateFlow<MusicDiscoveryState> = mutableDiscovery.asStateFlow()
    private var discoveryJob: Job? = null
    private var suggestionJob: Job? = null
    private var suggestionGeneration = 0
    private var searchJob: Job? = null
    private var resolveJob: Job? = null
    private var nextTrackPrefetchJob: Job? = null
    private var lastPrefetchedTrackId: String? = null
    private var searchGeneration = 0
    private var playbackGeneration = 0
    private var endedWithoutAdvanceGeneration: Int? = null

    /** Coordinates non-critical background fetches so they never starve cold track startup. */
    val startupContentionGate: PlaybackStartupContentionGate = PlaybackStartupContentionGate()

    init {
        lyricsController?.let { lifecycle.register(it) }
        social?.let { lifecycle.register(it) }
        lifecycle.register(player)
        lifecycle.register(catalog)

        // Restore persisted YouTube auth from Keychain if present
        platformBridge.loadYouTubeAuth()?.let { savedAuth ->
            catalog.setAuth(savedAuth)
            savedAuth.account?.let { libraryController?.setYouTubeAccount(it) }
        }

        libraryController?.let { saved ->
            applicationScope.launch {
                saved.awaitLoaded()
                if (!lifecycle.isClosed) {
                    val settings = saved.state.value.settings
                    catalog.setLocale(settings.contentLanguage, settings.contentCountry)
                    catalog.setAudioQuality(settings.audioQuality)
                    platformBridge.setKeepScreenAwake(settings.keepScreenAwake)
                    if (settings.playbackSpeed != 1f) {
                        setSpeed(settings.playbackSpeed)
                    }
                    if (playbackGeneration == 0 && settings.persistentQueue) {
                        val restoredQueue = saved.state.value.queue
                        val restoredIndex = saved.state.value.queueIndex
                        playbackQueue.replace(restoredQueue.map(MusicTrack::toPlaybackItem), restoredIndex.coerceAtLeast(0))
                        syncRemoteNavigationAvailability()
                        mutableState.update { it.copy(queue = restoredQueue, queueIndex = restoredIndex) }
                    }
                }
            }
        }
        applicationScope.launch {
            var wasEnded = false
            var lyricsTrack: MusicTrack? = null
            var lastPresenceTrackId: String? = null
            var lastPresencePlaying = false
            playback.collect { transport ->
                val settings = libraryController?.state?.value?.settings ?: MusicSettings()
                if (transport.isPlaying) {
                    if (!transport.isBuffering) {
                        startupContentionGate.markReady()
                        scheduleNextTrackPrefetch()
                    }
                    transport.track?.let {
                        libraryController?.recordPlay(it)
                        libraryController?.recordCachedTrack(it)
                    }
                } else if (transport.error != null || transport.ended) {
                    startupContentionGate.markReady()
                }
                // Authoritative audible-time statistics (monotonic clock; never counts paused/buffering/seeking)
                val delta = statisticsSession.onSample(
                    track = transport.track,
                    isAudiblePlaying = transport.isPlaying && !transport.isBuffering && !transport.ended,
                    eligible = !settings.pauseListenHistory,
                )
                if (delta != null && delta.listeningMs > 0L) {
                    libraryController?.recordAudibleDelta(delta, currentLocalDateTimeParts())
                }
                // Publish social listening presence when track or play state changes
                if (transport.track?.id != lastPresenceTrackId || transport.isPlaying != lastPresencePlaying) {
                    lastPresenceTrackId = transport.track?.id
                    lastPresencePlaying = transport.isPlaying
                    social?.publishPresence(
                        moe.rukamori.archivetune.social.SocialPresence(
                            online = true,
                            songId = if (transport.isPlaying) transport.track?.id else null,
                            title = if (transport.isPlaying) transport.track?.title else null,
                            artist = if (transport.isPlaying) transport.track?.artist else null,
                            artworkUrl = if (transport.isPlaying) transport.track?.artworkUrl else null,
                        ),
                    )
                }
                if (transport.track != lyricsTrack && (transport.track == null || transport.isPlaying || !startupContentionGate.isStartingUp.value)) {
                    lyricsTrack = transport.track
                    lyricsController?.select(transport.track)
                }
                if (!lifecycle.isClosed && transport.ended && !wasEnded &&
                    transport.track?.id == state.value.queue.getOrNull(state.value.queueIndex)?.id &&
                    state.value.resolvingTrackId == null
                ) {
                    if (sleepTimer.state.pauseWhenSongEnd) {
                        endedWithoutAdvanceGeneration = playbackGeneration
                        sleepTimer.onPlaybackEnded()
                        sleepTimer.clear()
                        mutablePlaybackOptions.update { it.copy(sleepAfterCurrent = false) }
                    } else if (playbackQueue.repeatMode == RepeatMode.ONE) {
                        player.seekTo(0)
                        player.play()
                    } else {
                        advanceQueue(automatic = true)
                    }
                }
                wasEnded = transport.ended
            }
        }
    }

    /** Home survives route changes; refresh retains visible shelves and coalesces requests. */
    fun loadHome(force: Boolean = false) {
        if (lifecycle.isClosed || homeJob?.isActive == true || (!force && home.value.loaded)) return
        homeMoreJob?.cancel()
        mutableHome.update { it.copy(loading = true, error = null) }
        val generation = ++homeGeneration
        homeJob = applicationScope.launch {
            try {
                val selected = home.value.selectedChip
                val page = if (selected == null) {
                    catalog.homePage(null)
                } else {
                    val browsed = catalog.browse(selected.browseId, selected.params)
                    MusicHomePage(browsed.shelves, catalog.homeChips(), browsed.continuation)
                }
                if (!lifecycle.isClosed && generation == homeGeneration) mutableHome.value = MusicHomeState(
                    shelves = page.shelves,
                    chips = page.chips,
                    selectedChip = selected,
                    continuation = page.continuation,
                    loaded = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!lifecycle.isClosed && generation == homeGeneration) mutableHome.update {
                    it.copy(loading = false, error = "Home could not connect. Try again.")
                }
            }
        }
    }

    fun loadMoreHome() {
        val token = home.value.continuation?.takeIf(String::isNotBlank) ?: return
        if (lifecycle.isClosed || homeMoreJob?.isActive == true || home.value.loading || home.value.loadingMore) return
        val generation = homeGeneration
        mutableHome.update { it.copy(loadingMore = true) }
        homeMoreJob = applicationScope.launch {
            try {
                val next = catalog.homePage(token)
                if (!lifecycle.isClosed && generation == homeGeneration) {
                    mutableHome.update { current ->
                        val existingTitles = current.shelves.map { it.title }.toSet()
                        val added = next.shelves.filterNot { it.title in existingTitles }
                        current.copy(
                            shelves = current.shelves + added,
                            continuation = next.continuation?.takeIf { it != token },
                            loadingMore = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!lifecycle.isClosed && generation == homeGeneration) {
                    mutableHome.update { it.copy(loadingMore = false, continuation = null) }
                }
            }
        }
    }

    fun selectHomeChip(chip: MusicHomeChip) {
        if (lifecycle.isClosed) return
        homeJob?.cancel()
        ++homeGeneration
        mutableHome.update { it.copy(selectedChip = chip.takeUnless { it == home.value.selectedChip }) }
        loadHome(force = true)
    }

    /** Bounded detail cache via `discovery-sdk` preserves route state and cancels obsolete loads. */
    fun openBrowse(item: MusicCatalogItem.Browse) {
        if (lifecycle.isClosed) return
        browseJob?.cancel()
        val previous = browse.value
        val isNewDetail = previous.item != null && (previous.item.id != item.id || previous.item.params != item.params)
        val depth = if (previous.item == null) 1 else previous.depth + if (isNewDetail) 1 else 0
        if (isNewDetail) {
            browseBackStack += previous
            if (browseBackStack.size > 8) browseBackStack.removeAt(0)
        }
        val generation = ++browseGeneration
        val key = DiscoveryKeys.browse(item.id, item.params)
        val cached = browseCache.peek(key)
        mutableBrowse.value = MusicBrowseState(item = item, page = cached, loading = cached == null, depth = depth)
        if (cached != null) return
        browseJob = applicationScope.launch {
            try {
                val page = browseCache.getOrLoad(key) { catalog.browse(item.id, item.params) }
                if (lifecycle.isClosed || generation != browseGeneration) return@launch
                mutableBrowse.value = MusicBrowseState(item = item, page = page, depth = depth)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!lifecycle.isClosed && generation == browseGeneration) mutableBrowse.update {
                    it.copy(loading = false, error = "This page could not connect. Try again.")
                }
            }
        }
    }

    fun playBrowse(item: MusicCatalogItem.Browse) {
        if (lifecycle.isClosed) return
        playlistPlayJob?.cancel()
        val expected = ++playbackGeneration
        resolveJob?.cancel()
        nextTrackPrefetchJob?.cancel()
        nextTrackPrefetchJob = null
        lastPrefetchedTrackId = null
        val startupToken = startupContentionGate.beginStartup()
        player.pause()
        mutableState.update { it.copy(resolvingTrackId = item.id, error = null) }
        playlistPlayJob = applicationScope.launch {
            try {
                val page = catalog.browse(item.id, item.params)
                if (lifecycle.isClosed || playbackGeneration != expected) return@launch
                if (page.tracks.isEmpty()) error("Empty playlist")
                playAt(0, page.tracks)
                mutableState.update { it.copy(queueTitle = page.title.ifBlank { item.title }) }
            } catch (cancelled: CancellationException) {
                startupContentionGate.markReady(startupToken)
                throw cancelled
            } catch (_: Exception) {
                startupContentionGate.markReady(startupToken)
                if (!lifecycle.isClosed && playbackGeneration == expected) mutableState.update {
                    it.copy(resolvingTrackId = null, error = "This playlist could not be played. Try again.")
                }
            }
        }
    }

    fun retryBrowse() { browse.value.item?.let(::openBrowse) }

    fun closeBrowse() {
        browseJob?.cancel()
        ++browseGeneration
        val previous = if (browseBackStack.isNotEmpty()) browseBackStack.removeAt(browseBackStack.lastIndex) else null
        mutableBrowse.value = previous ?: MusicBrowseState()
        if (previous?.item != null && previous.page == null) openBrowse(previous.item)
    }

    fun clearBrowse() {
        browseJob?.cancel()
        ++browseGeneration
        browseBackStack.clear()
        mutableBrowse.value = MusicBrowseState()
    }

    fun loadDiscovery(force: Boolean = false) {
        if (lifecycle.isClosed || discoveryJob?.isActive == true || (!force && discovery.value.loaded)) return
        mutableDiscovery.update { it.copy(loading = true, error = null) }
        discoveryJob = applicationScope.launch {
            try {
                val data = catalog.discovery()
                if (lifecycle.isClosed) return@launch
                mutableDiscovery.value = MusicDiscoveryState(data = data, loaded = true)
                moodArtworkFanOut.mapBatched(data.moods.take(24)) { mood ->
                    val url = try {
                        catalog.moodArtwork(mood)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (url != null && !lifecycle.isClosed) mutableDiscovery.update { current ->
                        current.copy(data = current.data.copy(moods = current.data.moods.map {
                            if (it.endpoint == mood.endpoint) it.copy(artworkUrl = url) else it
                        }))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!lifecycle.isClosed) mutableDiscovery.update {
                    it.copy(loading = false, error = "Search discovery could not connect. Try again.")
                }
            }
        }
    }

    fun suggest(query: String) {
        if (lifecycle.isClosed) return
        suggestionJob?.cancel()
        val generation = ++suggestionGeneration
        val input = query.trim().take(200)
        mutableState.update { it.copy(suggestions = emptyList()) }
        if (input.isEmpty()) return
        suggestionJob = applicationScope.launch {
            try {
                val suggestions = catalog.searchSuggestions(input)
                if (!lifecycle.isClosed && generation == suggestionGeneration) {
                    mutableState.update { it.copy(suggestions = suggestions) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A suggestion failure never replaces results or prevents search.
            }
        }
    }

    fun search(query: String, params: String? = null) {
        if (lifecycle.isClosed) return
        val normalized = query.trim().take(200)
        val generation = ++searchGeneration
        if (normalized.isNotEmpty()) libraryController?.recordSearch(normalized)
        searchJob?.cancel()
        val cacheKey = DiscoveryKeys.search(normalized, params.orEmpty())
        val cached = if (normalized.isEmpty()) null else searchCache.peek(cacheKey)
        mutableState.update {
            it.copy(
                query = query,
                searchItems = cached?.first ?: it.searchItems,
                searchSections = cached?.second ?: it.searchSections,
                results = cached?.first?.mapNotNull { item -> (item as? MusicCatalogItem.Track)?.track } ?: it.results,
                searching = normalized.isNotEmpty(),
                error = null,
                failedPlayback = false,
            )
        }
        if (normalized.isEmpty()) {
            mutableState.update { it.copy(results = emptyList(), searchItems = emptyList(), searchSections = emptyList(), searching = false) }
            return
        }
        if (cached != null) {
            mutableState.update { it.copy(searching = false) }
            return
        }
        searchJob = applicationScope.launch {
            try {
                val (results, sections) = searchCache.getOrLoad(cacheKey) {
                    if (params == null) {
                        val secs = catalog.searchSections(normalized)
                        val flat = secs.flatMap { it.items }.distinctBy { item ->
                            when (item) {
                                is MusicCatalogItem.Track -> "track:${item.track.id}"
                                is MusicCatalogItem.Browse -> "browse:${item.id}"
                            }
                        }.take(60)
                        flat to secs
                    } else {
                        catalog.searchItems(normalized, params).take(60) to emptyList()
                    }
                }
                if (lifecycle.isClosed || generation != searchGeneration) return@launch
                mutableState.update {
                    it.copy(
                        searchItems = results,
                        searchSections = sections,
                        results = results.mapNotNull { item -> (item as? MusicCatalogItem.Track)?.track },
                        searching = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == searchGeneration) mutableState.update {
                    it.copy(
                        searching = false,
                        error = (failure as? CatalogException)?.message ?: "Search could not connect. Please try again.",
                    )
                }
            }
        }
    }

    /** Start the click immediately; fill its radio queue via `playback-core` ClickedItemQueuePolicy. */
    fun playTrack(track: MusicTrack) {
        if (lifecycle.isClosed) return
        playAt(0, listOf(track))
        val generation = playbackGeneration
        radioQueueJob = applicationScope.launch {
            try {
                // Wait for the clicked track to finish initial startup before pulling radio/automix pages.
                startupContentionGate.awaitStartupClear()
                if (lifecycle.isClosed || generation != playbackGeneration) return@launch
                val queue = catalog.radioQueue(track)
                if (lifecycle.isClosed || generation != playbackGeneration) return@launch
                val selection = selectClickedItemQueue(
                    clickedItem = track.toPlaybackItem(),
                    clickedId = track.id,
                    returnedItems = queue.tracks.map(MusicTrack::toPlaybackItem),
                    itemId = { it.metadata.id },
                    returnedTitle = queue.title,
                    defaultTitle = "",
                )
                val tracks = selection.items.map(moe.rukamori.archivetune.playback.api.PlaybackItem::toMusicTrack)
                val selected = selection.startIndex
                playbackQueue.replace(selection.items, selected)
                syncRemoteNavigationAvailability()
                mutableState.update {
                    it.copy(
                        queue = tracks,
                        queueIndex = selected,
                        queueTitle = selection.title.takeIf(String::isNotBlank),
                    )
                }
                libraryController?.saveQueue(tracks, selected)
                if (playback.value.ended && state.value.resolvingTrackId == null && !sleepTimer.state.pauseWhenSongEnd && endedWithoutAdvanceGeneration != generation) {
                    next()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val fallback = fallbackClickedItemQueue(track.toPlaybackItem(), defaultTitle = state.value.queueTitle.orEmpty())
                if (!lifecycle.isClosed && generation == playbackGeneration) {
                    playbackQueue.replace(fallback.items, fallback.startIndex)
                }
            }
        }
    }

    /** The clicked list position wins, including duplicate songs in the queue. */
    fun playAt(index: Int, tracks: List<MusicTrack> = state.value.queue) {
        if (lifecycle.isClosed) return
        val track = tracks.getOrNull(index) ?: return
        playlistPlayJob?.cancel()
        radioQueueJob?.cancel()
        val sameQueue = tracks == state.value.queue && tracks.size == playbackQueue.items.size
        if (sameQueue) {
            playbackQueue.select(index)
        } else {
            playbackQueue.replace(tracks.map(MusicTrack::toPlaybackItem), index)
        }
        syncRemoteNavigationAvailability()
        val generation = ++playbackGeneration
        endedWithoutAdvanceGeneration = null
        if (playback.value.track?.id != track.id && sleepTimer.state.pauseWhenSongEnd) {
            sleepTimer.clear()
            mutablePlaybackOptions.update { it.copy(sleepAfterCurrent = false) }
        }
        resolveJob?.cancel()
        nextTrackPrefetchJob?.cancel()
        nextTrackPrefetchJob = null
        lastPrefetchedTrackId = null
        val startupToken = startupContentionGate.beginStartup()
        player.pause()
        mutableState.update {
            it.copy(
                queue = tracks.toList(),
                queueIndex = index,
                queueTitle = if (it.queue == tracks) it.queueTitle else null,
                resolvingTrackId = track.id,
                error = null,
                failedPlayback = false,
            )
        }
        libraryController?.saveQueue(tracks, index)
        resolveJob = applicationScope.launch {
            try {
                val offlineFile = track.localFilePath?.takeIf { platformBridge.fileExists(it) }
                    ?: libraryController?.state?.value?.downloads
                        ?.firstOrNull { it.track.id == track.id }
                        ?.filePath
                        ?.takeIf { platformBridge.fileExists(it) }
                val source = if (offlineFile != null) {
                    val mime = when {
                        offlineFile.endsWith(".mp3", true) -> "audio/mpeg"
                        offlineFile.endsWith(".wav", true) -> "audio/wav"
                        offlineFile.endsWith(".flac", true) -> "audio/flac"
                        else -> "audio/mp4"
                    }
                    ResolvedAudio(trackId = track.id, url = offlineFile, mimeType = mime)
                } else {
                    catalog.resolve(track)
                }
                if (lifecycle.isClosed || generation != playbackGeneration) return@launch
                require(source.trackId == track.id) { "Resolved track does not match the selected song" }
                validateIosPlaybackSource(source.toResolvedPlaybackSource())?.let { failure ->
                    throw IllegalArgumentException(failure.message)
                }
                player.load(track, source)
                player.setSpeed(playbackOptions.value.speed)
                if (player.canAdjustVolume) player.setVolume(playbackOptions.value.volume)
                player.play()
                mutableState.update { it.copy(resolvingTrackId = null) }
                if (playback.value.isPlaying && !playback.value.isBuffering) {
                    startupContentionGate.markReady(startupToken)
                    scheduleNextTrackPrefetch()
                }
            } catch (cancelled: CancellationException) {
                startupContentionGate.markReady(startupToken)
                throw cancelled
            } catch (failure: Exception) {
                startupContentionGate.markReady(startupToken)
                if (generation == playbackGeneration) {
                    val autoSkip = libraryController?.state?.value?.settings?.autoSkipNextOnError == true
                    if (autoSkip && playbackQueue.canAdvance) {
                        next()
                    } else {
                        mutableState.update {
                            it.copy(
                                resolvingTrackId = null,
                                failedPlayback = true,
                                error = (failure as? CatalogException)?.message ?: "This song could not be played. Try another song or retry.",
                            )
                        }
                    }
                }
            }
        }
    }

    fun shareCurrentTrack() {
        if (!lifecycle.isClosed) playback.value.track?.let { shareTrack(it) }
    }

    fun shareTrack(track: MusicTrack) {
        if (lifecycle.isClosed) return
        if (onShareTrack != null) onShareTrack.invoke(track)
        else platformBridge.shareText("${track.title} — ${track.artist}\nhttps://music.youtube.com/watch?v=${track.id}")
    }

    fun shareText(text: String) {
        if (!lifecycle.isClosed && text.isNotBlank()) platformBridge.shareText(text)
    }

    fun copyText(text: String) {
        if (!lifecycle.isClosed && text.isNotBlank()) platformBridge.copyToClipboard(text)
    }

    fun openUrl(url: String) {
        if (!lifecycle.isClosed && url.isNotBlank()) platformBridge.openUrl(url)
    }

    fun playNext(tracks: List<MusicTrack>) {
        if (lifecycle.isClosed || tracks.isEmpty()) return
        if (state.value.queue.isEmpty()) {
            playAt(0, tracks)
            return
        }
        playbackQueue.insertNext(tracks.map(MusicTrack::toPlaybackItem))
        syncRemoteNavigationAvailability()
        val updated = playbackQueue.items.map(moe.rukamori.archivetune.playback.api.PlaybackItem::toMusicTrack)
        val idx = playbackQueue.currentIndex
        mutableState.update { it.copy(queue = updated, queueIndex = idx) }
        libraryController?.saveQueue(updated, idx)
    }

    fun addToQueue(tracks: List<MusicTrack>) {
        if (lifecycle.isClosed || tracks.isEmpty()) return
        if (state.value.queue.isEmpty()) {
            playAt(0, tracks)
            return
        }
        playbackQueue.append(tracks.map(MusicTrack::toPlaybackItem))
        syncRemoteNavigationAvailability()
        val updated = playbackQueue.items.map(moe.rukamori.archivetune.playback.api.PlaybackItem::toMusicTrack)
        val idx = playbackQueue.currentIndex
        mutableState.update { it.copy(queue = updated, queueIndex = idx) }
        libraryController?.saveQueue(updated, idx)
    }

    fun saveQueueAsPlaylist(name: String): String? {
        if (lifecycle.isClosed) return null
        val tracks = state.value.queue
        if (tracks.isEmpty()) return null
        val id = libraryController?.createPlaylist(name) ?: return null
        tracks.forEach { libraryController.addToPlaylist(id, it) }
        return id
    }

    fun downloadTrack(track: MusicTrack) {
        if (lifecycle.isClosed || !platformBridge.canDownload || track.localFilePath != null) return
        if (downloadJobs[track.id]?.isActive == true) return
        if (libraryController?.state?.value?.downloads?.any { it.track.id == track.id } == true) return
        mutableDownloading.update { it + (track.id to 0.05f) }
        downloadJobs[track.id] = applicationScope.launch {
            try {
                val resolved = catalog.resolveForDownload(track)
                val result = platformBridge.downloadAudio(track.id, resolved) { progress ->
                    mutableDownloading.update { it + (track.id to progress) }
                }
                result.onSuccess { (fileUrl, sizeBytes) ->
                    libraryController?.recordDownload(
                        MusicDownloadedTrack(
                            track = track,
                            filePath = fileUrl,
                            sizeBytes = sizeBytes,
                            downloadedAtMs = currentEpochMillis(),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep UI responsive; download can be retried.
            } finally {
                mutableDownloading.update { it - track.id }
                downloadJobs.remove(track.id)
            }
        }
    }

    fun downloadTracks(tracks: List<MusicTrack>) {
        tracks.forEach(::downloadTrack)
    }

    fun removeDownload(trackId: String) {
        if (lifecycle.isClosed) return
        downloadJobs.remove(trackId)?.cancel()
        mutableDownloading.update { it - trackId }
        val existing = libraryController?.state?.value?.downloads?.firstOrNull { it.track.id == trackId }
        existing?.filePath?.let(platformBridge::deleteLocalFile)
        libraryController?.removeDownload(trackId)
    }

    fun clearAllDownloads() {
        if (lifecycle.isClosed) return
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        mutableDownloading.value = emptyMap()
        libraryController?.state?.value?.downloads?.forEach { platformBridge.deleteLocalFile(it.filePath) }
        libraryController?.clearDownloads()
    }

    fun importLocalAudioFiles() {
        if (lifecycle.isClosed || !platformBridge.canImportLocalFiles) return
        applicationScope.launch {
            val imported = platformBridge.importLocalAudioFiles()
            imported.forEach { libraryController?.addLocalTrack(it) }
        }
    }

    fun removeLocalTrack(trackId: String) {
        if (lifecycle.isClosed) return
        val existing = libraryController?.state?.value?.localTracks?.firstOrNull { it.id == trackId }
        existing?.localFilePath?.let(platformBridge::deleteLocalFile)
        libraryController?.removeLocalTrack(trackId)
    }

    fun signInYouTube() {
        if (lifecycle.isClosed || !platformBridge.canSignInYouTube || mutableYouTubeSigningIn.value) return
        mutableYouTubeSigningIn.value = true
        mutableYouTubeAuthError.value = null
        applicationScope.launch {
            try {
                val result = platformBridge.signInYouTube()
                result.onSuccess { auth ->
                    catalog.setAuth(auth)
                    val account = catalog.fetchAccountInfo() ?: MusicYouTubeAccount(name = "YouTube Music Account")
                    val enriched = auth.copy(account = account)
                    platformBridge.saveYouTubeAuth(enriched)
                    libraryController?.setYouTubeAccount(account)
                    loadHome(force = true)
                }.onFailure { err ->
                    mutableYouTubeAuthError.value = err.message ?: "YouTube sign-in failed"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableYouTubeAuthError.value = "YouTube sign-in failed"
            } finally {
                mutableYouTubeSigningIn.value = false
            }
        }
    }

    fun signOutYouTube() {
        if (lifecycle.isClosed) return
        catalog.setAuth(null)
        platformBridge.saveYouTubeAuth(null)
        libraryController?.setYouTubeAccount(null)
        mutableYouTubeAuthError.value = null
        loadHome(force = true)
    }

    fun exportBackup() {
        if (lifecycle.isClosed) return
        val json = libraryController?.exportBackupJson() ?: return
        platformBridge.shareText(json)
    }

    fun importBackup(onResult: (Boolean) -> Unit = {}) {
        if (lifecycle.isClosed || !platformBridge.canBackupRestore) {
            onResult(false)
            return
        }
        applicationScope.launch {
            val raw = platformBridge.importBackupJson()
            val ok = raw != null && libraryController?.importBackupJson(raw) == true
            onResult(ok)
        }
    }

    fun storageUsage(): MusicStorageUsage = platformBridge.computeStorageUsage()
    fun clearImageCache() { platformBridge.clearImageCache() }
    fun clearAudioCache() {
        platformBridge.clearAudioCache()
        libraryController?.clearCachedTracks()
    }

    /** Null cancels; delegates to `playback-core` [SleepTimerController]. */
    fun setSleepTimer(minutes: Int?) {
        if (lifecycle.isClosed) return
        val duration = minutes?.coerceIn(1, 180)
        sleepTimer.clear()
        mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = duration, sleepAfterCurrent = false) }
        if (duration != null) {
            sleepTimer.start(duration)
        }
    }

    fun setSleepAfterCurrent() {
        if (lifecycle.isClosed) return
        sleepTimer.clear()
        sleepTimer.start(-1)
        mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = null, sleepAfterCurrent = true) }
    }

    fun playShuffled(tracks: List<MusicTrack>) {
        if (lifecycle.isClosed || tracks.isEmpty()) return
        if (!playbackQueue.shuffleEnabled) {
            playbackQueue.setShuffleEnabled(true)
            mutablePlaybackOptions.update { it.copy(shuffle = true) }
        }
        val index = tracks.indices.random()
        playAt(index, tracks)
    }

    fun toggleShuffle() {
        if (lifecycle.isClosed) return
        val enabled = !playbackQueue.shuffleEnabled
        playbackQueue.setShuffleEnabled(enabled)
        if (enabled && playbackQueue.items.size > 1) {
            val currentVisual = playbackQueue.entries().indexOfFirst { it.isCurrent }
            if (currentVisual > 0) {
                playbackQueue.moveInPlayOrder(currentVisual, 0)
            }
        }
        mutablePlaybackOptions.update { it.copy(shuffle = enabled) }
        syncRemoteNavigationAvailability()
    }

    fun cycleRepeat() {
        if (lifecycle.isClosed) return
        val nextMode = when (playbackQueue.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playbackQueue.setRepeatMode(nextMode)
        mutablePlaybackOptions.update { it.copy(repeatMode = nextMode) }
        syncRemoteNavigationAvailability()
    }

    fun setSpeed(speed: Float) {
        if (lifecycle.isClosed || !speed.isFinite()) return
        val coreSanitized = PlaybackParameterPersistence.sanitize(speed, 0f).speed
        val value = coreSanitized.coerceIn(IosPlaybackController.MIN_IOS_SPEED, IosPlaybackController.MAX_IOS_SPEED)
        player.setSpeed(value)
        mutablePlaybackOptions.update { it.copy(speed = value) }
    }

    fun setVolume(volume: Float) {
        if (lifecycle.isClosed || !player.canAdjustVolume || !volume.isFinite()) return
        val value = volume.coerceIn(0f, 1f)
        player.setVolume(value)
        mutablePlaybackOptions.update { it.copy(volume = value) }
    }

    fun next() {
        if (!lifecycle.isClosed) advanceQueue(automatic = false)
    }

    private fun advanceQueue(automatic: Boolean) {
        val currentIndex = playbackQueue.currentIndex
        val selected = playbackQueue.advance(automatic = automatic) ?: return
        if (selected.index == currentIndex && selected.repeated && automatic && playbackQueue.repeatMode == RepeatMode.ONE) {
            player.seekTo(0)
            player.play()
        } else {
            playAt(selected.index)
        }
    }

    /** Thumbnail swipes follow `PlaybackQueue` visual order with repeat disabled, as on Android. */
    fun thumbnailNeighbor(direction: Int): Int? {
        val entries = playbackQueue.entries()
        val visualIndex = entries.indexOfFirst { it.isCurrent }
        if (visualIndex < 0) return null
        return entries.getOrNull(visualIndex + direction)?.logicalIndex
    }

    fun skipToPreviousItem() {
        if (!lifecycle.isClosed) thumbnailNeighbor(-1)?.let { playAt(it) }
    }

    fun previous() {
        if (lifecycle.isClosed) return
        if (playback.value.positionMs > 3_000) {
            seekTo(0)
        } else {
            val selected = playbackQueue.retreat()
            if (selected != null) {
                playAt(selected.index)
            } else if (state.value.queue.isNotEmpty()) {
                val firstVisual = playbackQueue.entries().firstOrNull()?.logicalIndex ?: 0
                playAt(firstVisual)
            }
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (lifecycle.isClosed || from == to) return
        val current = state.value
        if (from !in current.queue.indices || to !in current.queue.indices) return
        playbackQueue.move(from, to)
        syncRemoteNavigationAvailability()
        val queue = playbackQueue.items.map(moe.rukamori.archivetune.playback.api.PlaybackItem::toMusicTrack)
        val selected = playbackQueue.currentIndex
        mutableState.update { it.copy(queue = queue, queueIndex = selected) }
        libraryController?.saveQueue(queue, selected)
    }

    fun removeQueueItem(index: Int) {
        if (lifecycle.isClosed || index !in state.value.queue.indices) return
        val outcome = playbackQueue.removeAt(index)
        syncRemoteNavigationAvailability()
        val queue = playbackQueue.items.map(moe.rukamori.archivetune.playback.api.PlaybackItem::toMusicTrack)
        if (queue.isEmpty()) {
            resolveJob?.cancel()
            ++playbackGeneration
            player.stop()
            mutableState.update { it.copy(queue = emptyList(), queueIndex = -1, resolvingTrackId = null) }
            libraryController?.saveQueue(emptyList(), -1)
        } else if (outcome.currentChanged) {
            val selected = playbackQueue.currentIndex.coerceIn(0, queue.lastIndex)
            mutableState.update { it.copy(queue = queue, queueIndex = selected) }
            playAt(selected, queue)
        } else {
            val selected = playbackQueue.currentIndex
            mutableState.update { it.copy(queue = queue, queueIndex = selected) }
            libraryController?.saveQueue(queue, selected)
        }
    }

    fun dismissPlayback() {
        if (lifecycle.isClosed) return
        ++playbackGeneration
        resolveJob?.cancel()
        nextTrackPrefetchJob?.cancel()
        nextTrackPrefetchJob = null
        lastPrefetchedTrackId = null
        startupContentionGate.reset()
        playlistPlayJob?.cancel()
        radioQueueJob?.cancel()
        sleepTimer.clear()
        player.stop()
        playbackQueue.replace(emptyList())
        syncRemoteNavigationAvailability()
        mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = null, sleepAfterCurrent = false) }
        mutableState.update {
            it.copy(
                queue = emptyList(),
                queueIndex = -1,
                queueTitle = null,
                resolvingTrackId = null,
                error = null,
                failedPlayback = false,
            )
        }
        libraryController?.saveQueue(emptyList(), -1)
    }

    fun togglePlayback() {
        if (lifecycle.isClosed || state.value.resolvingTrackId != null) return
        if (state.value.failedPlayback || playback.value.error != null) {
            retryPlayback()
            return
        }
        if (playback.value.isPlaying || playback.value.isBuffering) {
            player.pause()
        } else {
            if (playback.value.ended) player.seekTo(0)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (!lifecycle.isClosed && state.value.resolvingTrackId == null) player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun toggleFavorite(track: MusicTrack) {
        if (lifecycle.isClosed) return
        val wasFav = libraryController?.state?.value?.favorites?.any { it.id == track.id } == true
        libraryController?.toggleFavorite(track)
        if (!wasFav && libraryController?.state?.value?.settings?.autoDownloadOnLike == true) {
            downloadTrack(track)
        }
    }

    fun setTheme(theme: MusicThemeChoice) { if (!lifecycle.isClosed) libraryController?.setTheme(theme) }

    fun updateSettings(transform: (MusicSettings) -> MusicSettings) {
        if (lifecycle.isClosed) return
        libraryController?.updateSettings(transform)
        val updated = libraryController?.state?.value?.settings ?: return
        catalog.setLocale(updated.contentLanguage, updated.contentCountry)
        catalog.setAudioQuality(updated.audioQuality)
        platformBridge.setKeepScreenAwake(updated.keepScreenAwake)
        if (updated.playbackSpeed != playbackOptions.value.speed) {
            setSpeed(updated.playbackSpeed)
        }
    }

    fun toggleSpeedDialPin(pin: MusicSpeedDialPin) { if (!lifecycle.isClosed) libraryController?.toggleSpeedDialPin(pin) }
    fun toggleSavedBrowse(item: MusicCatalogItem.Browse) { if (!lifecycle.isClosed) libraryController?.toggleSavedBrowse(item) }
    fun clearHistory() { if (!lifecycle.isClosed) libraryController?.clearHistory() }
    fun removeFromHistory(trackId: String) { if (!lifecycle.isClosed) libraryController?.removeFromHistory(trackId) }
    fun clearSearchHistory() { if (!lifecycle.isClosed) libraryController?.clearSearchHistory() }
    fun resetStatistics() { if (!lifecycle.isClosed) libraryController?.resetStatistics() }
    fun reorderPlaylistTrack(playlistId: String, from: Int, to: Int) { if (!lifecycle.isClosed) libraryController?.reorderPlaylistTrack(playlistId, from, to) }
    fun createPlaylistTag(name: String): String? = if (lifecycle.isClosed) null else libraryController?.createPlaylistTag(name)
    fun deletePlaylistTag(tagId: String) { if (!lifecycle.isClosed) libraryController?.deletePlaylistTag(tagId) }
    fun togglePlaylistTag(tagId: String, playlistId: String) { if (!lifecycle.isClosed) libraryController?.togglePlaylistTag(tagId, playlistId) }
    fun markNewsRead(newsId: String) { if (!lifecycle.isClosed) libraryController?.markNewsRead(newsId) }

    fun removeSearchHistory(query: String) { if (!lifecycle.isClosed) libraryController?.removeSearch(query) }

    fun createPlaylist(name: String): String? = if (lifecycle.isClosed) null else libraryController?.createPlaylist(name)
    fun renamePlaylist(id: String, name: String) { if (!lifecycle.isClosed) libraryController?.renamePlaylist(id, name) }
    fun deletePlaylist(id: String) { if (!lifecycle.isClosed) libraryController?.deletePlaylist(id) }
    fun addToPlaylist(id: String, track: MusicTrack) { if (!lifecycle.isClosed) libraryController?.addToPlaylist(id, track) }
    fun removeFromPlaylist(id: String, index: Int) { if (!lifecycle.isClosed) libraryController?.removeFromPlaylist(id, index) }

    suspend fun flushLibrary() { libraryController?.flush() }

    fun retryLibrarySave() {
        if (!lifecycle.isClosed) applicationScope.launch { try { libraryController?.flush() } catch (_: Exception) {} }
    }

    fun retryLyrics() { if (!lifecycle.isClosed) lyricsController?.select(playback.value.track, refresh = true) }
    fun searchLyricsVersions(title: String, artist: String, album: String? = null) {
        if (!lifecycle.isClosed) lyricsController?.searchVersions(title, artist, album)
    }
    fun selectLyricsVersion(version: LyricsVersion) { if (!lifecycle.isClosed) lyricsController?.selectVersion(version) }

    fun retryPlayback() {
        if (!lifecycle.isClosed && (state.value.failedPlayback || playback.value.error != null)) {
            state.value.queue.getOrNull(state.value.queueIndex)?.id?.let(catalog::invalidateResolved)
            playAt(state.value.queueIndex)
        }
    }

    private fun scheduleNextTrackPrefetch() {
        if (lifecycle.isClosed || startupContentionGate.isStartingUp.value) return
        val nextIndex = thumbnailNeighbor(+1) ?: return
        val nextTrack = state.value.queue.getOrNull(nextIndex) ?: return
        if (nextTrack.id == state.value.queue.getOrNull(state.value.queueIndex)?.id) return
        if (nextTrack.localFilePath != null) return
        if (lastPrefetchedTrackId == nextTrack.id) return
        lastPrefetchedTrackId = nextTrack.id
        nextTrackPrefetchJob?.cancel()
        nextTrackPrefetchJob = applicationScope.launch {
            try {
                // Allow the active track to build steady-state buffer and avoid wasted resolves during rapid skips.
                delay(NEXT_TRACK_PREFETCH_DELAY_MS)
                if (lifecycle.isClosed || startupContentionGate.isStartingUp.value) return@launch
                catalog.resolve(nextTrack)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Speculative prefetch failure is benign; playback will resolve on transition.
            }
        }
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    /** Await this at application teardown when a persistent library was supplied. */
    suspend fun closeAndFlush() {
        close()
        libraryController?.close()
    }

    override fun close() {
        if (lifecycle.isClosed) return
        ++searchGeneration
        ++playbackGeneration
        nextTrackPrefetchJob?.cancel()
        nextTrackPrefetchJob = null
        startupContentionGate.reset()
        browseBackStack.clear()
        lifecycle.close()
    }

    private fun syncRemoteNavigationAvailability() {
        (player as? IosEnginePlayerBridge)?.updateNavigationAvailability(
            hasPrevious = playbackQueue.canRetreat,
            hasNext = playbackQueue.canAdvance,
        )
    }

    private companion object {
        const val ANON_SCOPE = "anon"
        const val NEXT_TRACK_PREFETCH_DELAY_MS = 2_000L
    }
}

/** Thin bridge adapting an `ios-playback` [IosPlaybackEngine] (e.g. `AvFoundationPlaybackEngine`) to [PlayerEngine]. */
private class IosEnginePlayerBridge(
    private val engine: IosPlaybackEngine,
    scope: CoroutineScope,
) : PlayerEngine {
    override val canAdjustVolume: Boolean get() = engine.canAdjustVolume
    private val lifecycle = SdkLifecycle.forScope(scope)
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var loadedTrack: MusicTrack? = null

    init {
        lifecycle.register(engine)
        lifecycle.scope().launch {
            engine.state.collect { engineState ->
                publishEngineState(engineState)
            }
        }
    }

    fun updateNavigationAvailability(hasPrevious: Boolean, hasNext: Boolean) {
        engine.updateNavigationAvailability(hasPrevious = hasPrevious, hasNext = hasNext)
    }

    override fun load(track: MusicTrack, source: ResolvedAudio) {
        loadedTrack = track
        engine.load(track.toPlaybackItem(), source.toResolvedPlaybackSource())
        syncState()
    }

    override fun play() {
        engine.play()
        syncState()
    }

    override fun pause() {
        engine.pause()
        syncState()
    }

    override fun seekTo(positionMs: Long) {
        engine.seekTo(positionMs)
        syncState()
    }

    override fun setSpeed(speed: Float) = engine.setSpeed(speed)
    override fun setVolume(volume: Float) = engine.setVolume(volume)

    override fun stop() {
        loadedTrack = null
        engine.stop()
        syncState()
    }

    override fun close() {
        loadedTrack = null
        lifecycle.close()
    }

    private fun syncState() = publishEngineState(engine.state.value)

    private fun publishEngineState(engineState: moe.rukamori.archivetune.playback.ios.IosEngineState) {
        val current = loadedTrack?.takeIf { it.id == engineState.itemId }
        mutableState.value = PlaybackState(
            track = current,
            isPlaying = engineState.isPlaying,
            isBuffering = engineState.isBuffering,
            positionMs = engineState.positionMs,
            durationMs = engineState.durationMs,
            ended = engineState.ended,
            error = engineState.error?.message,
        )
    }
}

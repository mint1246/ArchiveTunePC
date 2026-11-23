package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.api.PlaybackParameterPersistence
import moe.rukamori.archivetune.playback.api.PlaybackQueue
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
    ) : this(
        catalog = catalog,
        player = IosEnginePlayerBridge(engine, scope),
        scope = scope,
        lyricsController = lyricsController,
        libraryController = libraryController,
        onShareTrack = onShareTrack,
        social = social,
        news = news,
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
        DiscoveryContentCache<List<MusicCatalogItem>>(
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

    val canShare: Boolean get() = onShareTrack != null
    val canAdjustVolume: Boolean get() = player.canAdjustVolume
    val library: StateFlow<MusicLibraryState>? = libraryController?.state
    val lyrics: StateFlow<LyricsState> = lyricsController?.state ?: MutableStateFlow(LyricsState())
    private val mutableHome = MutableStateFlow(MusicHomeState())
    val home: StateFlow<MusicHomeState> = mutableHome.asStateFlow()
    private var homeJob: Job? = null
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
    private var searchGeneration = 0
    private var playbackGeneration = 0
    private var endedWithoutAdvanceGeneration: Int? = null

    init {
        lyricsController?.let { lifecycle.register(it) }
        social?.let { lifecycle.register(it) }
        lifecycle.register(player)
        lifecycle.register(catalog)

        libraryController?.let { saved ->
            applicationScope.launch {
                saved.awaitLoaded()
                if (!lifecycle.isClosed && playbackGeneration == 0) {
                    val restoredQueue = saved.state.value.queue
                    val restoredIndex = saved.state.value.queueIndex
                    playbackQueue.replace(restoredQueue.map(MusicTrack::toPlaybackItem), restoredIndex.coerceAtLeast(0))
                    syncRemoteNavigationAvailability()
                    mutableState.update { it.copy(queue = restoredQueue, queueIndex = restoredIndex) }
                }
            }
        }
        applicationScope.launch {
            var wasEnded = false
            var lyricsTrack: MusicTrack? = null
            playback.collect { transport ->
                if (transport.isPlaying) transport.track?.let { libraryController?.recordPlay(it) }
                if (transport.track != lyricsTrack) {
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
        mutableHome.update { it.copy(loading = true, error = null) }
        val generation = ++homeGeneration
        homeJob = applicationScope.launch {
            try {
                val selected = home.value.selectedChip
                val shelves = if (selected == null) catalog.home()
                else catalog.browse(selected.browseId, selected.params).shelves
                val chips = catalog.homeChips()
                if (!lifecycle.isClosed && generation == homeGeneration) mutableHome.value = MusicHomeState(
                    shelves = shelves, chips = chips, selectedChip = selected, loaded = true,
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
                throw cancelled
            } catch (_: Exception) {
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
                searchItems = cached ?: it.searchItems,
                results = cached?.mapNotNull { item -> (item as? MusicCatalogItem.Track)?.track } ?: it.results,
                searching = normalized.isNotEmpty(),
                error = null,
                failedPlayback = false,
            )
        }
        if (normalized.isEmpty()) {
            mutableState.update { it.copy(results = emptyList(), searchItems = emptyList(), searching = false) }
            return
        }
        if (cached != null) {
            mutableState.update { it.copy(searching = false) }
            return
        }
        searchJob = applicationScope.launch {
            try {
                val results = searchCache.getOrLoad(cacheKey) { catalog.searchItems(normalized, params).take(60) }
                if (lifecycle.isClosed || generation != searchGeneration) return@launch
                mutableState.update {
                    it.copy(
                        searchItems = results,
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
                val source = catalog.resolve(track)
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == playbackGeneration) mutableState.update {
                    it.copy(
                        resolvingTrackId = null,
                        failedPlayback = true,
                        error = (failure as? CatalogException)?.message ?: "This song could not be played. Try another song or retry.",
                    )
                }
            }
        }
    }

    fun shareCurrentTrack() { if (!lifecycle.isClosed) playback.value.track?.let { onShareTrack?.invoke(it) } }

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

    fun toggleFavorite(track: MusicTrack) { if (!lifecycle.isClosed) libraryController?.toggleFavorite(track) }

    fun setTheme(theme: MusicThemeChoice) { if (!lifecycle.isClosed) libraryController?.setTheme(theme) }

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
            playAt(state.value.queueIndex)
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

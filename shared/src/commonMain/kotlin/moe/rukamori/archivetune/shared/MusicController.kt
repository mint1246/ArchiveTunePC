package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** Application-owned services: navigation and screen locking never dispose the player. */
class MusicController(
    private val catalog: MusicCatalog,
    private val player: PlayerEngine,
    scope: CoroutineScope,
    private val lyricsController: LyricsController? = null,
    private val libraryController: MusicLibrary? = null,
    private val onShareTrack: ((MusicTrack) -> Unit)? = null,
    val social: MusicSocialController? = null,
    val news: MusicNewsController? = null,
) {
    private val jobs = SupervisorJob(scope.coroutineContext[Job])
    private val applicationScope = CoroutineScope(scope.coroutineContext + jobs)
    private val mutableState = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = mutableState.asStateFlow()
    val playback: StateFlow<PlaybackState> = player.state
    private val mutablePlaybackOptions = MutableStateFlow(MusicPlaybackOptions())
    val playbackOptions: StateFlow<MusicPlaybackOptions> = mutablePlaybackOptions.asStateFlow()
    private var shuffledIndices = emptyList<Int>()
    private var sleepTimerJob: Job? = null
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
    private val browseCache = linkedMapOf<String, MusicBrowsePage>()
    private val browseBackStack = mutableListOf<MusicBrowseState>()
    private val mutableDiscovery = MutableStateFlow(MusicDiscoveryState())
    val discovery: StateFlow<MusicDiscoveryState> = mutableDiscovery.asStateFlow()
    private var discoveryJob: Job? = null
    private val searches = linkedMapOf<String, List<MusicCatalogItem>>()
    private var suggestionJob: Job? = null
    private var suggestionGeneration = 0
    private var searchJob: Job? = null
    private var resolveJob: Job? = null
    private var searchGeneration = 0
    private var playbackGeneration = 0
    private var endedWithoutAdvanceGeneration: Int? = null
    private var closed = false

    init {
        libraryController?.let { saved ->
            applicationScope.launch {
                saved.awaitLoaded()
                if (!closed && playbackGeneration == 0) {
                    mutableState.update { it.copy(queue = saved.state.value.queue, queueIndex = saved.state.value.queueIndex) }
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
                if (!closed && transport.ended && !wasEnded &&
                    transport.track?.id == state.value.queue.getOrNull(state.value.queueIndex)?.id &&
                    state.value.resolvingTrackId == null
                ) {
                    if (playbackOptions.value.sleepAfterCurrent) {
                        endedWithoutAdvanceGeneration = playbackGeneration
                        player.pause()
                        mutablePlaybackOptions.update { it.copy(sleepAfterCurrent = false) }
                    } else if (playbackOptions.value.repeatMode == MusicRepeatMode.ONE) {
                        player.seekTo(0); player.play()
                    } else next()
                }
                wasEnded = transport.ended
            }
        }
    }

    /** Home survives route changes; refresh retains visible shelves and coalesces requests. */
    fun loadHome(force: Boolean = false) {
        if (closed || homeJob?.isActive == true || (!force && home.value.loaded)) return
        mutableHome.update { it.copy(loading = true, error = null) }
        val generation = ++homeGeneration
        homeJob = applicationScope.launch {
            try {
                val selected = home.value.selectedChip
                val shelves = if (selected == null) catalog.home()
                    else catalog.browse(selected.browseId, selected.params).shelves
                val chips = catalog.homeChips()
                if (!closed && generation == homeGeneration) mutableHome.value = MusicHomeState(
                    shelves = shelves, chips = chips, selectedChip = selected, loaded = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && generation == homeGeneration) mutableHome.update {
                    it.copy(loading = false, error = "Home could not connect. Try again.")
                }
            }
        }
    }

    fun selectHomeChip(chip: MusicHomeChip) {
        if (closed) return
        homeJob?.cancel()
        ++homeGeneration
        mutableHome.update { it.copy(selectedChip = chip.takeUnless { it == home.value.selectedChip }) }
        loadHome(force = true)
    }

    /** Bounded detail cache and generation checks preserve route state and cancel obsolete loads. */
    fun openBrowse(item: MusicCatalogItem.Browse) {
        if (closed) return
        browseJob?.cancel()
        val previous = browse.value
        val isNewDetail = previous.item != null && (previous.item.id != item.id || previous.item.params != item.params)
        val depth = if (previous.item == null) 1 else previous.depth + if (isNewDetail) 1 else 0
        if (isNewDetail) {
            browseBackStack += previous
            if (browseBackStack.size > 8) browseBackStack.removeAt(0)
        }
        val generation = ++browseGeneration
        val key = item.id + ":" + item.params.orEmpty()
        val cached = browseCache.remove(key)?.also { browseCache[key] = it }
        mutableBrowse.value = MusicBrowseState(item = item, page = cached, loading = cached == null, depth = depth)
        if (cached != null) return
        browseJob = applicationScope.launch {
            try {
                val page = catalog.browse(item.id, item.params)
                if (closed || generation != browseGeneration) return@launch
                browseCache[key] = page
                while (browseCache.size > 8) browseCache.remove(browseCache.keys.first())
                mutableBrowse.value = MusicBrowseState(item = item, page = page, depth = depth)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && generation == browseGeneration) mutableBrowse.update {
                    it.copy(loading = false, error = "This page could not connect. Try again.")
                }
            }
        }
    }

    fun playBrowse(item: MusicCatalogItem.Browse) {
        if (closed) return
        playlistPlayJob?.cancel()
        val expected = ++playbackGeneration
        resolveJob?.cancel()
        player.pause()
        mutableState.update { it.copy(resolvingTrackId = item.id, error = null) }
        playlistPlayJob = applicationScope.launch {
            try {
                val page = catalog.browse(item.id, item.params)
                if (closed || playbackGeneration != expected) return@launch
                if (page.tracks.isEmpty()) error("Empty playlist")
                playAt(0, page.tracks)
                mutableState.update { it.copy(queueTitle = page.title.ifBlank { item.title }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && playbackGeneration == expected) mutableState.update {
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
        if (closed || discoveryJob?.isActive == true || (!force && discovery.value.loaded)) return
        mutableDiscovery.update { it.copy(loading = true, error = null) }
        discoveryJob = applicationScope.launch {
            try {
                val data = catalog.discovery()
                if (closed) return@launch
                mutableDiscovery.value = MusicDiscoveryState(data = data, loaded = true)
                // Like Android, show discovery immediately; bounded artwork requests fill visible cards.
                data.moods.take(24).chunked(3).forEach { batch ->
                    coroutineScope {
                        batch.map { mood -> async {
                            val url = try { catalog.moodArtwork(mood) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            if (url != null && !closed) mutableDiscovery.update { current ->
                                current.copy(data = current.data.copy(moods = current.data.moods.map {
                                    if (it.endpoint == mood.endpoint) it.copy(artworkUrl = url) else it
                                }))
                            }
                        } }.awaitAll()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed) mutableDiscovery.update { it.copy(loading = false, error = "Search discovery could not connect. Try again.") }
            }
        }
    }

    fun suggest(query: String) {
        if (closed) return
        suggestionJob?.cancel()
        val generation = ++suggestionGeneration
        val input = query.trim().take(200)
        mutableState.update { it.copy(suggestions = emptyList()) }
        if (input.isEmpty()) return
        suggestionJob = applicationScope.launch {
            try {
                val suggestions = catalog.searchSuggestions(input)
                if (!closed && generation == suggestionGeneration) mutableState.update { it.copy(suggestions = suggestions) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A suggestion failure never replaces results or prevents search. */ }
        }
    }

    fun search(query: String, params: String? = null) {
        if (closed) return
        val normalized = query.trim().take(200)
        val generation = ++searchGeneration
        if (normalized.isNotEmpty()) libraryController?.recordSearch(normalized)
        searchJob?.cancel()
        val cacheKey = normalized + "\u0000" + params.orEmpty()
        val cached = searches.remove(cacheKey)?.also { searches[cacheKey] = it }
        mutableState.update {
            it.copy(query = query, searchItems = cached ?: it.searchItems, results = cached?.mapNotNull { (it as? MusicCatalogItem.Track)?.track } ?: it.results, searching = normalized.isNotEmpty(), error = null, failedPlayback = false)
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
                val results = catalog.searchItems(normalized, params).take(60)
                if (closed || generation != searchGeneration) return@launch
                searches[cacheKey] = results
                while (searches.size > 12) searches.remove(searches.keys.first())
                mutableState.update { it.copy(searchItems = results, results = results.mapNotNull { (it as? MusicCatalogItem.Track)?.track }, searching = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == searchGeneration) mutableState.update {
                    it.copy(searching = false, error = (failure as? CatalogException)?.message ?: "Search could not connect. Please try again.")
                }
            }
        }
    }

    /** Start the click immediately; fill its radio queue without replacing or delaying audio. */
    fun playTrack(track: MusicTrack) {
        if (closed) return
        playAt(0, listOf(track))
        val generation = playbackGeneration
        radioQueueJob = applicationScope.launch {
            try {
                val queue = catalog.radioQueue(track)
                if (closed || generation != playbackGeneration) return@launch
                val index = queue.tracks.indexOfFirst { it.id == track.id }
                val tracks = if (index >= 0) queue.tracks else listOf(track)
                val selected = index.coerceAtLeast(0)
                mutableState.update { it.copy(queue = tracks, queueIndex = selected, queueTitle = queue.title) }
                libraryController?.saveQueue(tracks, selected)
                if (playbackOptions.value.shuffle) shuffledIndices = listOf(selected) + tracks.indices.filter { it != selected }.shuffled()
                if (playback.value.ended && state.value.resolvingTrackId == null && !playbackOptions.value.sleepAfterCurrent && endedWithoutAdvanceGeneration != generation) next()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The explicitly selected song remains playable on a queue failure. */ }
        }
    }

    /** The clicked list position wins, including duplicate songs in the queue. */
    fun playAt(index: Int, tracks: List<MusicTrack> = state.value.queue) {
        if (closed) return
        val track = tracks.getOrNull(index) ?: return
        playlistPlayJob?.cancel()
        radioQueueJob?.cancel()
        if (tracks != state.value.queue && playbackOptions.value.shuffle) {
            shuffledIndices = listOf(index) + tracks.indices.filter { it != index }.shuffled()
        }
        val generation = ++playbackGeneration
        endedWithoutAdvanceGeneration = null
        if (playback.value.track?.id != track.id) mutablePlaybackOptions.update { it.copy(sleepAfterCurrent = false) }
        resolveJob?.cancel()
        player.pause()
        mutableState.update {
            it.copy(queue = tracks.toList(), queueIndex = index,
                queueTitle = if (it.queue == tracks) it.queueTitle else null,
                resolvingTrackId = track.id, error = null, failedPlayback = false)
        }
        libraryController?.saveQueue(tracks, index)
        resolveJob = applicationScope.launch {
            try {
                val source = catalog.resolve(track)
                if (closed || generation != playbackGeneration) return@launch
                require(source.trackId == track.id) { "Resolved track does not match the selected song" }
                player.load(track, source)
                player.setSpeed(playbackOptions.value.speed)
                if (player.canAdjustVolume) player.setVolume(playbackOptions.value.volume)
                player.play()
                mutableState.update { it.copy(resolvingTrackId = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == playbackGeneration) mutableState.update {
                    it.copy(resolvingTrackId = null, failedPlayback = true, error = (failure as? CatalogException)?.message ?: "This song could not be played. Try another song or retry.")
                }
            }
        }
    }

    fun shareCurrentTrack() { if (!closed) playback.value.track?.let { onShareTrack?.invoke(it) } }

    /** Null cancels; a timer belongs to application playback, never to the player sheet. */
    fun setSleepTimer(minutes: Int?) {
        if (closed) return
        sleepTimerJob?.cancel()
        val duration = minutes?.coerceIn(1, 180)
        mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = duration, sleepAfterCurrent = false) }
        if (duration != null) sleepTimerJob = applicationScope.launch {
            delay(duration * 60_000L)
            player.pause()
            mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = null) }
        }
    }

    fun setSleepAfterCurrent() {
        if (closed) return
        sleepTimerJob?.cancel()
        mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = null, sleepAfterCurrent = true) }
    }

    fun playShuffled(tracks: List<MusicTrack>) {
        if (closed || tracks.isEmpty()) return
        if (!playbackOptions.value.shuffle) toggleShuffle()
        val index = tracks.indices.random()
        shuffledIndices = listOf(index) + tracks.indices.filter { it != index }.shuffled()
        playAt(index, tracks)
    }

    fun toggleShuffle() {
        if (closed) return
        val enabled = !playbackOptions.value.shuffle
        mutablePlaybackOptions.update { it.copy(shuffle = enabled) }
        val current = state.value.queueIndex
        shuffledIndices = if (enabled) listOfNotNull(current.takeIf { it in state.value.queue.indices }) +
            state.value.queue.indices.filter { it != current }.shuffled() else emptyList()
    }

    fun cycleRepeat() {
        if (!closed) mutablePlaybackOptions.update {
            it.copy(repeatMode = MusicRepeatMode.entries[(it.repeatMode.ordinal + 1) % MusicRepeatMode.entries.size])
        }
    }

    fun setSpeed(speed: Float) {
        if (closed || !speed.isFinite()) return
        val value = speed.coerceIn(0.25f, 2f)
        player.setSpeed(value)
        mutablePlaybackOptions.update { it.copy(speed = value) }
    }

    fun setVolume(volume: Float) {
        if (closed || !player.canAdjustVolume || !volume.isFinite()) return
        val value = volume.coerceIn(0f, 1f)
        player.setVolume(value)
        mutablePlaybackOptions.update { it.copy(volume = value) }
    }

    fun next() {
        if (closed) return
        val state = state.value
        val order = if (playbackOptions.value.shuffle) shuffledIndices else state.queue.indices.toList()
        val position = order.indexOf(state.queueIndex)
        val target = order.getOrNull(position + 1)
            ?: order.firstOrNull()?.takeIf { playbackOptions.value.repeatMode == MusicRepeatMode.ALL }
        if (target != null) playAt(target)
    }

    /** Thumbnail swipes follow the shuffle timeline with repeat disabled, as on Android. */
    fun thumbnailNeighbor(direction: Int): Int? {
        val current = state.value
        val order = if (playbackOptions.value.shuffle) shuffledIndices else current.queue.indices.toList()
        return order.getOrNull(order.indexOf(current.queueIndex) + direction)
    }

    fun skipToPreviousItem() {
        if (!closed) thumbnailNeighbor(-1)?.let { playAt(it) }
    }

    fun previous() {
        if (closed) return
        if (playback.value.positionMs > 3_000) seekTo(0)
        else {
            val current = state.value
            val order = if (playbackOptions.value.shuffle) shuffledIndices else current.queue.indices.toList()
            val position = order.indexOf(current.queueIndex)
            val target = order.getOrNull(position - 1)
                ?: if (playbackOptions.value.repeatMode == MusicRepeatMode.ALL) order.lastOrNull() else order.firstOrNull()
            if (target != null) playAt(target)
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (closed || from == to) return
        val current = state.value
        if (from !in current.queue.indices || to !in current.queue.indices) return
        val queue = current.queue.toMutableList().apply { add(to, removeAt(from)) }
        fun movedIndex(index: Int): Int = when {
            index == from -> to
            from < to && index in (from + 1)..to -> index - 1
            to < from && index in to until from -> index + 1
            else -> index
        }
        val selected = movedIndex(current.queueIndex)
        shuffledIndices = shuffledIndices.map(::movedIndex)
        mutableState.update { it.copy(queue = queue, queueIndex = selected) }
        libraryController?.saveQueue(queue, selected)
    }

    fun removeQueueItem(index: Int) {
        if (closed || index !in state.value.queue.indices) return
        val current = state.value
        val queue = current.queue.filterIndexed { i, _ -> i != index }
        if (queue.isEmpty()) {
            resolveJob?.cancel(); ++playbackGeneration; player.stop()
            mutableState.update { it.copy(queue = emptyList(), queueIndex = -1, resolvingTrackId = null) }
            libraryController?.saveQueue(emptyList(), -1)
        } else if (index == current.queueIndex) playAt(index.coerceAtMost(queue.lastIndex), queue)
        else {
            val selected = current.queueIndex - if (index < current.queueIndex) 1 else 0
            mutableState.update { it.copy(queue = queue, queueIndex = selected) }
            libraryController?.saveQueue(queue, selected)
            if (playbackOptions.value.shuffle) shuffledIndices = listOf(selected) + queue.indices.filter { it != selected }.shuffled()
        }
    }

    fun dismissPlayback() {
        if (closed) return
        ++playbackGeneration
        resolveJob?.cancel(); playlistPlayJob?.cancel(); radioQueueJob?.cancel()
        sleepTimerJob?.cancel()
        player.stop()
        shuffledIndices = emptyList()
        mutablePlaybackOptions.update { it.copy(sleepTimerMinutes = null, sleepAfterCurrent = false) }
        mutableState.update { it.copy(queue = emptyList(), queueIndex = -1, queueTitle = null,
            resolvingTrackId = null, error = null, failedPlayback = false) }
        libraryController?.saveQueue(emptyList(), -1)
    }

    fun togglePlayback() {
        if (closed || state.value.resolvingTrackId != null) return
        if (state.value.failedPlayback || playback.value.error != null) {
            retryPlayback()
            return
        }
        if (playback.value.isPlaying || playback.value.isBuffering) player.pause()
        else {
            if (playback.value.ended) player.seekTo(0)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (!closed && state.value.resolvingTrackId == null) player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun toggleFavorite(track: MusicTrack) { if (!closed) libraryController?.toggleFavorite(track) }

    fun setTheme(theme: MusicThemeChoice) { if (!closed) libraryController?.setTheme(theme) }

    fun removeSearchHistory(query: String) { if (!closed) libraryController?.removeSearch(query) }

    fun createPlaylist(name: String): String? = if (closed) null else libraryController?.createPlaylist(name)
    fun renamePlaylist(id: String, name: String) { if (!closed) libraryController?.renamePlaylist(id, name) }
    fun deletePlaylist(id: String) { if (!closed) libraryController?.deletePlaylist(id) }
    fun addToPlaylist(id: String, track: MusicTrack) { if (!closed) libraryController?.addToPlaylist(id, track) }
    fun removeFromPlaylist(id: String, index: Int) { if (!closed) libraryController?.removeFromPlaylist(id, index) }

    suspend fun flushLibrary() { libraryController?.flush() }

    fun retryLibrarySave() {
        if (!closed) applicationScope.launch { try { libraryController?.flush() } catch (_: Exception) {} }
    }

    fun retryLyrics() { if (!closed) lyricsController?.select(playback.value.track, refresh = true) }
    fun searchLyricsVersions(title: String, artist: String, album: String? = null) {
        if (!closed) lyricsController?.searchVersions(title, artist, album)
    }
    fun selectLyricsVersion(version: LyricsVersion) { if (!closed) lyricsController?.selectVersion(version) }

    fun retryPlayback() {
        if (!closed && (state.value.failedPlayback || playback.value.error != null)) playAt(state.value.queueIndex)
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    /** Await this at application teardown when a persistent library was supplied. */
    suspend fun closeAndFlush() {
        close()
        libraryController?.close()
    }

    fun close() {
        if (closed) return
        closed = true
        ++searchGeneration
        ++playbackGeneration
        applicationScope.cancel()
        searches.clear()
        browseCache.clear()
        browseBackStack.clear()
        lyricsController?.close()
        player.close()
        catalog.close()
    }
}

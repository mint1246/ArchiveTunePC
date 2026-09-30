package moe.rukamori.archivetune.desktop.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.chords.ChordRequest
import moe.rukamori.archivetune.chords.ChordTrack
import moe.rukamori.archivetune.chords.ChordUnavailableException
import moe.rukamori.archivetune.chords.SongsterrChordClient
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.LyricsDocument
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchResult
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus
import moe.rukamori.archivetune.lyrics.LyricsRequest
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot

sealed interface DesktopLyricsState {
    data object Idle : DesktopLyricsState
    data class Loading(val itemId: String) : DesktopLyricsState
    data class Ready(
        val itemId: String,
        val providerName: String,
        val timeline: LyricsTimeline,
        val activeLineIndex: Int,
    ) : DesktopLyricsState
    data class Empty(val itemId: String, val message: String = "No lyrics found for this track") : DesktopLyricsState
    data class Error(val itemId: String, val message: String) : DesktopLyricsState
}

sealed interface DesktopChordState {
    data object Idle : DesktopChordState
    data class Loading(val itemId: String) : DesktopChordState
    data class Ready(val itemId: String, val track: ChordTrack) : DesktopChordState
    data class Empty(val itemId: String, val message: String = "No chords found for this track") : DesktopChordState
    data class Error(val itemId: String, val message: String = "Chords could not be loaded") : DesktopChordState
}

data class DesktopLyricsSearchState(
    val itemId: String? = null,
    val request: LyricsRequest? = null,
    val searching: Boolean = false,
    val results: List<LyricsProviderSearchResult> = emptyList(),
    val message: String? = null,
)

sealed interface DesktopLyricsTranslationState {
    data object Idle : DesktopLyricsTranslationState
    data class Loading(val itemId: String, val targetLanguage: String) : DesktopLyricsTranslationState
    data class Ready(val itemId: String, val targetLanguage: String, val lines: List<String>) : DesktopLyricsTranslationState
    data class Error(val itemId: String, val message: String) : DesktopLyricsTranslationState
}

/** Implemented by the desktop composition root, which supplies current settings and secure credentials. */
fun interface DesktopLyricsTranslator {
    suspend fun translate(
        itemId: String,
        document: LyricsDocument,
        sourceLines: List<String>,
        targetLanguage: String,
    ): List<String>
}

/**
 * Bridges the real playback StateFlow to JvmLyricsClient. collectLatest cancels a pending provider
 * request immediately when the track changes. Cache entries are documents from real providers only.
 */
class DesktopLyricsPresenter(
    private val playback: PlaybackController,
    lyricsClient: JvmLyricsClient,
    private val scope: CoroutineScope,
    cacheSize: Int = 24,
    initiallyActive: Boolean = true,
    private val chordClient: SongsterrChordClient = SongsterrChordClient(),
    private val localChordAnalyzer: (suspend (PlaybackItem, ChordRequest) -> Result<ChordTrack>)? = null,
    private val lyricsTranslator: DesktopLyricsTranslator? = null,
    private val translationTargetLanguage: () -> String = { "en" },
) : AutoCloseable {
    val canTranslate: Boolean get() = lyricsTranslator != null
    @Volatile private var lyricsClient: JvmLyricsClient = lyricsClient

    private val cache = object : LinkedHashMap<String, LyricsDocument>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsDocument>?) = size > cacheSize
    }
    private val _state = MutableStateFlow<DesktopLyricsState>(DesktopLyricsState.Idle)
    val state: StateFlow<DesktopLyricsState> = _state.asStateFlow()
    private val _searchState = MutableStateFlow(DesktopLyricsSearchState())
    val searchState: StateFlow<DesktopLyricsSearchState> = _searchState.asStateFlow()
    private val _translationState = MutableStateFlow<DesktopLyricsTranslationState>(DesktopLyricsTranslationState.Idle)
    val translationState: StateFlow<DesktopLyricsTranslationState> = _translationState.asStateFlow()
    private val chordCache = object : LinkedHashMap<String, ChordTrack>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChordTrack>?) = size > cacheSize
    }
    private val _chordState = MutableStateFlow<DesktopChordState>(DesktopChordState.Idle)
    val chordState: StateFlow<DesktopChordState> = _chordState.asStateFlow()
    /** Exposed for the desktop surface to render artwork and playback-aware chrome. */
    val playbackState: StateFlow<PlaybackSnapshot> = playback.state
    private val _positionMs = MutableStateFlow(0L)
    /** Hot position stream for the SpicyLyrics-style animator (per-frame consumer). */
    val positionMsFlow: StateFlow<Long> = _positionMs.asStateFlow()
    private var searchJob: Job? = null
    private var chordJob: Job? = null
    private var translationJob: Job? = null
    private var selectedDocument: LyricsDocument? = null
    private val loadGeneration = AtomicLong()
    private val chordGeneration = AtomicLong()
    private val translationGeneration = AtomicLong()
    private val active = MutableStateFlow(initiallyActive)
    private val itemJob: Job = scope.launch {
        active.combine(
            playback.state.map { it.currentItem }.distinctUntilChanged(),
        ) { isActive, item -> isActive to item }
            .collectLatest { (isActive, item) ->
                if (!isActive) {
                    loadGeneration.incrementAndGet()
                    searchJob?.cancel()
                    chordJob?.cancel()
                    cancelTranslation()
                    return@collectLatest
                }
                if (item == null) {
                    loadGeneration.incrementAndGet()
                    chordGeneration.incrementAndGet()
                    searchJob?.cancel()
                    chordJob?.cancel()
                    cancelTranslation()
                    selectedDocument = null
                    _state.value = DesktopLyricsState.Idle
                    _searchState.value = DesktopLyricsSearchState()
                    _chordState.value = DesktopChordState.Idle
                    return@collectLatest
                }
                if (item.metadata.isPodcast) {
                    loadGeneration.incrementAndGet()
                    chordGeneration.incrementAndGet()
                    searchJob?.cancel()
                    chordJob?.cancel()
                    cancelTranslation()
                    selectedDocument = null
                    _state.value = DesktopLyricsState.Idle
                    _searchState.value = DesktopLyricsSearchState(item.metadata.id)
                    _chordState.value = DesktopChordState.Idle
                    return@collectLatest
                }
                val previousItemId = _searchState.value.itemId
                if (previousItemId != item.metadata.id) {
                    chordGeneration.incrementAndGet()
                    chordJob?.cancel()
                    _chordState.value = DesktopChordState.Idle
                }
                searchJob?.cancel()
                cancelTranslation()
                selectedDocument = null
                _searchState.value = DesktopLyricsSearchState(item.metadata.id, item.toLyricsRequestOrNull())
                load(item, playback.state.value.positionMs, loadGeneration.incrementAndGet())
            }
    }
    private val positionJob: Job = scope.launch {
        playback.state.collectLatest { snapshot ->
            _positionMs.value = snapshot.positionMs
        }
    }

    private suspend fun load(item: PlaybackItem, positionMs: Long, generation: Long) {
        val id = item.metadata.id
        if (generation != loadGeneration.get()) return
        _state.value = DesktopLyricsState.Loading(id)
        val request = item.toLyricsRequestOrNull()
        if (request == null) {
            if (generation == loadGeneration.get()) _state.value = DesktopLyricsState.Empty(id, "Lyrics need a song title and artist metadata")
            return
        }
        synchronized(cache) { cache[id] }?.let { cached ->
            if (generation == loadGeneration.get()) showDocument(id, cached, positionMs)
            return
        }

        val results = try {
            lyricsClient.searchLyrics(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (generation == loadGeneration.get()) {
                _state.value = DesktopLyricsState.Error(id, "Lyrics providers could not be reached")
            }
            return
        }
        if (generation != loadGeneration.get()) return
        _searchState.value = _searchState.value.copy(
            searching = false,
            results = results,
            message = if (results.none { it.document != null }) "No provider found lyrics for this search" else null,
        )
        val document = results.asSequence()
            .mapNotNull(LyricsProviderSearchResult::document)
            .firstOrNull { LyricsTimelineParser.parse(it).lines.isNotEmpty() }
        if (document != null) {
            synchronized(cache) { cache[id] = document }
            showDocument(id, document, positionMs)
        } else if (results.any { it.document != null }) {
            _state.value = DesktopLyricsState.Error(id, "Lyrics were returned but could not be rendered")
        } else if (results.any { it.status == LyricsProviderSearchStatus.FAILED || it.status == LyricsProviderSearchStatus.TIMED_OUT }) {
            _state.value = DesktopLyricsState.Error(id, "Lyrics providers could not be reached")
        } else {
            _state.value = DesktopLyricsState.Empty(id)
        }
    }

    private fun showDocument(itemId: String, document: LyricsDocument, positionMs: Long): Boolean {
        val timeline = LyricsTimelineParser.parse(document)
        selectedDocument = document
        _state.value = if (timeline.lines.isEmpty()) {
            DesktopLyricsState.Error(itemId, "${document.providerName} returned lyrics that could not be rendered")
        } else {
            DesktopLyricsState.Ready(
                itemId = itemId,
                providerName = document.providerName,
                timeline = timeline,
                activeLineIndex = timeline.activeLineIndex(positionMs),
            )
        }
        return timeline.lines.isNotEmpty()
    }

    /** Re-runs provider lookup for the current item after an Empty/Error state. */
    fun retry() {
        if (!active.value) return
        val item = playback.state.value.currentItem ?: return
        val generation = loadGeneration.incrementAndGet()
        scope.launch { load(item, playback.state.value.positionMs, generation) }
    }

    /** Replaces the provider set after settings or secure credentials change, then reloads lyrics. */
    fun updateLyricsClient(client: JvmLyricsClient) {
        if (client === lyricsClient) return
        lyricsClient = client
        synchronized(cache) { cache.clear() }
        searchJob?.cancel()
        searchJob = null
        cancelTranslation()
        retry()
    }

    /**
     * Chords are intentionally lazy: opening lyrics does not touch Songsterr. The first chord-button
     * press resolves the current track, then the result is cached for the presenter lifetime.
     */
    fun loadChords() {
        if (!active.value) return
        val snapshot = playback.state.value
        val item = snapshot.currentItem ?: return
        if (item.metadata.isPodcast) return
        val itemId = item.metadata.id
        synchronized(chordCache) { chordCache[itemId] }?.let { cached ->
            _chordState.value = DesktopChordState.Ready(itemId, cached)
            return
        }
        if ((_chordState.value as? DesktopChordState.Loading)?.itemId == itemId) return
        val request = item.toChordRequestOrNull(snapshot.durationMs)
        if (request == null) {
            _chordState.value = DesktopChordState.Empty(itemId, "Chords need a song title and artist metadata")
            return
        }
        val generation = chordGeneration.incrementAndGet()
        chordJob?.cancel()
        _chordState.value = DesktopChordState.Loading(itemId)
        chordJob = scope.launch {
            // Human-authored Songsterr labels remain the best source. If they are absent, prefer
            // the local whole-mix neural recognizer before the older per-tab-note heuristic.
            val authored = chordClient.getChords(request, allowDerived = false)
            if (generation != chordGeneration.get() || playback.state.value.currentItem?.metadata?.id != itemId) return@launch
            val result =
                authored.getOrNull()?.let(Result.Companion::success)
                    ?: localChordAnalyzer
                        ?.invoke(item, request)
                        ?.takeIf { it.isSuccess }
                    ?: chordClient.getChords(request, allowDerived = true)
            if (generation != chordGeneration.get() || playback.state.value.currentItem?.metadata?.id != itemId) return@launch
            result.fold(
                onSuccess = { track ->
                    synchronized(chordCache) { chordCache[itemId] = track }
                    _chordState.value = DesktopChordState.Ready(itemId, track)
                },
                onFailure = { failure ->
                    _chordState.value =
                        if (failure is ChordUnavailableException) {
                            DesktopChordState.Empty(itemId, failure.message ?: "No usable Songsterr chords were found")
                        } else {
                            DesktopChordState.Error(itemId, "Songsterr could not be reached")
                        }
                },
            )
        }
    }

    /** Replaces the current chord result with ArchiveTune's local neural analysis on demand. */
    fun loadChordsFromLocalModel() {
        if (!active.value) return
        val analyzer = localChordAnalyzer ?: return
        val snapshot = playback.state.value
        val item = snapshot.currentItem ?: return
        if (item.metadata.isPodcast) return
        val itemId = item.metadata.id
        val request = item.toChordRequestOrNull(snapshot.durationMs)
        if (request == null) {
            _chordState.value = DesktopChordState.Empty(itemId, "Chords need a song title and artist metadata")
            return
        }

        val generation = chordGeneration.incrementAndGet()
        chordJob?.cancel()
        _chordState.value = DesktopChordState.Loading(itemId)
        chordJob = scope.launch {
            val result = analyzer(item, request)
            if (generation != chordGeneration.get() || playback.state.value.currentItem?.metadata?.id != itemId) return@launch
            result.fold(
                onSuccess = { track ->
                    synchronized(chordCache) { chordCache[itemId] = track }
                    _chordState.value = DesktopChordState.Ready(itemId, track)
                },
                onFailure = { failure ->
                    _chordState.value = DesktopChordState.Error(
                        itemId,
                        failure.message?.takeIf(String::isNotBlank) ?: "Local chord model could not analyze this track",
                    )
                },
            )
        }
    }

    fun search(request: LyricsRequest) {
        if (!active.value) return
        val item = playback.state.value.currentItem ?: return
        val itemId = item.metadata.id
        searchJob?.cancel()
        val pending = lyricsClient.providers.map {
            LyricsProviderSearchResult(it.id, it.name, LyricsProviderSearchStatus.SEARCHING)
        }
        _searchState.value = DesktopLyricsSearchState(itemId, request, searching = true, results = pending)
        searchJob = scope.launch {
            supervisorScope {
                lyricsClient.providers.mapIndexed { index, provider ->
                    launch {
                        val result = try {
                            lyricsClient.searchLyrics(request, listOf(provider.id)).single()
                        } catch (cancellation: CancellationException) {
                            // A new track or a closed presenter cancels this whole search job.
                            // Do not turn cancellation into a synthetic provider failure and keep
                            // mutating the UI after the caller has moved on.
                            throw cancellation
                        } catch (_: Exception) {
                            LyricsProviderSearchResult(provider.id, provider.name, LyricsProviderSearchStatus.FAILED)
                        }
                        if (playback.state.value.currentItem?.metadata?.id == itemId) {
                            val current = _searchState.value
                            val updated = current.results.toMutableList().also { if (index in it.indices) it[index] = result }
                            _searchState.value = current.copy(results = updated)
                        }
                    }
                }.joinAll()
            }
            if (playback.state.value.currentItem?.metadata?.id == itemId) {
                val current = _searchState.value
                _searchState.value = current.copy(
                    searching = false,
                    message = if (current.results.none { it.document != null }) "No provider found lyrics for this search" else null,
                )
            }
        }
    }

    fun select(result: LyricsProviderSearchResult): Boolean {
        if (!active.value) return false
        val item = playback.state.value.currentItem ?: return false
        val document = result.document ?: return false
        loadGeneration.incrementAndGet()
        cancelTranslation()
        val rendered = showDocument(item.metadata.id, document, playback.state.value.positionMs)
        if (rendered) synchronized(cache) { cache[item.metadata.id] = document }
        return rendered
    }

    /** Starts a user-requested translation for the lyrics currently displayed. */
    fun translateCurrentLyrics() {
        if (!active.value) return
        val translator = lyricsTranslator ?: return
        val current = _state.value as? DesktopLyricsState.Ready ?: return
        val document = selectedDocument ?: return
        val language = translationTargetLanguage().trim().ifBlank { "en" }
        if ((_translationState.value as? DesktopLyricsTranslationState.Ready)?.let {
                it.itemId == current.itemId && it.targetLanguage.equals(language, ignoreCase = true)
            } == true
        ) return

        translationJob?.cancel()
        val generation = translationGeneration.incrementAndGet()
        _translationState.value = DesktopLyricsTranslationState.Loading(current.itemId, language)
        translationJob = scope.launch {
            try {
                val translated = translator.translate(
                    itemId = current.itemId,
                    document = document,
                    sourceLines = current.timeline.lines.map(TimedLyricLine::text),
                    targetLanguage = language,
                )
                if (generation != translationGeneration.get() || playback.state.value.currentItem?.metadata?.id != current.itemId) return@launch
                if (translated.size != current.timeline.lines.size || translated.any(String::isBlank)) {
                    error("Translation service returned an incomplete translation")
                }
                _translationState.value = DesktopLyricsTranslationState.Ready(current.itemId, language, translated)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (generation == translationGeneration.get() && playback.state.value.currentItem?.metadata?.id == current.itemId) {
                    _translationState.value = DesktopLyricsTranslationState.Error(
                        current.itemId,
                        failure.message?.takeIf(String::isNotBlank) ?: "Lyrics could not be translated",
                    )
                }
            }
        }
    }

    fun cancelTranslation() {
        translationGeneration.incrementAndGet()
        translationJob?.cancel()
        translationJob = null
        _translationState.value = DesktopLyricsTranslationState.Idle
    }

    /** Seeks the current track to a timed lyric line. Unsynchronised lines are intentionally inert. */
    fun seekToLine(index: Int) {
        val ready = _state.value as? DesktopLyricsState.Ready ?: return
        val position = ready.timeline.lines.getOrNull(index)?.startTimeMs ?: return
        scope.launch { playback.seekTo(position) }
    }

    /** Public helper used by the SpicyLyrics-style pane: seek to an arbitrary position in ms. */
    fun seekToMs(positionMs: Long) {
        if (positionMs < 0L) return
        scope.launch { playback.seekTo(positionMs) }
    }

    /**
     * Provider lookup is expensive and opens several HTTP engines. The desktop shell keeps the
     * presenter alive for the full process lifetime, so only do network work while lyrics are
     * actually visible. Re-activating immediately refreshes the current track through the flow
     * above (and serves it from the presenter cache when available).
     */
    fun setActive(isActive: Boolean) {
        active.value = isActive
    }

    override fun close() {
        searchJob?.cancel()
        chordJob?.cancel()
        cancelTranslation()
        itemJob.cancel()
        positionJob.cancel()
    }
}

private fun PlaybackItem.toLyricsRequestOrNull(): LyricsRequest? {
    val title = metadata.title.trim().ifEmpty { metadata.extras["searchTitle"].orEmpty().trim() }
    val artist =
        sequenceOf(
            metadata.artistText,
            metadata.extras["searchArtist"].orEmpty(),
            metadata.extras["albumArtist"].orEmpty(),
            metadata.extras["artist"].orEmpty(),
        ).map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    if (title.isEmpty() || artist.isEmpty()) return null
    return LyricsRequest(
    title = title,
    artist = artist,
    album = metadata.album?.title,
    durationSeconds = metadata.durationHintMs?.div(1_000L)?.toInt() ?: -1,
    videoId = metadata.extras["videoId"] ?: metadata.id,
)
}

private fun PlaybackItem.toChordRequestOrNull(playbackDurationMs: Long): ChordRequest? {
    val title = metadata.title.trim().ifEmpty { metadata.extras["searchTitle"].orEmpty().trim() }
    val artist =
        sequenceOf(
            metadata.artistText,
            metadata.extras["searchArtist"].orEmpty(),
            metadata.extras["albumArtist"].orEmpty(),
            metadata.extras["artist"].orEmpty(),
        ).map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    if (title.isEmpty() || artist.isEmpty()) return null
    return ChordRequest(
        title = title,
        artist = artist,
        durationMs = playbackDurationMs.takeIf { it > 0L } ?: metadata.durationHintMs,
        videoId = metadata.extras["videoId"] ?: metadata.id,
    )
}

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.LyricsDocument
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchResult
import moe.rukamori.archivetune.lyrics.LyricsProviderSearchStatus
import moe.rukamori.archivetune.lyrics.LyricsRequest
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackItem

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

data class DesktopLyricsSearchState(
    val itemId: String? = null,
    val request: LyricsRequest? = null,
    val searching: Boolean = false,
    val results: List<LyricsProviderSearchResult> = emptyList(),
    val message: String? = null,
)

/**
 * Bridges the real playback StateFlow to JvmLyricsClient. collectLatest cancels a pending provider
 * request immediately when the track changes. Cache entries are documents from real providers only.
 */
class DesktopLyricsPresenter(
    private val playback: PlaybackController,
    private val lyricsClient: JvmLyricsClient,
    private val scope: CoroutineScope,
    cacheSize: Int = 24,
) : AutoCloseable {
    private val cache = object : LinkedHashMap<String, LyricsDocument>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsDocument>?) = size > cacheSize
    }
    private val _state = MutableStateFlow<DesktopLyricsState>(DesktopLyricsState.Idle)
    val state: StateFlow<DesktopLyricsState> = _state.asStateFlow()
    private val _searchState = MutableStateFlow(DesktopLyricsSearchState())
    val searchState: StateFlow<DesktopLyricsSearchState> = _searchState.asStateFlow()
    private var searchJob: Job? = null
    private val loadGeneration = AtomicLong()
    private val itemJob: Job = scope.launch {
        playback.state.map { it.currentItem }.distinctUntilChanged().collectLatest { item ->
            if (item == null) {
                loadGeneration.incrementAndGet()
                searchJob?.cancel()
                _state.value = DesktopLyricsState.Idle
                _searchState.value = DesktopLyricsSearchState()
                return@collectLatest
            }
            searchJob?.cancel()
            _searchState.value = DesktopLyricsSearchState(item.metadata.id, item.toLyricsRequestOrNull())
            load(item, playback.state.value.positionMs, loadGeneration.incrementAndGet())
        }
    }
    private val positionJob: Job = scope.launch {
        playback.state.collectLatest { snapshot ->
            val ready = _state.value as? DesktopLyricsState.Ready ?: return@collectLatest
            if (snapshot.currentItem?.metadata?.id == ready.itemId) {
                _state.value = ready.copy(activeLineIndex = ready.timeline.activeLineIndex(snapshot.positionMs))
            }
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
        val item = playback.state.value.currentItem ?: return
        val generation = loadGeneration.incrementAndGet()
        scope.launch { load(item, playback.state.value.positionMs, generation) }
    }

    fun search(request: LyricsRequest) {
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
                        val result = runCatching { lyricsClient.searchLyrics(request, listOf(provider.id)).single() }
                            .getOrElse { LyricsProviderSearchResult(provider.id, provider.name, LyricsProviderSearchStatus.FAILED) }
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
        val item = playback.state.value.currentItem ?: return false
        val document = result.document ?: return false
        loadGeneration.incrementAndGet()
        val rendered = showDocument(item.metadata.id, document, playback.state.value.positionMs)
        if (rendered) synchronized(cache) { cache[item.metadata.id] = document }
        return rendered
    }

    override fun close() {
        searchJob?.cancel()
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

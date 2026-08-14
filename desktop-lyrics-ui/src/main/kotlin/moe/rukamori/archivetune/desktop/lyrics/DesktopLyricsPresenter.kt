package moe.rukamori.archivetune.desktop.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.lyrics.JvmLyricsClient
import moe.rukamori.archivetune.lyrics.LyricsDocument
import moe.rukamori.archivetune.lyrics.LyricsRequest
import moe.rukamori.archivetune.lyrics.LyricsUnavailableException
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
    private val itemJob: Job = scope.launch {
        playback.state.map { it.currentItem }.distinctUntilChanged().collectLatest { item ->
            if (item == null) {
                _state.value = DesktopLyricsState.Idle
                return@collectLatest
            }
            load(item, playback.state.value.positionMs)
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

    private suspend fun load(item: PlaybackItem, positionMs: Long) {
        val id = item.metadata.id
        _state.value = DesktopLyricsState.Loading(id)
        val request = item.toLyricsRequestOrNull()
        if (request == null) {
            _state.value = DesktopLyricsState.Empty(id, "Lyrics need a song title and artist metadata")
            return
        }
        val document = synchronized(cache) { cache[id] } ?: lyricsClient.getLyrics(request).getOrElse { error ->
            _state.value =
                if (error is LyricsUnavailableException && !error.hasProviderFailures) {
                    DesktopLyricsState.Empty(id)
                } else {
                    DesktopLyricsState.Error(id, error.message ?: "Lyrics providers could not be reached")
                }
            return
        }.also { synchronized(cache) { cache[id] = it } }
        val timeline = LyricsTimelineParser.parse(document)
        _state.value = if (timeline.lines.isEmpty()) DesktopLyricsState.Empty(id) else DesktopLyricsState.Ready(
            itemId = id,
            providerName = document.providerName,
            timeline = timeline,
            activeLineIndex = timeline.activeLineIndex(positionMs),
        )
    }

    /** Re-runs provider lookup for the current item after an Empty/Error state. */
    fun retry() {
        val item = playback.state.value.currentItem ?: return
        scope.launch { load(item, playback.state.value.positionMs) }
    }

    override fun close() {
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

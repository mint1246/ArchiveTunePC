package moe.rukamori.archivetune.shared

import kotlinx.coroutines.flow.StateFlow

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
)

/** Ephemeral source: never persist signed URLs or request headers. */
data class ResolvedAudio(
    val trackId: String,
    val url: String,
    val mimeType: String,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "ResolvedAudio(trackId=$trackId, mimeType=$mimeType)"
}

interface MusicCatalog {
    suspend fun searchItems(query: String, params: String?): List<MusicCatalogItem> = searchItems(query)
    suspend fun searchItems(query: String): List<MusicCatalogItem> = search(query).map { MusicCatalogItem.Track(it) }
    suspend fun searchSuggestions(query: String): List<String> = emptyList()
    suspend fun radioQueue(clicked: MusicTrack): MusicRadioQueue = MusicRadioQueue(listOf(clicked))
    suspend fun discovery(): MusicDiscovery = MusicDiscovery()
    suspend fun moodArtwork(mood: MusicMood): String? = mood.artworkUrl
    suspend fun home(): List<MusicShelf> = emptyList()
    suspend fun homeChips(): List<MusicHomeChip> = emptyList()
    suspend fun browse(id: String, params: String? = null): MusicBrowsePage = MusicBrowsePage(title = "")
    suspend fun search(query: String): List<MusicTrack>
    suspend fun resolve(track: MusicTrack): ResolvedAudio
    fun close()
}

data class PlaybackState(
    val track: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ended: Boolean = false,
    val error: String? = null,
)

/** Implementations report actual transport state; commands must run on the main dispatcher. */
interface PlayerEngine {
    val canAdjustVolume: Boolean get() = false
    val state: StateFlow<PlaybackState>
    fun load(track: MusicTrack, source: ResolvedAudio)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float) {}
    fun setVolume(volume: Float) {}
    fun stop()
    fun close()
}

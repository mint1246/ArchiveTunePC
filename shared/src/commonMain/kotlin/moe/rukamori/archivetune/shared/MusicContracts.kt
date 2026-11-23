package moe.rukamori.archivetune.shared

import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.ios.IosPlaybackException

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
)

fun MusicTrack.toPlaybackItem(): PlaybackItem = PlaybackItem(
    metadata = PlaybackMetadata(
        id = id,
        title = title,
        artists = artist.takeIf(String::isNotBlank)?.let { listOf(PlaybackArtist(name = it)) }.orEmpty(),
        artworkUri = artworkUrl,
        durationHintMs = durationMs,
    ),
    source = PlaybackSource.Deferred(id),
)

fun PlaybackItem.toMusicTrack(): MusicTrack = MusicTrack(
    id = metadata.id,
    title = metadata.title,
    artist = metadata.artistText,
    artworkUrl = metadata.artworkUri,
    durationMs = metadata.durationHintMs,
)

/** Ephemeral source: never persist signed URLs or request headers. */
data class ResolvedAudio(
    val trackId: String,
    val url: String,
    val mimeType: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun toResolvedPlaybackSource(): ResolvedPlaybackSource = ResolvedPlaybackSource(
        uri = url,
        mimeType = mimeType,
        headers = headers,
    )

    override fun toString(): String = "ResolvedAudio(trackId=$trackId, mimeType=$mimeType)"
}

interface MusicCatalog : AutoCloseable {
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
    override fun close()
}

fun MusicCatalog.asPlaybackSourceResolver(): PlaybackSourceResolver = PlaybackSourceResolver { item ->
    val track = item.toMusicTrack()
    try {
        val resolved = resolve(track)
        if (resolved.trackId != track.id) {
            return@PlaybackSourceResolver Result.failure(
                IosPlaybackException(
                    PlaybackFailure(
                        message = "Resolved track does not match the selected song.",
                        kind = PlaybackFailure.Kind.SOURCE_RESOLUTION,
                        recoverable = true,
                    ),
                ),
            )
        }
        Result.success(resolved.toResolvedPlaybackSource())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: CatalogException) {
        val kind = when (failure.reason) {
            CatalogException.Reason.NETWORK,
            CatalogException.Reason.RATE_LIMITED,
            CatalogException.Reason.SERVER_ERROR,
            -> PlaybackFailure.Kind.NETWORK
            CatalogException.Reason.UNSUPPORTED_STREAM -> PlaybackFailure.Kind.UNSUPPORTED_FORMAT
            else -> PlaybackFailure.Kind.SOURCE_RESOLUTION
        }
        Result.failure(
            IosPlaybackException(
                PlaybackFailure(
                    message = failure.message ?: "This song could not be played. Try another song or retry.",
                    kind = kind,
                    recoverable = kind != PlaybackFailure.Kind.UNSUPPORTED_FORMAT,
                ),
                cause = failure,
            ),
        )
    } catch (error: Throwable) {
        Result.failure(error)
    }
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
interface PlayerEngine : AutoCloseable {
    val canAdjustVolume: Boolean get() = false
    val state: StateFlow<PlaybackState>
    fun load(track: MusicTrack, source: ResolvedAudio)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float) {}
    fun setVolume(volume: Float) {}
    fun stop()
    override fun close()
}

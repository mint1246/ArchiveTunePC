/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

/** UI and persistence friendly metadata. It deliberately has no Android or Media3 types. */
data class PlaybackMetadata(
    val id: String,
    val title: String,
    val artists: List<PlaybackArtist> = emptyList(),
    val album: PlaybackAlbum? = null,
    val artworkUri: String? = null,
    val durationHintMs: Long? = null,
    val explicit: Boolean = false,
    val isMusicVideo: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
) {
    val artistText: String
        get() = artists.joinToString { it.name }
}

data class PlaybackArtist(
    val id: String? = null,
    val name: String,
)

data class PlaybackAlbum(
    val id: String? = null,
    val title: String,
)

/**
 * Describes how an item reaches the playback engine.
 *
 * [Deferred] is the normal representation for a YouTube item. Its key is handed back to the
 * ArchiveTune stream resolver immediately before playback, avoiding persistence of expiring URLs.
 */
sealed interface PlaybackSource {
    data class Deferred(val key: String) : PlaybackSource

    data class Remote(
        val url: String,
        val mimeType: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val expiresAtEpochMs: Long? = null,
    ) : PlaybackSource

    data class LocalFile(val path: String) : PlaybackSource
}

data class PlaybackItem(
    val metadata: PlaybackMetadata,
    val source: PlaybackSource = PlaybackSource.Deferred(metadata.id),
)

/** A source accepted directly by a desktop engine after any deferred resolution. */
data class ResolvedPlaybackSource(
    val uri: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null,
)

fun interface PlaybackSourceResolver {
    suspend fun resolve(item: PlaybackItem): Result<ResolvedPlaybackSource>
}

enum class PlaybackStatus {
    IDLE,
    RESOLVING,
    LOADING,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

enum class RepeatMode {
    OFF,
    ONE,
    ALL,
}

data class PlaybackFailure(
    val message: String,
    val kind: Kind = Kind.UNKNOWN,
    val recoverable: Boolean = true,
) {
    enum class Kind {
        ENGINE_UNAVAILABLE,
        SOURCE_RESOLUTION,
        NETWORK,
        UNSUPPORTED_FORMAT,
        LOCAL_FILE_MISSING,
        ENGINE,
        UNKNOWN,
    }
}

data class PlaybackSnapshot(
    val queue: List<PlaybackItem> = emptyList(),
    val queueTitle: String? = null,
    val currentIndex: Int = -1,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val hasPreviousItem: Boolean = false,
    val hasNextItem: Boolean = false,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val error: PlaybackFailure? = null,
) {
    val currentItem: PlaybackItem?
        get() = queue.getOrNull(currentIndex)

    val isPlaying: Boolean
        get() = playWhenReady && status == PlaybackStatus.READY

    val isLoading: Boolean
        get() = status == PlaybackStatus.RESOLVING ||
            status == PlaybackStatus.LOADING ||
            status == PlaybackStatus.BUFFERING

    val canSkipPrevious: Boolean
        get() = currentItem != null && (positionMs > 0L || hasPreviousItem)

    val canSkipNext: Boolean
        get() = currentItem != null && hasNextItem
}

enum class TransitionReason {
    QUEUE_REPLACED,
    USER_NEXT,
    USER_PREVIOUS,
    USER_SELECTED,
    AUTO_ADVANCE,
    REPEAT,
    CURRENT_ITEM_REMOVED,
    RETRY,
}

sealed interface PlaybackEvent {
    data class MediaTransition(
        val previous: PlaybackItem?,
        val current: PlaybackItem?,
        val reason: TransitionReason,
    ) : PlaybackEvent

    data class QueueChanged(val queue: List<PlaybackItem>, val currentIndex: Int) : PlaybackEvent

    data class Failure(val failure: PlaybackFailure, val item: PlaybackItem?) : PlaybackEvent
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube.playback

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.NextResult
import moe.rukamori.archivetune.playback.api.ClickedItemQueueSelection
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.fallbackClickedItemQueue
import moe.rukamori.archivetune.playback.api.selectClickedItemQueue

interface DesktopUpNextDiagnostics {
    fun info(message: String)
    fun warning(message: String)

    companion object {
        val NoOp: DesktopUpNextDiagnostics = object : DesktopUpNextDiagnostics {
            override fun info(message: String) = Unit
            override fun warning(message: String) = Unit
        }
    }
}

data class DesktopUpNextResolution(
    val selection: ClickedItemQueueSelection<PlaybackItem>,
    val failureMessage: String? = null,
)

/**
 * Resolves an "Up next" queue for an explicitly clicked [SongItem], enforcing
 * [selectClickedItemQueue] so YouTube's server-reported `currentIndex` never overrides the
 * user's clicked track.
 */
class DesktopUpNextQueueResolver(
    private val fetchUpNext: suspend (String) -> Result<NextResult>,
    private val learnAlbum: (String, PlaybackAlbum) -> Unit = { _, _ -> },
    private val lookupAlbum: (String) -> PlaybackAlbum? = { null },
    private val diagnostics: DesktopUpNextDiagnostics = DesktopUpNextDiagnostics.NoOp,
) {
    suspend fun resolve(clickedSong: SongItem): DesktopUpNextResolution =
        fetchUpNext(clickedSong.id).fold(
            onSuccess = { next ->
                val songs = next.items
                val selection = selectClickedItemQueue(
                    clickedItem = clickedSong.toPlaybackItem(learnAlbum, lookupAlbum),
                    clickedId = clickedSong.id,
                    returnedItems = songs.map { it.toPlaybackItem(learnAlbum, lookupAlbum) },
                    itemId = { it.metadata.id },
                    returnedTitle = next.title,
                )
                when (selection.outcome) {
                    ClickedItemQueueSelection.Outcome.EMPTY_QUEUE_FALLBACK -> {
                        diagnostics.info(
                            "up_next_selection event=empty requestedVideoId=${clickedSong.id} backendCurrentIndex=${next.currentIndex}",
                        )
                    }
                    ClickedItemQueueSelection.Outcome.MATCHED -> {
                        val backendSelectedId = next.currentIndex?.let(songs::getOrNull)?.id
                        diagnostics.info(
                            "up_next_selection event=resolved requestedVideoId=${clickedSong.id} " +
                                "backendCurrentIndex=${next.currentIndex} backendSelectedVideoId=$backendSelectedId " +
                                "matchedIndex=${selection.startIndex} queueSize=${songs.size}",
                        )
                    }
                    ClickedItemQueueSelection.Outcome.MISSING_TARGET_FALLBACK -> {
                        val backendSelectedId = next.currentIndex?.let(songs::getOrNull)?.id
                        diagnostics.info(
                            "up_next_selection event=resolved requestedVideoId=${clickedSong.id} " +
                                "backendCurrentIndex=${next.currentIndex} backendSelectedVideoId=$backendSelectedId " +
                                "matchedIndex=null queueSize=${songs.size}",
                        )
                        diagnostics.warning(
                            "up_next_selection event=requested_missing requestedVideoId=${clickedSong.id} " +
                                "backendCurrentIndex=${next.currentIndex} backendSelectedVideoId=$backendSelectedId " +
                                "queueVideoIds=${songs.take(12).joinToString(",") { it.id }}",
                        )
                    }
                    ClickedItemQueueSelection.Outcome.FAILURE_FALLBACK -> Unit
                }
                DesktopUpNextResolution(selection = selection)
            },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                diagnostics.warning(
                    "up_next_selection event=failure requestedVideoId=${clickedSong.id} " +
                        "errorType=${failure::class.simpleName} error=${failure.message?.replace(Regex("\\s+"), " ")?.take(240)}",
                )
                DesktopUpNextResolution(
                    selection = fallbackClickedItemQueue(
                        clickedItem = clickedSong.toPlaybackItem(learnAlbum, lookupAlbum),
                    ),
                    failureMessage = failure.message ?: "Could not load Up next",
                )
            },
        )
}

fun SongItem.toPlaybackItem(
    learnAlbum: (String, PlaybackAlbum) -> Unit = { _, _ -> },
    lookupAlbum: (String) -> PlaybackAlbum? = { null },
): PlaybackItem = PlaybackItem(
    PlaybackMetadata(
        id = id,
        title = title,
        artists = artists.map { PlaybackArtist(it.id, it.name) },
        album =
            album?.let { PlaybackAlbum(it.id, it.name) }
                ?.takeIf { !it.id.isNullOrBlank() && !it.title.isBlank() }
                ?.also { learnAlbum(id, it) }
                ?: lookupAlbum(id),
        artworkUri = thumbnail,
        durationHintMs = duration?.times(1000L),
        explicit = explicit,
        isPodcast = isPodcast,
    ),
)

fun EpisodeItem.toPlaybackItem(): PlaybackItem = PlaybackItem(
    PlaybackMetadata(
        id = id,
        title = title,
        artists = listOf(PlaybackArtist(podcast?.id, podcast?.name ?: "Podcast")),
        album = PlaybackAlbum(podcast?.id ?: browseId, podcast?.name ?: "Podcast"),
        artworkUri = thumbnail,
        durationHintMs = duration?.times(1000L),
        isPodcast = true,
    ),
)

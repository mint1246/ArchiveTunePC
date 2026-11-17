/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.downloads

import android.os.SystemClock
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.sdk.download.DownloadEntry
import moe.rukamori.archivetune.sdk.download.DownloadGroup
import moe.rukamori.archivetune.sdk.download.DownloadLibrary
import moe.rukamori.archivetune.sdk.download.DownloadLibraryAggregator
import moe.rukamori.archivetune.sdk.download.DownloadLibraryInput
import moe.rukamori.archivetune.sdk.download.DownloadSection
import moe.rukamori.archivetune.sdk.download.DownloadSnapshot
import moe.rukamori.archivetune.sdk.download.DownloadState
import moe.rukamori.archivetune.sdk.download.DownloadSongSource
import javax.inject.Inject

typealias DownloadMediaType = moe.rukamori.archivetune.sdk.download.DownloadMediaType
typealias DownloadEntryUiModel = DownloadEntry<MediaMetadata>
typealias DownloadSectionUiModel = DownloadSection<MediaMetadata>
typealias DownloadLibraryUiModel = DownloadLibrary<MediaMetadata>

class ManageDownloadsUseCase
    @Inject
    constructor(
        private val repository: Media3DownloadRepository,
    ) {
        private val aggregator =
            DownloadLibraryAggregator<Song, MediaMetadata> { source, id, fallbackTitle ->
                source?.toMediaMetadata()
                    ?: MediaMetadata(
                        id = id,
                        title = fallbackTitle,
                        artists = emptyList(),
                        duration = -1,
                    )
            }

        fun observe(): Flow<DownloadLibraryUiModel> = repository.observeDownloads().map(::mapSnapshot).flowOn(Dispatchers.Default)

        fun pause(songIds: Collection<String>) = repository.pause(songIds)

        fun resume(songIds: Collection<String>) = repository.resume(songIds)

        fun remove(songIds: Collection<String>) = repository.remove(songIds)

        private fun mapSnapshot(snapshot: DownloadRepositorySnapshot): DownloadLibraryUiModel =
            aggregator.aggregate(
                DownloadLibraryInput(
                    songs =
                        snapshot.songs.map { song ->
                            DownloadSongSource(
                                source = song,
                                id = song.song.id,
                                title = song.song.title,
                                artistsText = song.artists.joinToString { it.name }.ifBlank { null },
                                thumbnailUrl = song.song.thumbnailUrl,
                                albumId = song.album?.id ?: song.song.albumId,
                                durationSeconds = song.song.duration.takeIf { it > 0 },
                            )
                        },
                    groups =
                        buildList {
                            snapshot.playlists.forEach { playlist ->
                                add(
                                    DownloadGroup(
                                        id = playlist.id,
                                        mediaType = DownloadMediaType.PLAYLIST,
                                        title = playlist.title,
                                        supportingText = null,
                                        thumbnailUrl = playlist.thumbnails.firstOrNull(),
                                        destinationRoute =
                                            playlist.playlist.browseId?.let { "online_playlist/$it" }
                                                ?: "local_playlist/${playlist.id}",
                                    ),
                                )
                            }
                            snapshot.albums.forEach { album ->
                                add(
                                    DownloadGroup(
                                        id = album.id,
                                        mediaType = DownloadMediaType.ALBUM,
                                        title = album.title,
                                        supportingText = album.artists.joinToString { it.name }.ifBlank { null },
                                        thumbnailUrl = album.thumbnailUrl,
                                        destinationRoute = "album/${album.id}",
                                    ),
                                )
                            }
                        },
                    songIdsByGroup = snapshot.playlistSongMaps.groupBy({ it.playlistId }, { it.songId }),
                    downloads =
                        snapshot.downloads.mapValues { (id, download) ->
                            DownloadSnapshot(
                                id = id,
                                state = downloadState(download.state),
                                bytesDownloaded = download.bytesDownloaded,
                                contentLength = download.contentLength,
                                percentDownloaded = download.percentDownloaded,
                                updateTimeMs = download.updateTimeMs,
                                stopReason = download.stopReason,
                                failureReason = download.failureReason,
                                title = download.request.data.toString(Charsets.UTF_8).ifBlank { id },
                            )
                        },
                    nowMs = SystemClock.elapsedRealtime(),
                ),
            )
    }

internal fun downloadState(state: Int): DownloadState = when (state) {
    Download.STATE_QUEUED -> DownloadState.QUEUED
    Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
    Download.STATE_RESTARTING -> DownloadState.RESTARTING
    Download.STATE_COMPLETED -> DownloadState.COMPLETED
    Download.STATE_FAILED -> DownloadState.FAILED
    Download.STATE_REMOVING -> DownloadState.REMOVING
    Download.STATE_STOPPED -> DownloadState.STOPPED
    else -> DownloadState.UNKNOWN
}

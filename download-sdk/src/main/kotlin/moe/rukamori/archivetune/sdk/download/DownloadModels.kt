/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.download

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/** Platform-neutral projection of the parts of a Media3 `Download` the orchestrator reads. */
data class DownloadSnapshot(
    val id: String,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val contentLength: Long,
    val percentDownloaded: Float,
    val updateTimeMs: Long,
    val stopReason: Int,
    val failureReason: Int,
    val title: String,
)

/**
 * A caller-owned song value plus the fields the orchestrator needs from it. The original value is
 * retained so callers can build their own playback metadata in [DownloadLibraryAggregator].
 */
data class DownloadSongSource<out S>(
    val source: S,
    val id: String,
    val title: String,
    val artistsText: String?,
    val thumbnailUrl: String?,
    val albumId: String?,
    val durationSeconds: Int?,
)

enum class DownloadMediaType {
    PLAYLIST,
    ALBUM,
    SONG,
}

/** A playlist or album grouping supplied by the caller. */
data class DownloadGroup(
    val id: String,
    val mediaType: DownloadMediaType,
    val title: String,
    val supportingText: String?,
    val thumbnailUrl: String?,
    val destinationRoute: String?,
)

/** Everything the aggregator needs for one snapshot. No platform types cross this boundary. */
data class DownloadLibraryInput<out S>(
    val songs: List<DownloadSongSource<S>>,
    val groups: List<DownloadGroup>,
    val songIdsByGroup: Map<String, List<String>>,
    val downloads: Map<String, DownloadSnapshot>,
    val nowMs: Long,
)

@Immutable
data class DownloadEntry<out M>(
    val id: String,
    val title: String,
    val supportingText: String?,
    val thumbnailUrl: String?,
    val destinationRoute: String?,
    val playbackMetadata: M?,
    val durationSeconds: Int?,
    val songIds: List<String>,
    val totalCount: Int,
    val progress: Float,
    val percent: Int,
    val speedBytesPerSecond: Long,
    val paused: Boolean,
    val failed: Boolean,
    val children: List<DownloadEntry<M>> = emptyList(),
)

@Immutable
data class DownloadSection<out M>(
    val mediaType: DownloadMediaType,
    val entries: List<DownloadEntry<M>>,
) {
    val songIds: List<String> = entries.flatMap { it.songIds }.distinct()
    val paused: Boolean = entries.isNotEmpty() && entries.all { it.paused }
    val percent: Int =
        entries
            .map { it.progress }
            .average()
            .times(100)
            .roundToInt()
            .coerceIn(0, 100)
    val speedBytesPerSecond: Long = entries.sumOf { it.speedBytesPerSecond }
}

@Immutable
data class DownloadLibrary<out M>(
    val downloadedSections: List<DownloadSection<M>>,
    val progressSections: List<DownloadSection<M>>,
) {
    val isEmpty: Boolean = downloadedSections.isEmpty() && progressSections.isEmpty()
}

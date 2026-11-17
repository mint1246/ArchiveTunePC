/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.download

import kotlin.math.roundToInt

/**
 * Turns a caller-supplied download snapshot into grouped, ordered library sections.
 *
 * The aggregator owns only the derived speed samples; the caller owns persistence, the Media3
 * engine, concurrency, retries and cancellation. [metadataFor] receives the original caller song
 * when one is available, otherwise the download id and the title recorded in the request.
 */
class DownloadLibraryAggregator<S, M>(
    private val metadataFor: (source: S?, id: String, fallbackTitle: String) -> M?,
) {
    private val speedSampler = DownloadSpeedSampler()

    fun aggregate(input: DownloadLibraryInput<S>): DownloadLibrary<M> {
        val speeds = speedSampler.calculate(input.downloads, input.nowMs)
        val songsById = input.songs.associateBy { it.id }
        val completedIds =
            input.downloads.values
                .filter { it.state == DownloadState.COMPLETED }
                .mapTo(mutableSetOf()) { it.id }
        val activeIds =
            input.downloads.values
                .filter { DownloadStates.isVisibleInProgress(it.state) }
                .mapTo(mutableSetOf()) { it.id }

        val albumSongIds =
            input.songs
                .mapNotNull { song ->
                    song.albumId?.let { albumId -> albumId to song.id }
                }.groupBy({ it.first }, { it.second })
        val groupSongIds = input.songIdsByGroup
        val playlists = input.groups.filter { it.mediaType == DownloadMediaType.PLAYLIST }
        val albums = input.groups.filter { it.mediaType == DownloadMediaType.ALBUM }

        val downloadedSections =
            buildList {
                buildGroupEntries(
                    groups = playlists,
                    groupSongIds = groupSongIds,
                    relevantIds = completedIds,
                    downloads = input.downloads,
                    speeds = speeds,
                    requireCompleted = true,
                    songsById = songsById,
                ).takeIf { it.isNotEmpty() }?.let { add(DownloadSection(DownloadMediaType.PLAYLIST, it)) }
                buildGroupEntries(
                    groups = albums,
                    groupSongIds = albumSongIds,
                    relevantIds = completedIds,
                    downloads = input.downloads,
                    speeds = speeds,
                    requireCompleted = true,
                    songsById = songsById,
                ).takeIf { it.isNotEmpty() }?.let { add(DownloadSection(DownloadMediaType.ALBUM, it)) }
                buildSongEntries(
                    songIds = completedIds,
                    songsById = songsById,
                    downloads = input.downloads,
                    speeds = speeds,
                ).takeIf { it.isNotEmpty() }?.let { add(DownloadSection(DownloadMediaType.SONG, it)) }
            }

        val progressSections =
            buildList {
                buildGroupEntries(
                    groups = playlists,
                    groupSongIds = groupSongIds,
                    relevantIds = activeIds,
                    downloads = input.downloads,
                    speeds = speeds,
                    requireCompleted = false,
                    songsById = songsById,
                ).takeIf { it.isNotEmpty() }?.let { add(DownloadSection(DownloadMediaType.PLAYLIST, it)) }
                buildGroupEntries(
                    groups = albums,
                    groupSongIds = albumSongIds,
                    relevantIds = activeIds,
                    downloads = input.downloads,
                    speeds = speeds,
                    requireCompleted = false,
                    songsById = songsById,
                ).takeIf { it.isNotEmpty() }?.let { add(DownloadSection(DownloadMediaType.ALBUM, it)) }
                buildSongEntries(
                    songIds = activeIds,
                    songsById = songsById,
                    downloads = input.downloads,
                    speeds = speeds,
                ).takeIf { it.isNotEmpty() }?.let { add(DownloadSection(DownloadMediaType.SONG, it)) }
            }

        return DownloadLibrary(
            downloadedSections = downloadedSections,
            progressSections = progressSections,
        )
    }

    private fun buildGroupEntries(
        groups: List<DownloadGroup>,
        groupSongIds: Map<String, List<String>>,
        relevantIds: Set<String>,
        downloads: Map<String, DownloadSnapshot>,
        speeds: Map<String, Long>,
        requireCompleted: Boolean,
        songsById: Map<String, DownloadSongSource<S>>,
    ): List<DownloadEntry<M>> =
        groups
            .mapNotNull { group ->
                val songIds = groupSongIds[group.id].orEmpty().distinct()
                if (!songIds.isCollectionMatch(relevantIds, downloads, requireCompleted)) return@mapNotNull null
                val relevantSongIds = songIds.filter { it in relevantIds }
                val children =
                    if (requireCompleted) {
                        relevantSongIds.mapNotNull { songId ->
                            val song = songsById[songId]
                            val download = downloads[songId] ?: return@mapNotNull null
                            buildEntry(
                                id = "song:$songId",
                                title = song?.title ?: download.title,
                                supportingText = song?.artistsText,
                                thumbnailUrl = song?.thumbnailUrl,
                                destinationRoute = null,
                                playbackMetadata = metadataFor(song?.source, songId, download.title),
                                durationSeconds = song?.durationSeconds,
                                songIds = listOf(songId),
                                downloads = downloads,
                                speeds = speeds,
                            )
                        }
                    } else {
                        emptyList()
                    }
                buildEntry(
                    id = "${group.mediaType.name.lowercase()}:${group.id}",
                    title = group.title,
                    supportingText = group.supportingText,
                    thumbnailUrl = group.thumbnailUrl,
                    destinationRoute = group.destinationRoute,
                    playbackMetadata = null,
                    durationSeconds = null,
                    songIds = if (requireCompleted) relevantSongIds else songIds,
                    downloads = downloads,
                    speeds = speeds,
                    children = children,
                )
            }.sortedByDescending { entry -> entry.songIds.maxOfOrNull { downloads[it]?.updateTimeMs ?: 0L } ?: 0L }

    private fun buildSongEntries(
        songIds: Set<String>,
        songsById: Map<String, DownloadSongSource<S>>,
        downloads: Map<String, DownloadSnapshot>,
        speeds: Map<String, Long>,
    ): List<DownloadEntry<M>> =
        songIds
            .mapNotNull { songId ->
                val download = downloads[songId] ?: return@mapNotNull null
                val song = songsById[songId]
                buildEntry(
                    id = "song:$songId",
                    title = song?.title ?: download.title,
                    supportingText = song?.artistsText,
                    thumbnailUrl = song?.thumbnailUrl,
                    destinationRoute = null,
                    playbackMetadata = metadataFor(song?.source, songId, download.title),
                    durationSeconds = song?.durationSeconds,
                    songIds = listOf(songId),
                    downloads = downloads,
                    speeds = speeds,
                )
            }.sortedByDescending { downloads[it.songIds.single()]?.updateTimeMs ?: 0L }

    private fun buildEntry(
        id: String,
        title: String,
        supportingText: String?,
        thumbnailUrl: String?,
        destinationRoute: String?,
        playbackMetadata: M?,
        durationSeconds: Int?,
        songIds: List<String>,
        downloads: Map<String, DownloadSnapshot>,
        speeds: Map<String, Long>,
        children: List<DownloadEntry<M>> = emptyList(),
    ): DownloadEntry<M> {
        val entries = songIds.mapNotNull(downloads::get)
        val activeEntries = entries.filter { DownloadStates.isVisibleInProgress(it.state) }
        val progress =
            songIds
                .map { songId ->
                    when (val download = downloads[songId]) {
                        null -> 0f
                        else -> {
                            if (download.state == DownloadState.COMPLETED) {
                                1f
                            } else {
                                download.percentDownloaded.takeIf { it >= 0f }?.div(100f) ?: 0f
                            }
                        }
                    }
                }.average()
                .toFloat()
                .coerceIn(0f, 1f)
        return DownloadEntry(
            id = id,
            title = title,
            supportingText = supportingText,
            thumbnailUrl = thumbnailUrl,
            destinationRoute = destinationRoute,
            playbackMetadata = playbackMetadata,
            durationSeconds = durationSeconds,
            songIds = songIds,
            totalCount = songIds.size,
            progress = progress,
            percent = (progress * 100f).roundToInt().coerceIn(0, 100),
            speedBytesPerSecond = activeEntries.sumOf { speeds[it.id] ?: 0L },
            paused = activeEntries.isNotEmpty() && activeEntries.all { DownloadStates.isPausedState(it.state) },
            failed = activeEntries.any { it.state == DownloadState.FAILED },
            children = children,
        )
    }

    private fun List<String>.isCollectionMatch(
        relevantIds: Set<String>,
        downloads: Map<String, DownloadSnapshot>,
        requireCompleted: Boolean,
    ): Boolean {
        if (size < MIN_COLLECTION_SIZE || none(relevantIds::contains)) return false
        return if (requireCompleted) {
            any { downloads[it]?.state == DownloadState.COMPLETED }
        } else {
            all { downloads[it]?.let { download -> DownloadStates.isTrackedState(download.state) } == true }
        }
    }

    private companion object {
        const val MIN_COLLECTION_SIZE = 2
    }
}

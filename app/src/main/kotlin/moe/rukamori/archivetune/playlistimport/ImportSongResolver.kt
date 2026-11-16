/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlistimport

import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.models.ImportSource
import moe.rukamori.archivetune.models.ImportedSongResult
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.utils.describeImportSong
import timber.log.Timber

fun interface ImportYouTubeSongSearch {
    suspend fun search(query: String): List<Song>
}

object YouTubeImportSongSearch : ImportYouTubeSongSearch {
    override suspend fun search(query: String): List<Song> =
        YouTube
            .search(query, YouTube.SearchFilter.FILTER_SONG)
            .onFailure { error ->
                Timber.w(error, "YouTube search failed for imported song query: %s", query)
            }.getOrNull()
            ?.items
            ?.filterIsInstance<SongItem>()
            ?.distinctBy { it.id }
            ?.map { it.toImportSong() }
            .orEmpty()
}

class ImportSongResolver(
    private val youtubeSearch: ImportYouTubeSongSearch = YouTubeImportSongSearch,
    private val maxConcurrentResolutions: Int = DEFAULT_MAX_CONCURRENT_RESOLUTIONS,
) {
    private val resolver = ImportResolver(
        describe = ::describeImportSong,
        search = youtubeSearch::search,
        onSearchFailure = { song, error -> Timber.w(error, "YouTube lookup failed for imported song: %s", song.title) },
        maxConcurrentResolutions = maxConcurrentResolutions,
    )

    suspend fun resolve(
        songs: List<Song>,
        localLibrary: List<Song>,
        localFirst: Boolean,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ImportedSongResult> =
        resolver.resolve(songs, localLibrary, localFirst, onProgress).map { result ->
            ImportedSongResult(
                originalSong = result.original,
                resolvedId = result.match?.id,
                resolvedSong = result.match,
                source = when (result.source) {
                    ImportMatchSource.LOCAL -> ImportSource.LOCAL
                    ImportMatchSource.YOUTUBE -> ImportSource.YOUTUBE
                    ImportMatchSource.UNRESOLVED -> ImportSource.UNRESOLVED
                },
            )
        }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_RESOLUTIONS = 5
    }
}

internal fun buildImportSongQuery(song: Song): String = buildImportSongQuery(describeImportSong(song))

private fun SongItem.toImportSong(): Song {
    val metadata = toMediaMetadata()
    return Song(
        song = metadata.toSongEntity(),
        artists =
            metadata.artists.mapIndexed { index, artist ->
                ArtistEntity(
                    id = artist.id ?: "import_${metadata.id}_artist_$index",
                    name = artist.name,
                    thumbnailUrl = artist.thumbnailUrl,
                )
            },
    )
}

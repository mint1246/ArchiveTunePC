/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlistexport

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.playlist.export.PlaylistExportPlaylist
import moe.rukamori.archivetune.playlist.export.PlaylistExportSong
import moe.rukamori.archivetune.playlist.export.PlaylistExportStore
import moe.rukamori.archivetune.repository.PlaylistExportRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Adapts the Android Room repository and ContentResolver destination to the headless export seam. */
@Singleton
class AndroidPlaylistExportStore
    @Inject
    constructor(
        private val repository: PlaylistExportRepository,
    ) : PlaylistExportStore<Uri> {
        override fun observePlaylists(): Flow<List<PlaylistExportPlaylist>> =
            repository
                .observePlaylists()
                .map { playlists ->
                    playlists.map { playlist ->
                        PlaylistExportPlaylist(
                            id = playlist.id,
                            name = playlist.playlist.name,
                            songCount = playlist.songCount,
                        )
                    }
                }

        override suspend fun loadPlaylistSongs(playlistId: String): List<PlaylistExportSong> =
            repository.loadPlaylistSongs(playlistId).map { playlistSong ->
                val song = playlistSong.song
                PlaylistExportSong(
                    id = song.song.id,
                    title = song.song.title,
                    artists = song.artists.map { artist -> artist.name },
                    album = song.album?.title ?: song.song.albumName.orEmpty(),
                )
            }

        override suspend fun writeCsv(
            destination: Uri,
            records: Sequence<List<String>>,
        ) = repository.writeCsv(destination, records)
    }

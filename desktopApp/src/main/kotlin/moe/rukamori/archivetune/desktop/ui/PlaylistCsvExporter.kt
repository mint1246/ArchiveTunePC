/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.desktop.library.LocalTrack
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvExporter as SdkPlaylistCsvExporter
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvLocalTrack
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvSong

typealias PlaylistCsvRecord = moe.rukamori.archivetune.sdk.playlist.PlaylistCsvRecord

/** Android-compatible playlist CSV export, preserving playlist occurrence order and duplicates. */
object PlaylistCsvExporter {
    fun records(songs: List<SongItem>, localTracks: List<LocalTrack> = emptyList()): List<PlaylistCsvRecord> =
        SdkPlaylistCsvExporter.records(
            songs = songs.map { song ->
                PlaylistCsvSong(
                    id = song.id,
                    title = song.title,
                    artists = song.artists.map { it.name },
                    album = song.album?.name,
                )
            },
            localTracks = localTracks.map { track ->
                PlaylistCsvLocalTrack(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                )
            },
        )

    fun encode(records: List<PlaylistCsvRecord>): ByteArray = SdkPlaylistCsvExporter.encode(records)

    fun suggestedFileName(playlistName: String): String = SdkPlaylistCsvExporter.suggestedFileName(playlistName)
}

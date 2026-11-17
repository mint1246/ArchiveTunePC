/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.playlist.export.PlaylistCsvFormat
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistExportRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) {
        fun observePlaylists(): Flow<List<Playlist>> =
            database
                .playlistsByCreateDateAsc()
                .flowOn(Dispatchers.IO)

        suspend fun loadPlaylistSongs(playlistId: String): List<PlaylistSong> =
            withContext(Dispatchers.IO) {
                database.getPlaylistSongs(playlistId)
            }

        suspend fun writeCsv(
            destination: Uri,
            records: Sequence<List<String>>,
        ) = withContext(Dispatchers.IO) {
            val writer =
                context.contentResolver
                    .openOutputStream(destination, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?: throw IOException("Unable to open CSV destination")

            writer.use { output -> PlaylistCsvFormat.write(output, records) }
        }
    }

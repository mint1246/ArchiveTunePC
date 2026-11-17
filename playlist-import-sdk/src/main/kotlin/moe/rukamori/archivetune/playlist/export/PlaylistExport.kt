/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlist.export

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.io.Writer

/** A playlist offered for export. Stable so Compose can skip unchanged items. */
@Immutable
data class ExportablePlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
    val suggestedFileName: String,
)

/** Caller-owned playlist summary used by [PlaylistExportCoordinator.observe]. */
data class PlaylistExportPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
)

/** Caller-owned song used by [PlaylistCsvFormat.records]; [artists] already holds display names. */
data class PlaylistExportSong(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
)

/**
 * Storage and provider seam for playlist export. The destination is owned by the caller so this
 * module never depends on Android `Uri` or a particular persistence implementation.
 */
interface PlaylistExportStore<D> {
    fun observePlaylists(): Flow<List<PlaylistExportPlaylist>>
    suspend fun loadPlaylistSongs(playlistId: String): List<PlaylistExportSong>
    suspend fun writeCsv(
        destination: D,
        records: Sequence<List<String>>,
    )
}

/**
 * Headless playlist export coordinator. Observation preserves the caller's reversed playlist
 * order and filters empty playlists; export preserves song order and duplicate occurrences.
 * All suspend calls propagate cancellation from the caller.
 */
class PlaylistExportCoordinator<D>(
    private val store: PlaylistExportStore<D>,
) {
    fun observe(): Flow<List<ExportablePlaylist>> =
        store
            .observePlaylists()
            .map { playlists ->
                playlists
                    .asReversed()
                    .asSequence()
                    .filter { playlist -> playlist.songCount > 0 }
                    .map { playlist ->
                        ExportablePlaylist(
                            id = playlist.id,
                            name = playlist.name,
                            songCount = playlist.songCount,
                            suggestedFileName = PlaylistCsvFormat.fileName(playlist.name),
                        )
                    }.toList()
            }.flowOn(Dispatchers.Default)

    suspend fun export(
        playlistId: String,
        destination: D,
    ) {
        val songs = store.loadPlaylistSongs(playlistId)
        if (songs.isEmpty()) throw IOException("Playlist has no songs")
        store.writeCsv(destination, PlaylistCsvFormat.records(songs))
    }
}

/** The exact CSV format used by ArchiveTune playlist export, kept headless and side-effect free. */
object PlaylistCsvFormat {
    val CSV_HEADER: List<String> = listOf("Title", "Artist", "Album", "Media ID")

    /** Builds a safe suggested file name without changing the stored playlist name. */
    fun fileName(name: String): String {
        val safeBaseName =
            name
                .trim()
                .replace(CSV_FILE_NAME_FORBIDDEN_CHARACTERS, "_")
                .trimEnd('.', ' ')
                .takeCodePoints(MAX_CSV_FILE_NAME_LENGTH)
                .ifBlank { DEFAULT_CSV_FILE_NAME }
        return "$safeBaseName.csv"
    }

    /**
     * Lazily builds records so a cancelled export does not materialize the whole playlist.
     * Song order and duplicate occurrences are preserved verbatim.
     */
    fun records(songs: List<PlaylistExportSong>): Sequence<List<String>> =
        sequence {
            yield(CSV_HEADER)
            songs.forEach { song ->
                yield(
                    listOf(
                        song.title,
                        song.artists.joinToString(separator = "; "),
                        song.album.orEmpty(),
                        song.id,
                    ),
                )
            }
        }

    /** Writes the BOM, quoted fields and CRLF line endings used by the existing export storage. */
    fun write(
        writer: Writer,
        records: Sequence<List<String>>,
    ) {
        writer.append('\uFEFF')
        records.forEach { record ->
            record.forEachIndexed { index, value ->
                if (index > 0) writer.append(',')
                writer.writeCsvField(value)
            }
            writer.append("\r\n")
        }
    }

    private fun Writer.writeCsvField(value: String) {
        val safeValue = value.escapeSpreadsheetFormula()
        append('"')
        safeValue.forEach { character ->
            if (character == '"') append('"')
            append(character)
        }
        append('"')
    }

    private fun String.escapeSpreadsheetFormula(): String {
        val firstMeaningfulCharacter = firstOrNull { character -> !character.isWhitespace() }
        return if (firstMeaningfulCharacter != null && firstMeaningfulCharacter in CSV_FORMULA_PREFIXES) {
            "'$this"
        } else {
            this
        }
    }

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        val count = codePointCount(0, length)
        if (count <= maxCodePoints) return this
        return substring(0, offsetByCodePoints(0, maxCodePoints))
    }

    private val CSV_FILE_NAME_FORBIDDEN_CHARACTERS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val CSV_FORMULA_PREFIXES = setOf('=', '+', '-', '@')
    private const val MAX_CSV_FILE_NAME_LENGTH = 96
    private const val DEFAULT_CSV_FILE_NAME = "playlist"
}

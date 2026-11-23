/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sdk.playlist

data class PlaylistCsvRecord(
    val title: String,
    val artist: String,
    val album: String,
    val mediaId: String,
)

data class PlaylistCsvSong(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
)

data class PlaylistCsvLocalTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
)

/** Android-compatible playlist CSV export, preserving playlist occurrence order and duplicates. */
object PlaylistCsvExporter {
    private val forbiddenFileNameCharacters = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val whitespace = Regex("\\s+")
    private val formulaPrefixes = setOf('=', '+', '-', '@')
    private val header = listOf("Title", "Artist", "Album", "Media ID")

    fun records(
        songs: List<PlaylistCsvSong>,
        localTracks: List<PlaylistCsvLocalTrack> = emptyList(),
    ): List<PlaylistCsvRecord> {
        val localById = localTracks.associateBy(PlaylistCsvLocalTrack::id)
        val localByIdentity = buildMap {
            localTracks.forEach { track ->
                val key = identity(track.title, track.artist)
                if (key.first.isNotEmpty() && key.second.isNotEmpty() && key !in this) put(key, track)
            }
        }
        return songs.map { song ->
            val artist = song.artists.joinToString("; ")
            val local = localById[song.id] ?: song.artists.firstNotNullOfOrNull { sourceArtist ->
                localByIdentity[identity(song.title, sourceArtist)]
            }
            PlaylistCsvRecord(
                title = local?.title ?: song.title,
                artist = local?.artist ?: artist,
                album = local?.album ?: song.album.orEmpty(),
                mediaId = song.id,
            )
        }
    }

    fun encode(records: List<PlaylistCsvRecord>): ByteArray = buildString {
        append('\uFEFF')
        (sequenceOf(header) + records.asSequence().map { listOf(it.title, it.artist, it.album, it.mediaId) })
            .forEach { row ->
                row.forEachIndexed { index, field ->
                    if (index > 0) append(',')
                    val firstMeaningful = field.firstOrNull { !it.isWhitespace() }
                    val safeField = if (firstMeaningful != null && firstMeaningful in formulaPrefixes) "'$field" else field
                    append('"')
                    safeField.forEach { character ->
                        if (character == '"') append('"')
                        append(character)
                    }
                    append('"')
                }
                append("\r\n")
            }
    }.encodeToByteArray()

    fun suggestedFileName(playlistName: String): String {
        val safe = playlistName.trim()
            .replace(forbiddenFileNameCharacters, "_")
            .trimEnd('.', ' ')
            .takeCodePoints(MAX_BASE_NAME_CODE_POINTS)
            .ifBlank { DEFAULT_FILE_NAME }
        return "$safe.csv"
    }

    private fun identity(title: String, artist: String): Pair<String, String> =
        title.normalizedIdentity() to artist.normalizedIdentity()

    private fun String.normalizedIdentity(): String = trim().replace(whitespace, " ").lowercase()

    private fun String.takeCodePoints(maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ""
        var codePoints = 0
        var index = 0
        while (index < length) {
            if (codePoints >= maxCodePoints) return substring(0, index)
            val c = this[index]
            index += if (c.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
            codePoints++
        }
        return this
    }

    private const val MAX_BASE_NAME_CODE_POINTS = 96
    private const val DEFAULT_FILE_NAME = "playlist"
}

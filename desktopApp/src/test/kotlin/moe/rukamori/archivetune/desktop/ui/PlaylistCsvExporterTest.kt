package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.desktop.library.LocalTrack
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistCsvExporterTest {
    @Test
    fun `export preserves source order duplicates and matching local library metadata`() {
        val local = LocalTrack(
            id = "local-file-id",
            path = Path.of("/music/track.flac"),
            modifiedMillis = 10,
            sizeBytes = 20,
            title = "Song Name",
            artist = "Artist Name",
            album = "Library Album",
        )
        val first = SongItem(
            id = "video-a",
            title = " song   name ",
            artists = listOf(Artist("artist name", "artist-id")),
            album = Album("Remote Album", "album-id"),
            thumbnail = "",
        )
        val second = SongItem(
            id = "video-b",
            title = "Second Song",
            artists = listOf(Artist("Second Artist", null)),
            album = Album("Second Album", "album-b"),
            thumbnail = "",
        )

        assertEquals(
            listOf(
                PlaylistCsvRecord("Song Name", "Artist Name", "Library Album", "video-a"),
                PlaylistCsvRecord("Song Name", "Artist Name", "Library Album", "video-a"),
                PlaylistCsvRecord("Second Song", "Second Artist", "Second Album", "video-b"),
            ),
            PlaylistCsvExporter.records(listOf(first, first, second), listOf(local)),
        )
    }

    @Test
    fun `CSV is Excel friendly escaped and includes four Android compatible columns`() {
        val encoded = PlaylistCsvExporter.encode(
            listOf(PlaylistCsvRecord("=1+1", "Artist, \"A\"", "Album\nName", "id-1")),
        )
        val csv = encoded.toString(StandardCharsets.UTF_8)

        assertTrue(encoded.take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        assertEquals(
            "\uFEFF\"Title\",\"Artist\",\"Album\",\"Media ID\"\r\n" +
                "\"'=1+1\",\"Artist, \"\"A\"\"\",\"Album\nName\",\"id-1\"\r\n",
            csv,
        )
    }

    @Test
    fun `suggested filename is safe and bounded`() {
        assertEquals("Road _ Live.csv", PlaylistCsvExporter.suggestedFileName(" Road / Live. "))
        assertEquals("playlist.csv", PlaylistCsvExporter.suggestedFileName("   "))
        assertEquals(96 + ".csv".length, PlaylistCsvExporter.suggestedFileName("🎵".repeat(100)).codePointCount(0, PlaylistCsvExporter.suggestedFileName("🎵".repeat(100)).length))
    }
}

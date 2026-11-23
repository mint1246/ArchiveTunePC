package moe.rukamori.archivetune.sdk.playlist

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistCsvExporterTest {
    @Test
    fun `export preserves source order duplicates and matching local library metadata`() {
        val local = PlaylistCsvLocalTrack(
            id = "local-file-id",
            title = "Song Name",
            artist = "Artist Name",
            album = "Library Album",
        )
        val first = PlaylistCsvSong(
            id = "video-a",
            title = " song   name ",
            artists = listOf("artist name"),
            album = "Remote Album",
        )
        val second = PlaylistCsvSong(
            id = "video-b",
            title = "Second Song",
            artists = listOf("Second Artist", "Featured"),
            album = "Second Album",
        )

        assertEquals(
            listOf(
                PlaylistCsvRecord("Song Name", "Artist Name", "Library Album", "video-a"),
                PlaylistCsvRecord("Song Name", "Artist Name", "Library Album", "video-a"),
                PlaylistCsvRecord("Second Song", "Second Artist; Featured", "Second Album", "video-b"),
            ),
            PlaylistCsvExporter.records(listOf(first, first, second), listOf(local)),
        )
    }

    @Test
    fun `export matches local track by id before normalized title and artist`() {
        val byId = PlaylistCsvLocalTrack(
            id = "video-1",
            title = "Local Title",
            artist = "Local Artist",
            album = "Local Album",
        )
        val song = PlaylistCsvSong(
            id = "video-1",
            title = "Remote Title",
            artists = listOf("Remote Artist"),
            album = "Remote Album",
        )

        assertEquals(
            listOf(PlaylistCsvRecord("Local Title", "Local Artist", "Local Album", "video-1")),
            PlaylistCsvExporter.records(listOf(song), listOf(byId)),
        )
    }

    @Test
    fun `CSV is Excel friendly escaped and includes four Android compatible columns`() {
        val encoded = PlaylistCsvExporter.encode(
            listOf(
                PlaylistCsvRecord("=1+1", "Artist, \"A\"", "Album\nName", "id-1"),
                PlaylistCsvRecord(" +SUM(A1)", "-cmd", "@at", "id-2"),
            ),
        )
        val csv = encoded.toString(StandardCharsets.UTF_8)

        assertTrue(encoded.take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        assertEquals(
            "\uFEFF\"Title\",\"Artist\",\"Album\",\"Media ID\"\r\n" +
                "\"'=1+1\",\"Artist, \"\"A\"\"\",\"Album\nName\",\"id-1\"\r\n" +
                "\"' +SUM(A1)\",\"'-cmd\",\"'@at\",\"id-2\"\r\n",
            csv,
        )
    }

    @Test
    fun `suggested filename is safe and bounded`() {
        assertEquals("Road _ Live.csv", PlaylistCsvExporter.suggestedFileName(" Road / Live. "))
        assertEquals("playlist.csv", PlaylistCsvExporter.suggestedFileName("   "))
        val emojiName = PlaylistCsvExporter.suggestedFileName("🎵".repeat(100))
        assertEquals(96 + ".csv".length, emojiName.codePointCount(0, emojiName.length))
    }
}

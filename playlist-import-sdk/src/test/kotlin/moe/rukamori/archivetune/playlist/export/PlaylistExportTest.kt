/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlist.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistExportTest {
    private fun song(
        id: String,
        title: String = "title $id",
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
    ) = PlaylistExportSong(id = id, title = title, artists = artists, album = album)

    @Test
    fun `records keep playlist order and duplicate occurrences`() {
        val songs = listOf(song("a"), song("b"), song("a"))

        assertEquals(
            listOf(
                PlaylistCsvFormat.CSV_HEADER,
                listOf("title a", "Artist", "Album", "a"),
                listOf("title b", "Artist", "Album", "b"),
                listOf("title a", "Artist", "Album", "a"),
            ),
            PlaylistCsvFormat.records(songs).toList(),
        )
    }

    @Test
    fun `records join artists and fall back to empty album`() {
        val records = PlaylistCsvFormat.records(listOf(song("a", artists = listOf("A", "B"), album = null))).toList()

        assertEquals(listOf("title a", "A; B", "", "a"), records[1])
    }

    @Test
    fun `write uses BOM quoted fields CRLF and formula escaping`() {
        val writer = StringWriter()
        val records =
            sequenceOf(
                listOf("Plain", "A,B", "Say \"hi\"", "id"),
                listOf("=SUM(1)", " +cmd", "\t-data", "@ref", "id"),
            )

        PlaylistCsvFormat.write(writer, records)

        assertEquals(
            "\uFEFF\"Plain\",\"A,B\",\"Say \"\"hi\"\"\",\"id\"\r\n" +
                "\"'=SUM(1)\",\"' +cmd\",\"'\t-data\",\"'@ref\",\"id\"\r\n",
            writer.toString(),
        )
    }

    @Test
    fun `write does not escape a formula prefix after a non whitespace character`() {
        val writer = StringWriter()

        PlaylistCsvFormat.write(writer, sequenceOf(listOf("a=b", "id")))

        assertEquals("\uFEFF\"a=b\",\"id\"\r\n", writer.toString())
    }

    @Test
    fun `fileName sanitizes reserved characters trailing dots and blank names`() {
        assertEquals("My_playlist_.csv", PlaylistCsvFormat.fileName("  My/playlist:  "))
        assertEquals("playlist.csv", PlaylistCsvFormat.fileName("   ...  "))
        assertEquals("a_b.csv", PlaylistCsvFormat.fileName("a\tb"))
    }

    @Test
    fun `fileName truncates by code points and keeps emoji intact`() {
        val name = "x".repeat(95) + "\uD83C\uDFB5" + "tail"

        assertEquals("x".repeat(95) + "\uD83C\uDFB5.csv", PlaylistCsvFormat.fileName(name))
    }

    private class TestStore<D>(
        private val playlists: List<PlaylistExportPlaylist> = emptyList(),
        private val songs: List<PlaylistExportSong> = emptyList(),
    ) : PlaylistExportStore<D> {
        var writtenRecords: List<List<String>> = emptyList()
        var writeCalled = false

        override fun observePlaylists() = flowOf(playlists)

        override suspend fun loadPlaylistSongs(playlistId: String): List<PlaylistExportSong> = songs

        override suspend fun writeCsv(
            destination: D,
            records: Sequence<List<String>>,
        ) {
            writeCalled = true
            writtenRecords = records.toList()
        }
    }

    @Test
    fun `observe reverses playlist order filters empty playlists and suggests names`() = runTest {
        val store =
            TestStore<Unit>(
                playlists =
                    listOf(
                        PlaylistExportPlaylist("first", "First", 2),
                        PlaylistExportPlaylist("empty", "Empty", 0),
                        PlaylistExportPlaylist("last", "Last/One", 3),
                    ),
            )

        val observed = PlaylistExportCoordinator(store).observe().toList()

        assertEquals(1, observed.size)
        assertEquals(
            listOf(
                ExportablePlaylist("last", "Last/One", 3, "Last_One.csv"),
                ExportablePlaylist("first", "First", 2, "First.csv"),
            ),
            observed.single(),
        )
    }

    @Test
    fun `export rejects an empty playlist before opening storage`() = runTest {
        val store = TestStore<Unit>()

        assertFailsWith<IOException> { PlaylistExportCoordinator(store).export("empty", Unit) }
        assertEquals(false, store.writeCalled)
    }

    @Test
    fun `export hands ordered records including duplicates to storage`() = runTest {
        val store = TestStore<Unit>(songs = listOf(song("a"), song("b"), song("a")))

        PlaylistExportCoordinator(store).export("playlist", Unit)

        assertEquals(
            listOf(
                PlaylistCsvFormat.CSV_HEADER,
                listOf("title a", "Artist", "Album", "a"),
                listOf("title b", "Artist", "Album", "b"),
                listOf("title a", "Artist", "Album", "a"),
            ),
            store.writtenRecords,
        )
    }

    @Test
    fun `export propagates cancellation from the song provider`() = runTest {
        val store =
            object : PlaylistExportStore<Unit> {
                override fun observePlaylists() = flowOf(emptyList<PlaylistExportPlaylist>())

                override suspend fun loadPlaylistSongs(playlistId: String): List<PlaylistExportSong> =
                    throw CancellationException("cancelled")

                override suspend fun writeCsv(
                    destination: Unit,
                    records: Sequence<List<String>>,
                ) = error("must not write")
            }

        assertFailsWith<CancellationException> { PlaylistExportCoordinator(store).export("playlist", Unit) }
    }

    @Test
    fun `export propagates cancellation from storage`() = runTest {
        val store =
            object : PlaylistExportStore<Unit> {
                override fun observePlaylists() = flowOf(emptyList<PlaylistExportPlaylist>())

                override suspend fun loadPlaylistSongs(playlistId: String) = listOf(song("a"))

                override suspend fun writeCsv(
                    destination: Unit,
                    records: Sequence<List<String>>,
                ) = throw CancellationException("cancelled")
            }

        assertFailsWith<CancellationException> { PlaylistExportCoordinator(store).export("playlist", Unit) }
    }
}

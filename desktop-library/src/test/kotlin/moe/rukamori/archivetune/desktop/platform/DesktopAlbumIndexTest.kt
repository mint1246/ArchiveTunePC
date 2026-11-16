package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.playback.api.PlaybackAlbum

class DesktopAlbumIndexTest {
    @Test
    fun `close persists browsable albums and reloads them`() {
        val path = Files.createTempDirectory("album-index-test").resolve("albums.json")
        val album = PlaybackAlbum(id = "album-id", title = "Album")
        DesktopAlbumIndex(path).use { index ->
            index.learn("song-id", album)
            index.learn("missing-id", PlaybackAlbum(id = null, title = "Unbrowseable"))
            index.learn("", album)
        }

        DesktopAlbumIndex(path).use { restored ->
            assertEquals(album, restored.albumFor("song-id"))
            assertNull(restored.albumFor("missing-id"))
            assertNull(restored.albumFor(""))
        }
    }
}

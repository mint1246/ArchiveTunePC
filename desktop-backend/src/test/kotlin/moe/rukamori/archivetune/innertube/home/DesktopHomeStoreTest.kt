/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube.home

import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopHomeStoreTest {
    @Test
    fun `round trips account data and play history`() {
        val dir = Files.createTempDirectory("archivetune-home-store")
        val store = DesktopHomeStore(dir.resolve("home-store.json"))
        val song =
            SongItem(
                id = "song-1",
                title = "A Song",
                artists = listOf(Artist(id = "artist-1", name = "An Artist")),
                album = Album(id = "album-1", name = "An Album"),
                duration = 200,
                thumbnail = "https://example.com/a.jpg",
                explicit = true,
            )
        val album =
            AlbumItem(
                browseId = "album-1",
                playlistId = "pl-1",
                title = "An Album",
                artists = listOf(Artist(id = "artist-1", name = "An Artist")),
                year = 2020,
                thumbnail = "https://example.com/album.jpg",
            )
        val artist =
            ArtistItem(
                id = "artist-1",
                title = "An Artist",
                thumbnail = "https://example.com/artist.jpg",
                shuffleEndpoint = null,
                radioEndpoint = null,
            )

        store.replaceLikedSongs(listOf(song))
        store.replaceLikedAlbums(listOf(album))
        store.replaceSubscribedArtists(listOf(artist))
        store.recordPlay(DesktopPlayEvent(song, playedAtEpochMillis = 1_000L, playTimeMs = 60_000L))

        val reloaded = DesktopHomeStore(dir.resolve("home-store.json")).snapshot.value
        assertEquals(1, reloaded.likedSongs.size)
        assertEquals("A Song", reloaded.likedSongs.first().title)
        assertEquals(1, reloaded.likedAlbums.size)
        assertEquals(1, reloaded.subscribedArtists.size)
        assertEquals(1, reloaded.playHistory.size)
        assertEquals(60_000L, reloaded.playHistory.first().playTimeMs)
    }

    @Test
    fun `computes quick picks and forgotten favorites`() {
        val now = System.currentTimeMillis()
        val song = SongItem(id = "s1", title = "Old Favourite", artists = emptyList(), thumbnail = "t")
        val snapshot =
            DesktopHomeSnapshot(
                likedSongs = listOf(song),
                playHistory =
                    listOf(
                        DesktopPlayEvent(song, playedAtEpochMillis = now - 40L * 86400_000L, playTimeMs = 600_000L),
                        DesktopPlayEvent(song, playedAtEpochMillis = now, playTimeMs = 1_000L),
                    ),
            )

        val content = computeLocalHomeContent(snapshot, now)

        assertTrue(content.quickPicks.isNotEmpty())
        assertEquals(listOf("s1"), content.forgottenFavorites.map { it.id })
    }

    @Test
    fun `toggles and persists speed dial items`() {
        val dir = Files.createTempDirectory("archivetune-home-store")
        val store = DesktopHomeStore(dir.resolve("home-store.json"))
        val song = SongItem(id = "s1", title = "Pinned Song", artists = emptyList(), thumbnail = "t")
        val playlist =
            PlaylistItem(
                id = "p1",
                title = "Pinned Playlist",
                author = null,
                songCountText = null,
                thumbnail = "t",
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )

        store.toggleSpeedDial(song)
        store.toggleSpeedDial(playlist)

        assertTrue(store.isSpeedDialPinned(song))
        assertTrue(store.isSpeedDialPinned(playlist))

        val reloaded = DesktopHomeStore(dir.resolve("home-store.json"))
        assertEquals(2, reloaded.snapshot.value.speedDial.size)

        reloaded.toggleSpeedDial(song)
        assertTrue(!reloaded.isSpeedDialPinned(song))
        assertEquals(1, reloaded.snapshot.value.speedDial.size)
    }
}

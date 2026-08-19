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
import org.junit.Assert.assertSame
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

    @Test
    fun `orders new releases by favourite then most played artist`() {
        val album = AlbumItem(
            browseId = "album-1",
            playlistId = "pl-1",
            title = "Album",
            artists = listOf(Artist(id = "artist-1", name = "Artist")),
            thumbnail = "t",
        )
        val favourite =
            ArtistItem(
                id = "fav",
                title = "Favourite",
                thumbnail = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
        val mostPlayed = SongItem(
            id = "song-1",
            title = "Song",
            artists = listOf(Artist(id = "played", name = "Played")),
            thumbnail = "t",
        )
        val snapshot =
            DesktopHomeSnapshot(
                subscribedArtists = listOf(favourite),
                playHistory = listOf(DesktopPlayEvent(mostPlayed, playedAtEpochMillis = 1_000L, playTimeMs = 10_000L)),
            )

        val favouriteAlbum = album.copy(browseId = "fav", playlistId = "pl-fav", artists = listOf(Artist("Favourite", "fav")))
        val playedAlbum = album.copy(browseId = "played", playlistId = "pl-played", artists = listOf(Artist("Played", "played")))
        val unknownAlbum = album.copy(browseId = "unknown", playlistId = "pl-unknown", artists = listOf(Artist("Unknown", "unknown")))

        val sorted =
            sortNewReleasesByArtistPreference(
                listOf(unknownAlbum, playedAlbum, favouriteAlbum),
                snapshot,
            )

        assertEquals(listOf("fav", "played", "unknown"), sorted.map { it.browseId })
    }

    @Test
    fun `precise account mutations update local snapshot without a full resync`() {
        val dir = Files.createTempDirectory("archivetune-home-store")
        val store = DesktopHomeStore(dir.resolve("home-store.json"))
        val song = SongItem(id = "song", title = "Song", artists = emptyList(), thumbnail = "t")
        val artist = ArtistItem(
            id = "artist",
            title = "Artist",
            thumbnail = null,
            channelId = "channel",
            shuffleEndpoint = null,
            radioEndpoint = null,
        )

        store.setSongLiked(song, true)
        store.setArtistSubscribed(artist, true)
        assertEquals(listOf("song"), store.snapshot.value.likedSongs.map { it.id })
        assertEquals(listOf("artist"), store.snapshot.value.subscribedArtists.map { it.id })

        store.setSongLiked(song, false)
        store.setArtistSubscribed(artist, false)
        assertTrue(store.snapshot.value.likedSongs.isEmpty())
        assertTrue(store.snapshot.value.subscribedArtists.isEmpty())
    }

    @Test
    fun `history and related caches stay memory bounded`() {
        val dir = Files.createTempDirectory("archivetune-home-store")
        val store = DesktopHomeStore(dir.resolve("home-store.json"), asyncPersistence = true)
        val first = SongItem(id = "same", title = "Old", artists = emptyList(), thumbnail = "t")
        val newest = first.copy(title = "Newest")

        store.recordPlay(DesktopPlayEvent(first, playedAtEpochMillis = 1_000L, playTimeMs = 10_000L))
        store.recordPlay(DesktopPlayEvent(newest, playedAtEpochMillis = 2_000L, playTimeMs = 10_000L))
        val events = store.snapshot.value.playHistory
        assertSame(events[0].song, events[1].song)
        assertEquals("Newest", events[0].song.title)

        repeat(70) { seed ->
            store.replaceRelatedSongs(
                "seed-$seed",
                List(105) { song -> SongItem(id = "$seed-$song", title = "Song", artists = emptyList(), thumbnail = "t") },
            )
        }
        assertEquals(64, store.snapshot.value.relatedSongsBySeed.size)
        assertTrue(store.snapshot.value.relatedSongsBySeed.values.all { it.size <= 100 })
        store.close()
    }
}

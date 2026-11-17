/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube.home

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.pages.ArtistPage
import moe.rukamori.archivetune.innertube.pages.ArtistSection
import moe.rukamori.archivetune.innertube.pages.ArtistSectionLayout
import moe.rukamori.archivetune.innertube.pages.LibraryPage
import moe.rukamori.archivetune.innertube.pages.NextResult
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.innertube.pages.RelatedPage
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPersonalizationCoordinatorTest {
    @Test
    fun `syncHomeLibrary clears account data and recommendations when signed out`() = runBlocking {
        val dir = Files.createTempDirectory("archivetune-personalization-signed-out")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val homeStore = DesktopHomeStore(dir.resolve("home-store.json"))
            val coordinator = DesktopResourceCoordinator(cacheDirectory = dir.resolve("cache"))
            val backend = FakePersonalizationBackend()
            var signedIn = true
            val personalization = DesktopPersonalizationCoordinator(
                backend = backend,
                homeStore = homeStore,
                coordinator = coordinator,
                scope = scope,
                accountScopeProvider = { "account-1" },
                isSignedInProvider = { signedIn },
            )

            val likedSong = song("liked-1", "Liked", "artist-1")
            val artist = ArtistItem(id = "artist-1", title = "Artist 1", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)
            backend.likedSongsResult = Result.success(
                PlaylistPage(
                    playlist = PlaylistItem(id = "LM", title = "Liked Music", author = null, songCountText = null, thumbnail = null, playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null),
                    songs = listOf(likedSong),
                    songsContinuation = null,
                    continuation = null,
                ),
            )
            backend.artistPages["artist-1"] = ArtistPage(
                artist = artist,
                sections = listOf(ArtistSection("Top songs", listOf(likedSong), null, ArtistSectionLayout.LIST)),
                description = null,
            )
            homeStore.recordPlay(DesktopPlayEvent(likedSong, playedAtEpochMillis = 1_000L, playTimeMs = 60_000L))

            personalization.syncHomeLibrary()
            personalization.loadSimilarRecommendations()
            assertEquals(listOf(likedSong), homeStore.snapshot.value.likedSongs)
            assertEquals(1, personalization.similarRecommendations.value.size)

            signedIn = false
            personalization.syncHomeLibrary()
            assertTrue(homeStore.snapshot.value.likedSongs.isEmpty())
            assertTrue(personalization.similarRecommendations.value.isEmpty())
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cacheRelatedSongsIfNeeded deduplicates concurrent fetches for the same video`() = runBlocking {
        val dir = Files.createTempDirectory("archivetune-personalization-dedupe")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val homeStore = DesktopHomeStore(dir.resolve("home-store.json"))
            val coordinator = DesktopResourceCoordinator(cacheDirectory = dir.resolve("cache"))
            val gate = CompletableDeferred<Unit>()
            val backend = FakePersonalizationBackend(nextGate = gate)
            val relatedSong = song("rel-1", "Related Song", "artist-rel")
            val endpoint = BrowseEndpoint(browseId = "MPRE_rel")
            backend.nextResults["seed-1"] = NextResult(
                items = emptyList(),
                relatedEndpoint = endpoint,
                continuation = null,
                endpoint = WatchEndpoint(videoId = "seed-1"),
            )
            backend.relatedPages[endpoint.browseId] = RelatedPage(
                songs = listOf(relatedSong),
                albums = emptyList(),
                artists = emptyList(),
                playlists = emptyList(),
            )

            val personalization = DesktopPersonalizationCoordinator(
                backend = backend,
                homeStore = homeStore,
                coordinator = coordinator,
                scope = scope,
                accountScopeProvider = { "account-1" },
                isSignedInProvider = { true },
            )

            val first = async { personalization.cacheRelatedSongsIfNeeded("seed-1") }
            val second = async { personalization.cacheRelatedSongsIfNeeded("seed-1") }
            gate.complete(Unit)
            awaitAll(first, second)

            assertEquals(1, backend.nextCalls.get())
            assertEquals(1, backend.relatedCalls.get())
            assertEquals(listOf(relatedSong), homeStore.snapshot.value.relatedSongsBySeed["seed-1"])

            // Already cached in homeStore: subsequent calls do not hit backend.
            personalization.cacheRelatedSongsIfNeeded("seed-1")
            assertEquals(1, backend.nextCalls.get())
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `play history enforces 5s minimum and 30m maximum bounds`() = runBlocking {
        val dir = Files.createTempDirectory("archivetune-personalization-history")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val homeStore = DesktopHomeStore(dir.resolve("home-store.json"))
            val coordinator = DesktopResourceCoordinator(cacheDirectory = dir.resolve("cache"))
            val backend = FakePersonalizationBackend()
            var nowMs = 10_000L
            val personalization = DesktopPersonalizationCoordinator(
                backend = backend,
                homeStore = homeStore,
                coordinator = coordinator,
                scope = scope,
                accountScopeProvider = { "account-1" },
                isSignedInProvider = { true },
                now = { nowMs },
            )

            // Under 5s -> ignored
            personalization.onMediaTransition(playbackItem("short-1", "Short"), atEpochMillis = 10_000L)
            personalization.flushPlayHistoryCurrent(atEpochMillis = 14_999L)
            assertTrue(homeStore.snapshot.value.playHistory.isEmpty())

            // 2 hours -> clamped to 1_800_000ms
            personalization.onMediaTransition(playbackItem("long-1", "Long"), atEpochMillis = 20_000L)
            personalization.flushPlayHistoryCurrent(atEpochMillis = 20_000L + 7_200_000L)
            val recorded = homeStore.snapshot.value.playHistory.single()
            assertEquals("long-1", recorded.song.id)
            assertEquals(20_000L, recorded.playedAtEpochMillis)
            assertEquals(1_800_000L, recorded.playTimeMs)
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadSimilarRecommendations assembles artist and song shelves from most played history`() = runBlocking {
        val dir = Files.createTempDirectory("archivetune-personalization-similar")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val homeStore = DesktopHomeStore(dir.resolve("home-store.json"))
            val coordinator = DesktopResourceCoordinator(cacheDirectory = dir.resolve("cache"))
            val backend = FakePersonalizationBackend()

            val topSong = song("s-top", "Top Song", "a-top")
            val secondSong = song("s-2", "Second Song", "a-2")
            val topArtist = ArtistItem(id = "a-top", title = "Top Artist", thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)
            val recSong = song("s-rec", "Recommended", "a-rec")
            val relatedSong = song("s-rel", "Related Track", "a-rel")
            val endpoint = BrowseEndpoint(browseId = "MPRE_top")

            homeStore.recordPlay(DesktopPlayEvent(topSong, 1_000L, 120_000L))
            homeStore.recordPlay(DesktopPlayEvent(secondSong, 2_000L, 30_000L))

            backend.artistPages["a-top"] = ArtistPage(
                artist = topArtist,
                sections = listOf(
                    ArtistSection("Older", listOf(song("old", "Old", "a-top")), null, ArtistSectionLayout.LIST),
                    ArtistSection("Popular", listOf(recSong), null, ArtistSectionLayout.LIST),
                ),
                description = null,
            )
            backend.nextResults["s-top"] = NextResult(
                items = emptyList(),
                relatedEndpoint = endpoint,
                continuation = null,
                endpoint = WatchEndpoint(videoId = "s-top"),
            )
            backend.relatedPages[endpoint.browseId] = RelatedPage(
                songs = listOf(relatedSong),
                albums = emptyList(),
                artists = emptyList(),
                playlists = emptyList(),
            )

            val personalization = DesktopPersonalizationCoordinator(
                backend = backend,
                homeStore = homeStore,
                coordinator = coordinator,
                scope = scope,
                accountScopeProvider = { "account-1" },
                isSignedInProvider = { true },
            )
            personalization.loadSimilarRecommendations()

            val shelves = personalization.similarRecommendations.value
            assertEquals(2, shelves.size)
            assertEquals(topArtist, shelves[0].title)
            assertTrue(shelves[0].items.contains(recSong))
            assertEquals(topSong, shelves[1].title)
            assertEquals(listOf(relatedSong), shelves[1].items)
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    private fun song(id: String, title: String, artistId: String) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(id = artistId, name = "Artist $artistId")),
        album = Album(id = "album-$id", name = "Album $id"),
        duration = 180,
        thumbnail = "https://example.com/$id.jpg",
    )

    private fun playbackItem(id: String, title: String) = PlaybackItem(
        PlaybackMetadata(
            id = id,
            title = title,
            artists = listOf(PlaybackArtist(id = "artist-$id", name = "Artist")),
            album = PlaybackAlbum(id = "album-$id", title = "Album"),
            durationHintMs = 180_000L,
        ),
    )

    private class FakePersonalizationBackend(
        private val nextGate: CompletableDeferred<Unit>? = null,
    ) : DesktopPersonalizationBackend {
        val nextCalls = AtomicInteger(0)
        val relatedCalls = AtomicInteger(0)

        var likedSongsResult: Result<PlaylistPage> = Result.failure(IllegalStateException("unused"))
        var librarySongsResult: Result<LibraryPage> = Result.success(LibraryPage(emptyList(), null))
        var libraryAlbumsResult: Result<LibraryPage> = Result.success(LibraryPage(emptyList(), null))
        var libraryArtistsResult: Result<LibraryPage> = Result.success(LibraryPage(emptyList(), null))
        val artistPages = mutableMapOf<String, ArtistPage>()
        val nextResults = mutableMapOf<String, NextResult>()
        val relatedPages = mutableMapOf<String, RelatedPage>()

        override suspend fun likedSongs(): Result<PlaylistPage> = likedSongsResult
        override suspend fun librarySongs(): Result<LibraryPage> = librarySongsResult
        override suspend fun libraryAlbums(): Result<LibraryPage> = libraryAlbumsResult
        override suspend fun libraryArtists(): Result<LibraryPage> = libraryArtistsResult

        override suspend fun artist(browseId: String): Result<ArtistPage> =
            artistPages[browseId]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException(browseId))

        override suspend fun next(videoId: String): Result<NextResult> {
            nextCalls.incrementAndGet()
            nextGate?.await()
            return nextResults[videoId]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException(videoId))
        }

        override suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> {
            relatedCalls.incrementAndGet()
            return relatedPages[endpoint.browseId]?.let { Result.success(it) } ?: Result.failure(NoSuchElementException(endpoint.browseId))
        }
    }
}

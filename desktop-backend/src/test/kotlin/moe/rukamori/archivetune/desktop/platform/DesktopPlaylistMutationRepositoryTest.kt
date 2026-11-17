package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.home.DesktopLibraryRepository
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint

class DesktopPlaylistMutationRepositoryTest {
    @Test
    fun `rename failure rolls back full PlaylistItem metadata`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = libraryRepository(scope) { "account-a" }
            library.onAccountScopeChanged()
            val original = richPlaylist("pl-1", "Original Title")
            library.upsertCached(original)

            val browse = RecordingBrowseEffects()
            val backend = RecordingMutationBackend(
                renameHandler = { _, _ -> Result.failure(IllegalStateException("rename failed")) },
            )
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { "account-a" },
                accountEpochProvider = { 0L },
            )

            val result = repository.renamePlaylist(original, "New Title")

            assertTrue(result.isFailure)
            assertEquals(listOf(original), library.state.value.playlists)
            assertTrue(browse.events.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rename success refreshes playlist`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = libraryRepository(scope) { "account-a" }
            library.onAccountScopeChanged()
            val original = richPlaylist("pl-1", "Original Title")
            library.upsertCached(original)

            val browse = RecordingBrowseEffects()
            val backend = RecordingMutationBackend()
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { "account-a" },
                accountEpochProvider = { 0L },
            )

            val result = repository.renamePlaylist(original, "Renamed")

            assertTrue(result.isSuccess)
            assertEquals("Renamed", library.state.value.playlists.single().title)
            assertEquals(listOf("refresh:pl-1"), browse.events)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `create and add preserve track order and duplicates`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = libraryRepository(scope) { "account-a" }
            library.onAccountScopeChanged()
            val browse = RecordingBrowseEffects(openPlaylistId = "pl-open")
            val backend = RecordingMutationBackend(
                createHandler = { _, _ -> Result.success("pl-created") },
            )
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { "account-a" },
                accountEpochProvider = { 0L },
            )

            val songs = listOf(
                song("v-2", setVideoId = "sv-1"),
                song("v-1", setVideoId = "sv-2"),
                song("v-2", setVideoId = "sv-3"),
            )

            val created = repository.createPlaylist("Mix", songs)
            assertEquals("pl-created", created.getOrNull())
            assertEquals(listOf("v-2", "v-1", "v-2"), backend.lastCreatedVideoIds)
            assertEquals("Mix", library.state.value.playlists.single { it.id == "pl-created" }.title)

            val addedToClosed = repository.addSongsToPlaylist("pl-closed", songs)
            assertTrue(addedToClosed.isSuccess)
            assertEquals(listOf("v-2", "v-1", "v-2"), backend.lastAddedVideoIds)
            assertEquals(listOf("invalidate:pl-closed"), browse.events)

            browse.events.clear()
            val addedToOpen = repository.addSongsToPlaylist("pl-open", songs)
            assertTrue(addedToOpen.isSuccess)
            assertEquals(listOf("refresh:pl-open"), browse.events)

            browse.events.clear()
            val addedAfterPickerDismissal = repository.addSongsToPlaylist("pl-open", songs) { false }
            assertTrue(addedAfterPickerDismissal.isSuccess)
            assertTrue(browse.events.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `remove preserves SongItem setVideoId and refreshes playlist`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = libraryRepository(scope) { "account-a" }
            library.onAccountScopeChanged()
            val browse = RecordingBrowseEffects()
            val backend = RecordingMutationBackend()
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { "account-a" },
                accountEpochProvider = { 0L },
            )
            val target = song("v-1", setVideoId = "sv-second-occurrence")

            val result = repository.removeSongFromPlaylist("pl-1", target)

            assertTrue(result.isSuccess)
            assertEquals(target, backend.lastRemovedSong)
            assertEquals(listOf("refresh:pl-1"), browse.events)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `delete invalidates browse, removes cached playlist, and clears stack`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = libraryRepository(scope) { "account-a" }
            library.onAccountScopeChanged()
            library.upsertCached(richPlaylist("pl-1", "To Delete"))
            val browse = RecordingBrowseEffects()
            val backend = RecordingMutationBackend()
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { "account-a" },
                accountEpochProvider = { 0L },
            )

            val result = repository.deletePlaylist("pl-1")

            assertTrue(result.isSuccess)
            assertTrue(library.state.value.playlists.isEmpty())
            assertEquals(listOf("invalidate:pl-1", "clearStack"), browse.events)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `account switch across suspension blocks stale cache and browse writes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            var currentAccount = "account-a"
            val library = libraryRepository(scope) { currentAccount }
            library.onAccountScopeChanged()
            val originalA = richPlaylist("pl-a", "Account A Playlist")
            library.upsertCached(originalA)

            val browse = RecordingBrowseEffects()
            val backend = RecordingMutationBackend(
                renameHandler = { _, _ ->
                    started.complete(Unit)
                    release.await()
                    Result.failure(IllegalStateException("late failure"))
                },
            )
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { currentAccount },
                accountEpochProvider = { 0L },
            )

            val inFlight = async { repository.renamePlaylist(originalA, "Temp") }
            withTimeout(2_000) { started.await() }

            currentAccount = "account-b"
            library.onAccountScopeChanged()
            val accountBPlaylist = richPlaylist("pl-b", "Account B Playlist")
            library.upsertCached(accountBPlaylist)

            release.complete(Unit)
            val result = inFlight.await()

            assertTrue(result.isFailure)
            assertEquals("account-b", library.state.value.accountScope)
            assertEquals(listOf(accountBPlaylist), library.state.value.playlists)
            assertTrue(browse.events.isEmpty())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `epoch change across same-account bounce blocks stale writes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val epoch = AtomicLong(1L)
            val library = libraryRepository(scope) { "account-a" }
            library.onAccountScopeChanged()
            val browse = RecordingBrowseEffects()
            val backend = RecordingMutationBackend(
                createHandler = { _, _ ->
                    started.complete(Unit)
                    release.await()
                    Result.success("pl-stale")
                },
            )
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { "account-a" },
                accountEpochProvider = epoch::get,
            )

            val inFlight = async { repository.createPlaylist("Stale", emptyList<SongItem>()) }
            withTimeout(2_000) { started.await() }

            // Simulate A -> B -> A bounce before the suspended request completes.
            epoch.incrementAndGet()
            release.complete(Unit)
            val result = inFlight.await()

            assertEquals("pl-stale", result.getOrNull())
            assertNull(library.state.value.playlists.firstOrNull { it.id == "pl-stale" })
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `cancellation propagates and rolls back optimistic rename when same account`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        try {
            val library = libraryRepository(scope) { "account-a" }
            library.onAccountScopeChanged()
            val original = richPlaylist("pl-1", "Original")
            library.upsertCached(original)

            val browse = RecordingBrowseEffects()
            val backend = RecordingMutationBackend(
                renameHandler = { _, _ ->
                    started.complete(Unit)
                    gate.await()
                    Result.success(Unit)
                },
            )
            val repository = DesktopPlaylistMutationRepository(
                backend = backend,
                libraryRepository = library,
                browseEffects = browse,
                accountScopeProvider = { "account-a" },
                accountEpochProvider = { 0L },
            )

            val job = async { repository.renamePlaylist(original, "Optimistic") }
            withTimeout(2_000) { started.await() }
            assertEquals("Optimistic", library.state.value.playlists.single().title)

            job.cancel(CancellationException("user navigated away"))
            assertFailsWith<CancellationException> { job.await() }
            assertEquals(listOf(original), library.state.value.playlists)
            assertTrue(browse.events.isEmpty())
        } finally {
            gate.complete(Unit)
            scope.cancel()
        }
    }

    private fun libraryRepository(
        scope: CoroutineScope,
        accountScopeProvider: () -> String,
    ) = DesktopLibraryRepository.forTesting(
        coordinator = DesktopResourceCoordinator(Files.createTempDirectory("archivetune-playlist-mutation-test")),
        scope = scope,
        accountScopeProvider = accountScopeProvider,
        playlistLoader = { Result.success(emptyList()) },
    )

    private fun richPlaylist(id: String, title: String) = PlaylistItem(
        id = id,
        title = title,
        author = Artist(name = "Channel Name", id = "UC123"),
        songCountText = "42 songs",
        thumbnail = "https://example.invalid/thumb.jpg",
        playEndpoint = WatchEndpoint(videoId = "v-play", playlistId = id),
        shuffleEndpoint = WatchEndpoint(videoId = "v-shuffle", playlistId = id, params = "shuffle"),
        radioEndpoint = WatchEndpoint(videoId = "v-radio", playlistId = "RD$id"),
        isEditable = true,
        description = "Full metadata description",
    )

    private fun song(id: String, setVideoId: String) = SongItem(
        id = id,
        title = "Song $id",
        artists = listOf(Artist(name = "Artist", id = "AR1")),
        thumbnail = "https://example.invalid/$id.jpg",
        setVideoId = setVideoId,
    )

    private class RecordingBrowseEffects(
        private val openPlaylistId: String? = null,
    ) : DesktopPlaylistBrowseEffects {
        val events = mutableListOf<String>()

        override fun invalidatePlaylist(playlistId: String) {
            events += "invalidate:$playlistId"
        }

        override fun refreshPlaylist(playlistId: String) {
            events += "refresh:$playlistId"
        }

        override fun clearCurrentStack() {
            events += "clearStack"
        }

        override fun currentlyOpenPlaylistId(): String? = openPlaylistId
    }

    private class RecordingMutationBackend(
        private val createHandler: suspend (String, List<String>) -> Result<String> = { _, _ -> Result.success("created-id") },
        private val renameHandler: suspend (String, String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        private val deleteHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) },
        private val addHandler: suspend (String, List<String>) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        private val removeHandler: suspend (String, SongItem) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    ) : DesktopPlaylistMutationBackend {
        var lastCreatedVideoIds: List<String>? = null
        var lastAddedVideoIds: List<String>? = null
        var lastRemovedSong: SongItem? = null

        override suspend fun createPlaylist(name: String, videoIds: List<String>): Result<String> {
            lastCreatedVideoIds = videoIds
            return createHandler(name, videoIds)
        }

        override suspend fun renamePlaylist(playlistId: String, name: String): Result<Unit> =
            renameHandler(playlistId, name)

        override suspend fun deletePlaylist(playlistId: String): Result<Unit> =
            deleteHandler(playlistId)

        override suspend fun addSongsToPlaylist(playlistId: String, videoIds: List<String>): Result<Unit> {
            lastAddedVideoIds = videoIds
            return addHandler(playlistId, videoIds)
        }

        override suspend fun removeSongFromPlaylist(playlistId: String, song: SongItem): Result<Unit> {
            lastRemovedSong = song
            return removeHandler(playlistId, song)
        }
    }
}

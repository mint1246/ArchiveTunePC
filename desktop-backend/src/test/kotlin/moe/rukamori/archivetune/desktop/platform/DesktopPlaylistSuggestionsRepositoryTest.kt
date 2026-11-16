package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaylistSuggestionsRepositoryTest {
    @Test
    fun `filters playlist songs duplicates and podcast entries`() {
        val result = filterPlaylistSuggestionItems(
            candidates = listOf(song("kept"), song("existing"), song("kept"), song("episode", podcast = true)),
            excludedIds = setOf("existing"),
        )

        assertEquals(listOf("kept"), result.map(SongItem::id))
    }

    @Test
    fun `cached query page persists and remains isolated by account`() = runBlocking {
        val directory = Files.createTempDirectory("playlist-suggestions-test")
        val coordinator = DesktopResourceCoordinator(directory)
        val backend = FakeSuggestionsBackend()
        val repository = DesktopPlaylistSuggestionsRepository(backend, coordinator, { "account-a" })

        repository.activate("playlist", "Road trip", emptyList())
        assertEquals(1, backend.searchCount)
        val restored = DesktopPlaylistSuggestionsRepository(backend, coordinator, { "account-a" })
        restored.activate("playlist", "Road trip", emptyList())
        assertEquals(1, backend.searchCount, "warm page should be restored from the persistent cache")
        assertTrue(restored.state.value.items.isNotEmpty())

        val otherAccount = DesktopPlaylistSuggestionsRepository(backend, coordinator, { "account-b" })
        otherAccount.activate("playlist", "Road trip", emptyList())
        assertEquals(2, backend.searchCount, "another account must not reuse account-a's suggestions")
    }

    @Test
    fun `add keeps visible order and removes added songs from suggestions`() = runBlocking {
        val coordinator = DesktopResourceCoordinator(Files.createTempDirectory("playlist-suggestions-add-test"))
        val backend = FakeSuggestionsBackend()
        val repository = DesktopPlaylistSuggestionsRepository(backend, coordinator, { "account" })
        repository.activate("playlist", "Road trip", listOf(song("already-in-playlist")))

        val result = repository.addSuggestions(
            "playlist",
            listOf(song("second"), song("first"), song("second"), song("already-in-playlist")),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("second", "first"), backend.addedBatches.single())
        assertFalse(repository.state.value.items.any { it.id == "second" || it.id == "first" })
    }

    @Test
    fun `continuation filters podcast results and duplicates across fetched pages`() = runBlocking {
        val coordinator = DesktopResourceCoordinator(Files.createTempDirectory("playlist-suggestions-pages-test"))
        val backend = FakeSuggestionsBackend()
        backend.continuationPages += SearchResult(listOf(song("third"), song("episode", podcast = true), song("four"), song("four")), "page-2")
        backend.continuationPages += SearchResult(listOf(song("four"), song("five")))
        val repository = DesktopPlaylistSuggestionsRepository(backend, coordinator, { "account" })
        repository.activate("playlist", "Road trip", emptyList())

        repository.loadMore()
        assertEquals(listOf("second", "first", "third", "four"), repository.state.value.items.map(SongItem::id))
        repository.loadMore()
        assertEquals(listOf("second", "first", "third", "four", "five"), repository.state.value.items.map(SongItem::id))
    }

    @Test
    fun `account switch drops an in flight old account page`() = runBlocking {
        var account = "account-a"
        val coordinator = DesktopResourceCoordinator(Files.createTempDirectory("playlist-suggestions-switch-test"))
        val backend = FakeSuggestionsBackend()
        val gate = CompletableDeferred<Unit>()
        backend.searchGate = gate
        val repository = DesktopPlaylistSuggestionsRepository(backend, coordinator, { account })

        val oldRequest = async { repository.activate("playlist", "Road trip", emptyList()) }
        backend.searchStarted.await()
        account = "account-b"
        val accountSwitch = async(start = CoroutineStart.UNDISPATCHED) { repository.onAccountScopeChanged() }
        gate.complete(Unit)
        oldRequest.await()
        accountSwitch.await()

        assertEquals("account-b", repository.accountScope.value)
        assertTrue(repository.state.value.items.isEmpty())
        backend.searchGate = null
        repository.activate("playlist", "Road trip", emptyList())
        assertEquals(2, backend.searchCount, "the new account needs its own fresh search page")
    }

    private class FakeSuggestionsBackend : DesktopPlaylistSuggestionsBackend {
        var searchCount = 0
        val addedBatches = mutableListOf<List<String>>()
        var searchGate: CompletableDeferred<Unit>? = null
        val searchStarted = CompletableDeferred<Unit>()
        val continuationPages = mutableListOf<SearchResult>()

        override suspend fun search(query: String): Result<SearchResult> {
            searchCount++
            searchStarted.complete(Unit)
            searchGate?.await()
            return Result.success(SearchResult(listOf(song("second"), song("first"), song("third")), "page-1"))
        }

        override suspend fun continuation(token: String): Result<SearchResult> =
            Result.success(if (continuationPages.isNotEmpty()) continuationPages.removeAt(0) else SearchResult(emptyList()))

        override suspend fun addSongs(playlistId: String, videoIds: List<String>): Result<Unit> {
            addedBatches += videoIds
            return Result.success(Unit)
        }
    }

    private companion object {
        fun song(id: String, podcast: Boolean = false) = SongItem(
            id = id,
            title = "Song $id",
            artists = listOf(Artist("Artist", null)),
            thumbnail = "",
            isPodcast = podcast,
        )
    }
}

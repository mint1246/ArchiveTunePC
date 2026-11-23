package moe.rukamori.archivetune.shared

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MusicCollectionsTest {
    private class Store(var value: String? = null) : MusicStore {
        override suspend fun read() = value
        override suspend fun write(json: String) { value = json }
    }

    @Test
    fun playlistsPersistOrderDuplicatesAndOccurrenceRemoval() = runTest {
        val store = Store()
        val library = MusicLibrary(store, backgroundScope)
        val id = requireNotNull(library.createPlaylist("  Mix  "))
        val other = requireNotNull(library.createPlaylist("Other"))
        assertNotEquals(id, other)
        val a = MusicTrack("a", "A")
        val b = MusicTrack("b", "B")
        library.addToPlaylist(id, a)
        library.addToPlaylist(id, b)
        library.addToPlaylist(id, a)
        library.removeFromPlaylist(id, 0)
        library.renamePlaylist(id, "Renamed")
        library.deletePlaylist(other)
        library.close()
        val reopened = MusicLibrary(store, backgroundScope)
        reopened.awaitLoaded()
        assertEquals(listOf(MusicPlaylist(id, "Renamed", listOf(b, a))), reopened.state.value.playlists)
        reopened.close()
    }

    @Test
    fun editsBeforeReadAreReplayedOverExistingPlaylists() = runTest {
        val read = CompletableDeferred<String?>()
        var saved: String? = null
        val store = object : MusicStore {
            override suspend fun read() = read.await()
            override suspend fun write(json: String) { saved = json }
        }
        val library = MusicLibrary(store, backgroundScope)
        library.renamePlaylist("old", "Updated")
        val id = requireNotNull(library.createPlaylist("New"))
        library.addToPlaylist(id, MusicTrack("a", "A"))
        read.complete("""{"version":1,"playlists":[{"id":"old","name":"Original","tracks":[]}]}""")
        library.close()
        val reopened = MusicLibrary(Store(saved), backgroundScope)
        reopened.awaitLoaded()
        assertEquals(listOf("Updated", "New"), reopened.state.value.playlists.map { it.name })
        assertEquals(listOf("a"), reopened.state.value.playlists.last().tracks.map { it.id })
        reopened.close()
    }
}

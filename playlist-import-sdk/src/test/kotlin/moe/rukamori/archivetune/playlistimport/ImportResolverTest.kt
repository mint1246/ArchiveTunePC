/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlistimport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

data class TestSong(val id: String, val title: String, val artist: String)

class ImportResolverTest {
    private fun describe(song: TestSong) = ImportSongDescription(song.title, listOf(song.artist))

    @Test
    fun `remote results preserve occurrences and source order despite out of order completion`() = runTest {
        val first = TestSong("1", "first", "artist")
        val second = TestSong("2", "second", "artist")
        val progress = mutableListOf<Int>()
        val resolver = ImportResolver(::describe, search = { query ->
            if (query.startsWith("first")) delay(100) else delay(10)
            if (query.startsWith("first")) listOf(first) else listOf(second)
        })
        val results = resolver.resolve(listOf(first, second, first), emptyList(), false) { completed, _ ->
            progress += completed
        }
        assertEquals(listOf(first, second, first), results.map { it.original })
        assertEquals(listOf(first, second, first), results.map { it.match })
        assertEquals(listOf(1, 2, 3), progress)
        assertTrue(results.all { it.source == ImportMatchSource.YOUTUBE })
    }

    @Test
    fun `remote resolutions respect the configured concurrency limit`() = runTest {
        var active = 0
        var maximumActive = 0
        val resolver = ImportResolver(::describe, search = {
            active++
            maximumActive = maxOf(maximumActive, active)
            try {
                delay(10)
                emptyList()
            } finally {
                active--
            }
        }, maxConcurrentResolutions = 2)
        resolver.resolve(List(5) { TestSong("$it", "song $it", "artist") }, emptyList(), false)
        assertEquals(2, maximumActive)
        assertEquals(0, active)
    }

    @Test
    fun `local match avoids remote search and failed remote reports unresolved`() = runTest {
        val local = TestSong("local", "Beyoncé", "Artist")
        val original = TestSong("original", "Beyonce", "Artist")
        val missing = TestSong("missing", "missing song", "Artist")
        val failures = mutableListOf<String>()
        val resolver = ImportResolver(::describe, search = { error("offline") }, onSearchFailure = { song, _ ->
            failures += song.id
        })
        val results = resolver.resolve(listOf(original, missing), listOf(local), true)
        assertEquals(ImportMatchSource.LOCAL, results[0].source)
        assertEquals(local, results[0].match)
        assertEquals(ImportMatchSource.UNRESOLVED, results[1].source)
        assertEquals(listOf("missing"), failures)
    }

    @Test
    fun `cancellation propagates without being reported as failure`() = runTest {
        var reported = false
        val resolver = ImportResolver(::describe, search = { throw CancellationException("cancelled") }, onSearchFailure = { _, _ ->
            reported = true
        })
        assertFailsWith<CancellationException> {
            resolver.resolve(listOf(TestSong("1", "song", "artist")), emptyList(), false)
        }
        assertEquals(false, reported)
    }

    @Test
    fun `query decodes artist names and tolerates malformed escapes`() {
        assertEquals("Song - A B %bad%", buildImportSongQuery(ImportSongDescription(" Song ", listOf("A+B", "%bad%"))))
    }
}

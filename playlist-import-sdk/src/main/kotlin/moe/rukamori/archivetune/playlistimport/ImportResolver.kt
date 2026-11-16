/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlistimport

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ImportSongDescription(val title: String, val artists: List<String>)

enum class ImportMatchSource { LOCAL, YOUTUBE, UNRESOLVED }

data class ImportResolution<T>(val original: T, val match: T?, val source: ImportMatchSource)

/** Resolves occurrences concurrently while retaining the input order and duplicates. */
class ImportResolver<T>(
    private val describe: (T) -> ImportSongDescription,
    private val search: suspend (String) -> List<T>,
    private val onSearchFailure: (T, Exception) -> Unit = { _, _ -> },
    private val maxConcurrentResolutions: Int = 5,
) {
    init {
        require(maxConcurrentResolutions > 0) { "Maximum concurrent resolutions must be greater than zero" }
    }

    suspend fun resolve(
        songs: List<T>,
        localLibrary: List<T>,
        localFirst: Boolean,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ImportResolution<T>> = coroutineScope {
        val semaphore = Semaphore(maxConcurrentResolutions)
        val completed = AtomicInteger(0)
        songs.map { original ->
            async {
                val result = semaphore.withPermit { resolveOne(original, localLibrary, localFirst) }
                onProgress(completed.incrementAndGet(), songs.size)
                result
            }
        }.awaitAll()
    }

    private suspend fun resolveOne(original: T, localLibrary: List<T>, localFirst: Boolean): ImportResolution<T> {
        val description = describe(original)
        val artists = description.artists.filter(String::isNotBlank)
        if (localFirst) {
            val local = ImportMatcher.bestSongMatch(localLibrary, description.title, artists, describe = describe)
            if (local != null) return ImportResolution(original, local, ImportMatchSource.LOCAL)
        }
        val remote = try {
            ImportMatcher.bestSongMatch(search(buildImportSongQuery(description)), description.title, artists, describe = describe)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onSearchFailure(original, error)
            null
        }
        return ImportResolution(original, remote, if (remote == null) ImportMatchSource.UNRESOLVED else ImportMatchSource.YOUTUBE)
    }
}

fun buildImportSongQuery(song: ImportSongDescription): String {
    val artists = song.artists.map(::decodeArtistName).filter(String::isNotBlank).joinToString(" ").trim()
    return if (artists.isEmpty()) song.title.trim() else "${song.title.trim()} - $artists"
}

private fun decodeArtistName(value: String): String =
    try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
    } catch (error: IllegalArgumentException) {
        value
    }

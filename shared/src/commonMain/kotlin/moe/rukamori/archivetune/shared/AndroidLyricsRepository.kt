/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.lyrics.LyricsRequestCache
import moe.rukamori.archivetune.lyrics.LyricsRequestKey

/**
 * The Android YouLyPlus endpoint/mirror order backed by `lyrics-sdk` [LyricsRequestCache]
 * single-flight coalescing + byte-bounded LRU caching, retaining LRCLIB's exact default fallback.
 */
class AndroidLyricsRepository(
    private val client: HttpClient,
    private val fallback: LyricsRepository = LrclibLyricsRepository(client),
) : LyricsRepository {
    private val requestCache = LyricsRequestCache()
    private val keysMutex = Mutex()
    private val activeKeys = LinkedHashSet<LyricsRequestKey>()
    private var closed = false

    override suspend fun fetch(track: MusicTrack): List<LyricsLine> = try {
        wordLyrics(track, null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: LyricsException) {
        fallback.fetch(track)
    }

    override suspend fun searchVersions(track: MusicTrack) = searchVersions(track, null)

    override suspend fun searchVersions(track: MusicTrack, album: String?): List<LyricsVersion> = supervisorScope {
        val timed = async {
            try {
                val lines = wordLyrics(track, album)
                val text = lines.joinToString("\n") { it.text }
                listOf(
                    LyricsVersion(
                        id = 0,
                        title = track.title,
                        artist = track.artist,
                        album = album,
                        durationSeconds = track.durationMs?.div(1000.0),
                        isSynced = lines.any { it.startTimeMs != null },
                        provider = LyricsProvider.YOULY_PLUS,
                        wordTimed = lines.any { it.words.isNotEmpty() },
                        preview = text.take(250),
                        lineCount = lines.size,
                        characterCount = text.length,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LyricsException) {
                emptyList()
            }
        }
        val other = async {
            try {
                fallback.searchVersions(track, album)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LyricsException) {
                emptyList()
            }
        }
        (timed.await() + other.await()).take(51)
    }

    override suspend fun fetchVersion(version: LyricsVersion): List<LyricsLine> =
        if (version.provider == LyricsProvider.YOULY_PLUS) {
            wordLyrics(
                MusicTrack(
                    "lyrics-selection",
                    version.title,
                    version.artist,
                    durationMs = version.durationSeconds?.times(1000)?.toLong(),
                ),
                version.album,
            )
        } else {
            fallback.fetchVersion(version)
        }

    private suspend fun wordLyrics(track: MusicTrack, album: String?): List<LyricsLine> {
        if (closed) throw LyricsException(LyricsError.CLOSED)
        if (track.title.isBlank() || track.artist.isBlank()) throw LyricsException(LyricsError.MISSING_METADATA)
        val title = track.title.trim().take(300)
        val artist = track.artist.trim().take(300)
        val cleanAlbum = album?.trim()?.take(300)
        val durationSec = track.durationMs?.div(1_000L)?.toInt() ?: -1
        val requestKey = LyricsRequestKey(
            mediaId = "$title\u0000$artist",
            title = title,
            artists = artist,
            album = cleanAlbum,
            duration = durationSec,
            providers = listOf("YouLyPlus"),
        )
        keysMutex.withLock {
            activeKeys.remove(requestKey)
            activeKeys.add(requestKey)
            while (activeKeys.size > MAX_TRACKED_KEYS) {
                activeKeys.remove(activeKeys.first())
            }
        }
        val rawBody = requestCache.getOrFetchSingle(
            request = requestKey,
            forceRefresh = false,
            cacheable = { it.isNotBlank() },
        ) {
            withTimeoutOrNull(20_000L) {
                for (base in mirrors) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val body = withTimeoutOrNull(4_000L) {
                            val response = client.get("$base/v2/lyrics/get") {
                                parameter("title", title)
                                parameter("artist", artist)
                                cleanAlbum?.takeIf { it.isNotBlank() }?.let { parameter("album", it) }
                                track.durationMs?.takeIf { it > 0 }?.let { parameter("duration", it / 1000) }
                                header("Accept", "application/json")
                                header("User-Agent", "ArchiveTune")
                            }
                            if (response.status.value !in 200..299) null else response.bodyAsText()
                        } ?: continue
                        val parsed = decodeYouLyPlusTimeline(body)
                        if (parsed.isNotEmpty()) return@withTimeoutOrNull body
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Try the next Android mirror; no request details escape.
                    }
                }
                null
            } ?: throw LyricsException(LyricsError.NOT_FOUND)
        }
        return decodeYouLyPlusTimeline(rawBody)
    }

    override suspend fun invalidate(track: MusicTrack) {
        val title = track.title.trim().take(300)
        val artist = track.artist.trim().take(300)
        val toInvalidate = keysMutex.withLock {
            val matched = activeKeys.filter { it.title == title && it.artists == artist }
            matched.forEach(activeKeys::remove)
            matched
        }
        toInvalidate.forEach(requestCache::invalidate)
        fallback.invalidate(track)
    }

    override fun close() {
        closed = true
        requestCache.clear()
        fallback.close()
        client.close()
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 64
        val mirrors = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus-seven.vercel.app",
        )
    }
}

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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Android YouLyPlus endpoint/mirror order, retaining LRCLIB's exact default fallback. */
class AndroidLyricsRepository(
    private val client: HttpClient,
    private val fallback: LyricsRepository = LrclibLyricsRepository(client),
) : LyricsRepository {
    private data class Key(val title: String, val artist: String, val album: String?, val duration: Long?)
    private val mutex = Mutex()
    private val cache = linkedMapOf<Key, List<LyricsLine>>()
    private val pending = mutableMapOf<Key, CompletableDeferred<List<LyricsLine>>>()
    private var closed = false

    override suspend fun fetch(track: MusicTrack): List<LyricsLine> = try {
        wordLyrics(track, null)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: LyricsException) { fallback.fetch(track) }

    override suspend fun searchVersions(track: MusicTrack) = searchVersions(track, null)

    override suspend fun searchVersions(track: MusicTrack, album: String?): List<LyricsVersion> = supervisorScope {
        val timed = async {
            try {
                val lines = wordLyrics(track, album)
                val text = lines.joinToString("\n") { it.text }
                listOf(LyricsVersion(0, track.title, track.artist, album, track.durationMs?.div(1000.0),
                    isSynced = lines.any { it.startTimeMs != null }, provider = LyricsProvider.YOULY_PLUS,
                    wordTimed = lines.any { it.words.isNotEmpty() }, preview = text.take(250),
                    lineCount = lines.size, characterCount = text.length))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LyricsException) { emptyList() }
        }
        val other = async {
            try { fallback.searchVersions(track, album) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LyricsException) { emptyList() }
        }
        (timed.await() + other.await()).take(51)
    }

    override suspend fun fetchVersion(version: LyricsVersion): List<LyricsLine> =
        if (version.provider == LyricsProvider.YOULY_PLUS) wordLyrics(
            MusicTrack("lyrics-selection", version.title, version.artist,
                durationMs = version.durationSeconds?.times(1000)?.toLong()), version.album)
        else fallback.fetchVersion(version)

    private suspend fun wordLyrics(track: MusicTrack, album: String?): List<LyricsLine> {
        if (closed) throw LyricsException(LyricsError.CLOSED)
        if (track.title.isBlank() || track.artist.isBlank()) throw LyricsException(LyricsError.MISSING_METADATA)
        val key = Key(track.title.trim().take(300), track.artist.trim().take(300), album?.trim()?.take(300), track.durationMs)
        var leader = false
        val request = mutex.withLock {
            cache.remove(key)?.let { cache[key] = it; return it }
            pending[key] ?: CompletableDeferred<List<LyricsLine>>().also { pending[key] = it; leader = true }
        }
        if (!leader) return request.await()
        try {
            val result = withTimeoutOrNull(20_000L) {
                for (base in mirrors) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val body = withTimeoutOrNull(4_000L) {
                            val response = client.get("$base/v2/lyrics/get") {
                                parameter("title", key.title); parameter("artist", key.artist)
                                key.album?.takeIf { it.isNotBlank() }?.let { parameter("album", it) }
                                key.duration?.takeIf { it > 0 }?.let { parameter("duration", it / 1000) }
                                header("Accept", "application/json"); header("User-Agent", "ArchiveTune")
                            }
                            if (response.status.value !in 200..299) null else response.bodyAsText()
                        } ?: continue
                        val lines = decodeYouLyPlusTimeline(body)
                        if (lines.isNotEmpty()) return@withTimeoutOrNull lines
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Try the next Android mirror; no request details escape. */ }
                }
                null
            } ?: throw LyricsException(LyricsError.NOT_FOUND)
            mutex.withLock {
                cache[key] = result
                while (cache.size > 24) cache.remove(cache.keys.first())
            }
            request.complete(result)
            return result
        } catch (failure: Throwable) {
            request.completeExceptionally(failure)
            throw failure
        } finally {
            withContext(NonCancellable) { mutex.withLock { pending.remove(key) } }
        }
    }

    override suspend fun invalidate(track: MusicTrack) {
        mutex.withLock { cache.keys.filter { it.title == track.title.trim() && it.artist == track.artist.trim() }.forEach(cache::remove) }
        fallback.invalidate(track)
    }

    override fun close() {
        closed = true
        cache.clear()
        fallback.close()
        client.close()
    }

    private companion object {
        val mirrors = listOf("https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.prjktla.workers.dev", "https://lyricsplus.atomix.one", "https://lyricsplus-seven.vercel.app")
    }
}

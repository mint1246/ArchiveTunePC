package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/** Stable, user-safe failure categories; remote URLs, payloads, and exception text are never exposed. */
enum class LyricsError(val message: String) {
    MISSING_METADATA("A song title and artist are needed to find lyrics."),
    NOT_FOUND("No lyrics were found for this song."),
    IDENTITY_MISMATCH("The lyrics returned do not match this song."),
    RATE_LIMITED("The lyrics service is rate-limiting requests. Try again later."),
    TIMEOUT("The lyrics lookup timed out. Please retry."),
    NETWORK("Could not reach the lyrics service. Check your connection."),
    SERVER_ERROR("The lyrics service is temporarily unavailable."),
    ACCESS_DENIED("The lyrics service did not allow this request."),
    INVALID_RESPONSE("The lyrics service returned an invalid response."),
    CLOSED("The lyrics service is closed."),
}

class LyricsException(val reason: LyricsError) : Exception(reason.message)

interface LyricsRepository {
    /** Returns synced cues or untimed plain lines; throws only sanitized [LyricsException] failures. */
    suspend fun fetch(track: MusicTrack): List<LyricsLine>
    suspend fun searchVersions(track: MusicTrack): List<LyricsVersion> = throw LyricsException(LyricsError.NOT_FOUND)
    suspend fun searchVersions(track: MusicTrack, album: String?): List<LyricsVersion> = searchVersions(track)
    suspend fun fetchVersion(version: LyricsVersion): List<LyricsLine> = throw LyricsException(LyricsError.NOT_FOUND)
    suspend fun invalidate(track: MusicTrack) {}
    fun close()
}

/**
 * LRCLIB's documented HTTPS /api/get signature lookup. There is deliberately no
 * fuzzy /api/search fallback: a same-title cover/remix must never replace the
 * selected song's lyrics. Artist/title must match and known duration must be
 * within LRCLIB's documented +/- 2 second tolerance.
 *
 * The caller supplies a dedicated HttpClient (HttpClient(Darwin) on iOS);
 * this repository takes ownership of it and closes it. No auth or cookies used.
 * Successful responses and not-found results use a bounded per-track LRU cache.
 */
class LrclibLyricsRepository(
    private val httpClient: HttpClient,
    private val maxCacheEntries: Int = 24,
) : LyricsRepository {
    init { require(maxCacheEntries in 1..128) { "Lyrics cache size must be between 1 and 128" } }

    private data class TrackKey(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long?,
    )

    private sealed interface CacheEntry {
        data class Found(val lines: List<LyricsLine>) : CacheEntry
        data object Missing : CacheEntry
    }

    private val cacheMutex = Mutex()
    private val cache = linkedMapOf<TrackKey, CacheEntry>()
    private var closed = false

    override suspend fun fetch(track: MusicTrack): List<LyricsLine> {
        checkOpen()
        val key = track.toKey()
        val cached = cacheMutex.withLock {
            cache.remove(key)?.also { cache[key] = it }
        }
        when (cached) {
            is CacheEntry.Found -> return cached.lines
            CacheEntry.Missing -> throw LyricsException(LyricsError.NOT_FOUND)
            null -> Unit
        }

        val lines = try {
            request(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LyricsException) {
            if (failure.reason == LyricsError.NOT_FOUND) remember(key, CacheEntry.Missing)
            throw failure
        }
        checkOpen()
        remember(key, CacheEntry.Found(lines))
        return lines
    }

    override suspend fun invalidate(track: MusicTrack) {
        cacheMutex.withLock { cache.keys.filter { it.id == track.id }.forEach { cache.remove(it) } }
    }

    private fun MusicTrack.toKey(): TrackKey {
        val cleanedTitle = title.trim()
        val cleanedArtist = artist.trim()
        if (id.isBlank() || cleanedTitle.isBlank() || cleanedArtist.isBlank() ||
            cleanedTitle.length > 300 || cleanedArtist.length > 300 ||
            (durationMs != null && durationMs !in 1_000L..3_600_000L)
        ) throw LyricsException(LyricsError.MISSING_METADATA)
        return TrackKey(id, cleanedTitle, cleanedArtist, durationMs)
    }

    private suspend fun remember(key: TrackKey, value: CacheEntry) {
        cacheMutex.withLock {
            if (closed) return@withLock
            cache.remove(key)
            cache[key] = value
            while (cache.size > maxCacheEntries) cache.remove(cache.keys.first())
        }
    }

    private suspend fun request(key: TrackKey): List<LyricsLine> {
        val body = try {
            withTimeoutOrNull(10_000L) {
                val response = httpClient.get("https://lrclib.net/api/get") {
                    url {
                        parameters.append("track_name", key.title)
                        parameters.append("artist_name", key.artist)
                        key.durationMs?.let { parameters.append("duration", (it.toDouble() / 1_000.0).toString()) }
                    }
                    accept(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, "ArchiveTune-iOS/1.0")
                }
                if (response.status != HttpStatusCode.OK) throw failureForStatus(response.status)
                response.bodyAsText()
            } ?: throw LyricsException(LyricsError.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LyricsException) {
            throw failure
        } catch (failure: ResponseException) {
            throw failureForStatus(failure.response.status)
        } catch (_: Exception) {
            throw LyricsException(LyricsError.NETWORK)
        }
        checkOpen()
        if (body.length > 1_500_000) throw LyricsException(LyricsError.INVALID_RESPONSE)
        val payload = try { Json.parseToJsonElement(body) as? JsonObject }
            catch (_: Exception) { null }
            ?: throw LyricsException(LyricsError.INVALID_RESPONSE)

        val remoteTitle = payload.stringField("trackName") ?: payload.stringField("name")
        val remoteArtist = payload.stringField("artistName")
        val remoteDuration = (payload["duration"] as? JsonPrimitive)?.doubleOrNull
        if (remoteTitle == null || remoteArtist == null ||
            normalized(remoteTitle) != normalized(key.title) ||
            normalized(remoteArtist) != normalized(key.artist) ||
            (key.durationMs != null &&
                (remoteDuration == null || !remoteDuration.isFinite() ||
                    abs(remoteDuration * 1_000.0 - key.durationMs.toDouble()) > 2_000.0))
        ) throw LyricsException(LyricsError.IDENTITY_MISMATCH)

        if ((payload["instrumental"] as? JsonPrimitive)?.content == "true") {
            throw LyricsException(LyricsError.NOT_FOUND)
        }
        val synced = payload.stringField("syncedLyrics").orEmpty()
        val timedLines = LyricsTimeline.parseSynced(synced)
        if (timedLines.any { it.text.isNotBlank() }) return timedLines

        val plainLines = LyricsTimeline.parsePlain(payload.stringField("plainLyrics").orEmpty())
        if (plainLines.isNotEmpty()) return plainLines
        throw LyricsException(LyricsError.NOT_FOUND)
    }

    override suspend fun searchVersions(track: MusicTrack): List<LyricsVersion> {
        val key = track.toKey()
        val body = versionRequest("https://lrclib.net/api/search", key)
        val array = try { Json.parseToJsonElement(body) as? JsonArray } catch (_: Exception) { null }
            ?: throw LyricsException(LyricsError.INVALID_RESPONSE)
        return array.asSequence().mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val id = (obj["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = obj.stringField("trackName") ?: obj.stringField("name") ?: return@mapNotNull null
            val artist = obj.stringField("artistName") ?: return@mapNotNull null
            val duration = (obj["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
            LyricsVersion(id, title, artist, obj.stringField("albumName"), duration,
                !obj.stringField("syncedLyrics").isNullOrBlank(),
                preview = obj.stringField("plainLyrics").orEmpty().take(250),
                lineCount = obj.stringField("plainLyrics").orEmpty().lineSequence().count { it.isNotBlank() },
                characterCount = obj.stringField("plainLyrics").orEmpty().length)
        }.distinctBy { it.id }.take(50).toList()
    }

    override suspend fun fetchVersion(version: LyricsVersion): List<LyricsLine> {
        if (version.id <= 0) throw LyricsException(LyricsError.INVALID_RESPONSE)
        val body = versionRequest("https://lrclib.net/api/get/${version.id}", null)
        val payload = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
            ?: throw LyricsException(LyricsError.INVALID_RESPONSE)
        if ((payload["id"] as? JsonPrimitive)?.longOrNull != version.id) throw LyricsException(LyricsError.INVALID_RESPONSE)
        if ((payload["instrumental"] as? JsonPrimitive)?.content == "true") throw LyricsException(LyricsError.NOT_FOUND)
        val timed = LyricsTimeline.parseSynced(payload.stringField("syncedLyrics").orEmpty())
        if (timed.any { it.text.isNotBlank() }) return timed
        return LyricsTimeline.parsePlain(payload.stringField("plainLyrics").orEmpty())
            .takeIf { it.isNotEmpty() } ?: throw LyricsException(LyricsError.NOT_FOUND)
    }

    private suspend fun versionRequest(address: String, key: TrackKey?): String {
        checkOpen()
        val body = try {
            withTimeoutOrNull(10_000L) {
                val response = httpClient.get(address) {
                    if (key != null) url {
                        parameters.append("track_name", key.title)
                        parameters.append("artist_name", key.artist)
                    }
                    accept(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, "ArchiveTune-iOS/1.0")
                }
                if (response.status != HttpStatusCode.OK) throw failureForStatus(response.status)
                response.bodyAsText()
            } ?: throw LyricsException(LyricsError.TIMEOUT)
        } catch (e: CancellationException) { throw e
        } catch (e: LyricsException) { throw e
        } catch (e: ResponseException) { throw failureForStatus(e.response.status)
        } catch (_: Exception) { throw LyricsException(LyricsError.NETWORK) }
        checkOpen()
        if (body.length > 1_500_000) throw LyricsException(LyricsError.INVALID_RESPONSE)
        return body
    }

    private fun JsonObject.stringField(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun normalized(value: String): String = value.trim().replace(Regex("""\s+"""), " ").lowercase()

    private fun failureForStatus(status: HttpStatusCode): LyricsException = LyricsException(
        when (status.value) {
            404 -> LyricsError.NOT_FOUND
            429 -> LyricsError.RATE_LIMITED
            in 500..599 -> LyricsError.SERVER_ERROR
            else -> LyricsError.ACCESS_DENIED
        },
    )

    private fun checkOpen() {
        if (closed) throw LyricsException(LyricsError.CLOSED)
    }

    override fun close() {
        if (closed) return
        closed = true
        httpClient.close()
        cache.clear()
    }
}

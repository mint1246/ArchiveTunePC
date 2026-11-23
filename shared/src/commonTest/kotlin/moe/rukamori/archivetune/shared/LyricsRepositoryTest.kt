package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.Dispatchers
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LyricsRepositoryTest {
    private val first = MusicTrack("first", "First", "An Artist", durationMs = 120_000L)
    private val second = MusicTrack("second", "Second", "An Artist", durationMs = 120_000L)

    private fun response(
        title: String = "First",
        artist: String = "An Artist",
        seconds: Double = 120.0,
        synced: String? = "[00:01.20]hello\n",
        plain: String? = "hello\n",
        instrumental: Boolean = false,
    ): String = buildJsonObject {
        put("trackName", title)
        put("artistName", artist)
        put("duration", seconds)
        put("instrumental", instrumental)
        put("syncedLyrics", synced?.let(::JsonPrimitive) ?: JsonNull)
        put("plainLyrics", plain?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

    private fun fake(
        size: Int = 24,
        handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ): LrclibLyricsRepository {
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = Dispatchers.Unconfined
            addHandler { request ->
                val (status, text) = handler(request)
                respond(
                    content = ByteReadChannel(text),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        })
        return LrclibLyricsRepository(HttpClient(engine) { expectSuccess = false }, size)
    }

    @Test
    fun explicitRetryInvalidatesTheCachedNotFoundResult() = runTest {
        var available = false
        var requests = 0
        val repository = fake {
            requests++
            if (available) HttpStatusCode.OK to response() else HttpStatusCode.NotFound to "{}"
        }
        val controller = LyricsController(repository, backgroundScope)
        try {
            controller.select(first)
            runCurrent()
            assertEquals(LyricsError.NOT_FOUND, controller.state.value.error)
            available = true
            controller.select(first, refresh = true)
            runCurrent()
            assertEquals(2, requests)
            assertEquals(listOf(LyricsLine(1_200L, "hello")), controller.state.value.lines)
            assertNull(controller.state.value.error)
        } finally { controller.close() }
    }

    @Test
    fun usesRealHttpsGetSignatureIncludingUnicodeAndPreciseDuration() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val track = MusicTrack("unicode", "  鳥の歌 🌙 ", "Björk", durationMs = 180_500)
        val repository = fake { request ->
            requests += request
            HttpStatusCode.OK to response("鳥の歌 🌙", "Björk", 180.5, "[00:01.21]你好 مرحبا", null)
        }
        try {
            assertEquals(listOf(LyricsLine(1_210L, "你好 مرحبا")), repository.fetch(track))
            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https", request.url.protocol.name)
            assertEquals("lrclib.net", request.url.host)
            assertEquals("/api/get", request.url.encodedPath)
            assertEquals("鳥の歌 🌙", request.url.parameters["track_name"])
            assertEquals("Björk", request.url.parameters["artist_name"])
            assertEquals("180.5", request.url.parameters["duration"])
            assertNull(request.url.parameters["album_name"])
            assertFalse(request.headers.contains(HttpHeaders.Cookie))
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
        } finally {
            repository.close()
        }
    }

    @Test
    fun rejectsOtherArtistsAndTitlesWithoutBroadSearchFallback() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = fake { request ->
            requests += request
            HttpStatusCode.OK to response(title = "First", artist = "Another Artist")
        }
        try {
            val exception = assertFailsWith<LyricsException> { repository.fetch(first) }
            assertEquals(LyricsError.IDENTITY_MISMATCH, exception.reason)
            assertEquals(1, requests.size)
            assertEquals("/api/get", requests.single().url.encodedPath)
            assertFalse(exception.message.orEmpty().contains("Another Artist"))
        } finally {
            repository.close()
        }

        val otherTitle = fake { HttpStatusCode.OK to response(title = "First (Live)") }
        try {
            assertEquals(
                LyricsError.IDENTITY_MISMATCH,
                assertFailsWith<LyricsException> { otherTitle.fetch(first) }.reason,
            )
        } finally {
            otherTitle.close()
        }
    }

    @Test
    fun enforcesDurationToleranceAndAllowsMetadataOnlyWhenDurationUnknown() = runTest {
        val atBoundary = fake { HttpStatusCode.OK to response(seconds = 122.0) }
        try {
            assertEquals(1_200L, atBoundary.fetch(first).single().startTimeMs)
        } finally {
            atBoundary.close()
        }

        val outside = fake { HttpStatusCode.OK to response(seconds = 122.001) }
        try {
            assertEquals(
                LyricsError.IDENTITY_MISMATCH,
                assertFailsWith<LyricsException> { outside.fetch(first) }.reason,
            )
        } finally {
            outside.close()
        }

        val requests = mutableListOf<HttpRequestData>()
        val noDuration = fake { request ->
            requests += request
            HttpStatusCode.OK to response(seconds = 999.0)
        }
        try {
            val undated = first.copy(id = "no-duration", durationMs = null)
            assertEquals(1_200L, noDuration.fetch(undated).single().startTimeMs)
            assertNull(requests.single().url.parameters["duration"])
        } finally {
            noDuration.close()
        }
    }

    @Test
    fun fallsBackToUntimedPlainTextWhenSyncedIsAbsentOrMalformed() = runTest {
        val repository = fake {
            HttpStatusCode.OK to response(
                synced = "[00:99]broken\n[ar:ignored]",
                plain = "  こんにちは  \n\nשָׁלוֹם",
            )
        }
        try {
            val lines = repository.fetch(first)
            assertEquals(
                listOf(LyricsLine(null, "  こんにちは"), LyricsLine(null, ""), LyricsLine(null, "שָׁלוֹם")),
                lines,
            )
            assertNull(LyricsTimeline.activeLine(lines, 9_000))
        } finally {
            repository.close()
        }
    }

    @Test
    fun emptyTimedCuesDoNotHideActualPlainLyrics() = runTest {
        val repository = fake {
            HttpStatusCode.OK to response(synced = "[00:01] \n[00:05] ", plain = "Real lyrics")
        }
        try {
            assertEquals(listOf(LyricsLine(null, "Real lyrics")), repository.fetch(first))
        } finally {
            repository.close()
        }
    }

    @Test
    fun missingMetadataDoesNotMakeRequests() = runTest {
        var requests = 0
        val repository = fake { requests++; HttpStatusCode.OK to response() }
        try {
            assertEquals(
                LyricsError.MISSING_METADATA,
                assertFailsWith<LyricsException> { repository.fetch(first.copy(artist = "")) }.reason,
            )
            assertEquals(
                LyricsError.MISSING_METADATA,
                assertFailsWith<LyricsException> { repository.fetch(first.copy(durationMs = 0L)) }.reason,
            )
            assertEquals(0, requests)
        } finally {
            repository.close()
        }
    }

    @Test
    fun cachesSuccessfulAndMissingResultsPerTrackWithLruBound() = runTest {
        val requests = mutableListOf<String>()
        val repository = fake(size = 2) { request ->
            val title = request.url.parameters["track_name"].orEmpty()
            requests += title
            if (title == "Missing") HttpStatusCode.NotFound to """{"message":"untrusted url"}"""
            else HttpStatusCode.OK to response(title = title)
        }
        val missing = first.copy(id = "missing", title = "Missing")
        val third = first.copy(id = "third", title = "Third")
        try {
            repository.fetch(first)
            repository.fetch(second)
            repository.fetch(first) // LRU hit promotes First
            repository.fetch(third) // evicts Second
            repository.fetch(second)
            assertEquals(listOf("First", "Second", "Third", "Second"), requests)
            assertEquals(LyricsError.NOT_FOUND,
                assertFailsWith<LyricsException> { repository.fetch(missing) }.reason)
            assertEquals(LyricsError.NOT_FOUND,
                assertFailsWith<LyricsException> { repository.fetch(missing) }.reason)
            assertEquals(1, requests.count { it == "Missing" })
        } finally {
            repository.close()
        }
        assertEquals(
            LyricsError.CLOSED,
            assertFailsWith<LyricsException> { repository.fetch(first) }.reason,
        )
    }

    @Test
    fun stalledEndpointHasFiniteTypedTimeout() = runTest {
        val repository = fake { awaitCancellation() }
        try {
            val error = assertFailsWith<LyricsException> { repository.fetch(first) }
            assertEquals(LyricsError.TIMEOUT, error.reason)
        } finally {
            repository.close()
        }
    }

    @Test
    fun rateLimitAndInvalidJsonAreTypedAndNeverExposeServerPayloads() = runTest {
        val limited = fake {
            HttpStatusCode.TooManyRequests to """{"message":"token=DO_NOT_EXPOSE https://bad.invalid"}"""
        }
        try {
            val error = assertFailsWith<LyricsException> { limited.fetch(first) }
            assertEquals(LyricsError.RATE_LIMITED, error.reason)
            assertFalse(error.message.orEmpty().contains("DO_NOT_EXPOSE"))
        } finally {
            limited.close()
        }

        val malformed = fake { HttpStatusCode.OK to "<html>not json</html>" }
        try {
            assertEquals(
                LyricsError.INVALID_RESPONSE,
                assertFailsWith<LyricsException> { malformed.fetch(first) }.reason,
            )
        } finally {
            malformed.close()
        }

        val noLyrics = fake {
            HttpStatusCode.OK to response(instrumental = true, synced = null, plain = null)
        }
        try {
            assertEquals(
                LyricsError.NOT_FOUND,
                assertFailsWith<LyricsException> { noLyrics.fetch(first) }.reason,
            )
        } finally {
            noLyrics.close()
        }
    }

    @Test
    fun controllerCancelsOldWorkAndCannotPublishObsoleteTracks() = runTest {
        val pending = CompletableDeferred<List<LyricsLine>>()
        val repository = StubRepository { track ->
            if (track.id == first.id) withContext(NonCancellable) { pending.await() }
            else listOf(LyricsLine(5_000L, "new"))
        }
        val controller = LyricsController(repository, backgroundScope)
        controller.select(first)
        runCurrent()
        assertEquals(first.id, controller.state.value.trackId)
        assertTrue(controller.state.value.loading)

        controller.select(second)
        runCurrent()
        assertEquals(second.id, controller.state.value.trackId)
        assertFalse(controller.state.value.loading)
        assertEquals(listOf(LyricsLine(5_000L, "new")), controller.state.value.lines)

        pending.complete(listOf(LyricsLine(3_000L, "obsolete")))
        runCurrent()
        assertEquals(second.id, controller.state.value.trackId)
        assertEquals("new", controller.state.value.lines.single().text)
        assertNull(controller.state.value.activeLine(3_000))
        assertEquals(0, controller.state.value.activeLine(5_000))
        controller.close()
    }

    @Test
    fun controllerSelectionClearAndCloseCancelPendingWork() = runTest {
        var cancelled = false
        val repository = StubRepository {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val controller = LyricsController(repository, backgroundScope)
        controller.select(first)
        runCurrent()
        controller.select(null)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(LyricsState(), controller.state.value)

        controller.select(first)
        runCurrent()
        controller.close()
        controller.close()
        controller.select(second)
        runCurrent()
        assertTrue(repository.closed)
        assertEquals(1, repository.closeCount)
        assertEquals(LyricsState(), controller.state.value)
    }

    @Test
    fun controllerPublishesOnlySanitizedTypedFailures() = runTest {
        val repository = StubRepository { throw LyricsException(LyricsError.RATE_LIMITED) }
        val controller = LyricsController(repository, backgroundScope)
        controller.select(first)
        runCurrent()
        assertEquals(first.id, controller.state.value.trackId)
        assertNull(controller.state.value.lines.firstOrNull())
        assertFalse(controller.state.value.loading)
        assertEquals(LyricsError.RATE_LIMITED, controller.state.value.error)
        assertEquals("The lyrics service is rate-limiting requests. Try again later.",
            controller.state.value.error?.message)
        controller.close()
    }

    @Test
    fun searchesBoundedOrderedVersionsAndFetchesSelectedId() = runTest {
        val requests = mutableListOf<String>()
        val repository = fake { request ->
            requests += request.url.encodedPath
            if (request.url.encodedPath == "/api/search") {
                HttpStatusCode.OK to (1..60).joinToString(prefix = "[", postfix = "]") { id ->
                    """{"id":$id,"trackName":"Cover $id","artistName":"Artist","albumName":"Album","duration":120,"syncedLyrics":"[00:01]line"}"""
                }
            } else HttpStatusCode.OK to """{"id":2,"syncedLyrics":"[00:01.20]chosen"}"""
        }
        try {
            val versions = repository.searchVersions(first)
            assertEquals(50, versions.size)
            assertEquals(listOf(1L, 2L), versions.take(2).map { it.id })
            assertTrue(versions[1].isSynced)
            assertEquals(listOf(LyricsLine(1200, "chosen")), repository.fetchVersion(versions[1]))
            assertEquals(listOf("/api/search", "/api/get/2"), requests)
        } finally { repository.close() }
    }

    @Test
    fun obsoleteVersionCannotReplaceNewTrackAndErrorsAreRedacted() = runTest {
        val pending = CompletableDeferred<List<LyricsLine>>()
        val version = LyricsVersion(1, "Cover", "Artist", null, null, true)
        val repo = object : LyricsRepository {
            override suspend fun fetch(track: MusicTrack) = listOf(LyricsLine(null, track.title))
            override suspend fun fetchVersion(version: LyricsVersion) = withContext(NonCancellable) { pending.await() }
            override fun close() {}
        }
        val controller = LyricsController(repo, backgroundScope)
        controller.select(first); runCurrent()
        controller.selectVersion(version); runCurrent()
        controller.select(second); runCurrent()
        pending.complete(listOf(LyricsLine(1000, "obsolete"))); runCurrent()
        assertEquals("Second", controller.state.value.lines.single().text)
        assertNull(controller.state.value.selectedVersion)
        controller.close()
        val bad = fake { HttpStatusCode.TooManyRequests to "token=SECRET" }
        try {
            val e = assertFailsWith<LyricsException> { bad.searchVersions(first) }
            assertEquals(LyricsError.RATE_LIMITED, e.reason)
            assertFalse(e.message.orEmpty().contains("SECRET"))
        } finally { bad.close() }
    }

    private class StubRepository(
        private val block: suspend (MusicTrack) -> List<LyricsLine>,
    ) : LyricsRepository {
        var closed = false
        var closeCount = 0
        override suspend fun fetch(track: MusicTrack) = block(track)
        override fun close() { closeCount++; closed = true }
    }
}

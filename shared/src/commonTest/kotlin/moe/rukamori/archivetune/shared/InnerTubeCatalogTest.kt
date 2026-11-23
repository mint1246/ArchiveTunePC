package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.Dispatchers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InnerTubeCatalogTest {
    @Test
    fun headerlessMoodUsesFirstBrowseItemCoverLikeAndroid() = runTest {
        val engine = testEngine {
            respond("""{"contents":{"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"simpleText":"Playlists"}}},
              "contents":[{"musicTwoRowItemRenderer":{
                "title":{"simpleText":"Coffee Shop Blend"},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLcoffee"}},
                "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                  {"url":"https://i.ytimg.com/coffee.jpg"}
                ]}}}
              }}]}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val catalog = InnerTubeCatalog(HttpClient(engine) { expectSuccess = false })
        try {
            val mood = MusicMood("Chill", 0xff112233, MusicCatalogItem.Browse("FEmusic_moods_and_genres_category", "Chill"))
            assertEquals("https://i.ytimg.com/coffee.jpg", catalog.moodArtwork(mood))
        } finally { catalog.close() }
    }

    @Test
    fun radioFollowsAndroidAutomixAndBoundsRepeatedPreviewEndpoints() = runTest {
        val requestBodies = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val engine = testEngine { request ->
            val response = if (request.url.encodedPath.endsWith("/browse")) {
                """{"responseContext":{"visitorData":"fixture-visitor"}}"""
            } else {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                requestBodies += Json.parseToJsonElement(body).jsonObject
                val id = if (requestBodies.size == 1) CatalogFixtures.CLICKED else CatalogFixtures.SECOND
                """{"contents":[
                  {"playlistPanelVideoRenderer":{"videoId":"$id","title":{"simpleText":"Song"}}},
                  {"automixPreviewVideoRenderer":{"content":{"automixPlaylistVideoRenderer":{
                    "navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"RDAMVMfixture","params":"mix-params"}}
                  }}}}
                ]}"""
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val catalog = InnerTubeCatalog(HttpClient(engine) { expectSuccess = false })
        try {
            val queue = catalog.radioQueue(track())
            assertEquals(listOf(CatalogFixtures.CLICKED, CatalogFixtures.SECOND), queue.tracks.map { it.id })
            assertEquals(2, requestBodies.size)
            assertEquals(CatalogFixtures.CLICKED, requestBodies.first()["videoId"]?.jsonPrimitive?.content)
            assertEquals("RDAMVMfixture", requestBodies.last()["playlistId"]?.jsonPrimitive?.content)
            assertEquals("mix-params", requestBodies.last()["params"]?.jsonPrimitive?.content)
        } finally { catalog.close() }
    }

    // Keep MockEngine on the test coroutine's call stack: virtual timeout advancement must
    // not race a response scheduled on the real IO thread pool.
    private fun testEngine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        MockEngine(MockEngineConfig().apply {
            dispatcher = Dispatchers.Unconfined
            addHandler(handler)
        })

    private fun track(id: String = CatalogFixtures.CLICKED) =
        MusicTrack(id = id, title = "The clicked track", artist = "Original Artist")

    private fun fake(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: (HttpRequestData) -> Unit = {},
    ): InnerTubeCatalog {
        val engine = testEngine { request ->
            if (request.url.encodedPath.endsWith("/browse")) {
                return@testEngine respond("""{"responseContext":{"visitorData":"fixture-visitor"}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            onRequest(request)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return InnerTubeCatalog(HttpClient(engine) { expectSuccess = false })
    }

    @Test
    fun parsesNestedMusicShelvesWithoutConfusingArtistOrDuplicateEntries() {
        val results = CatalogParsing.search(CatalogFixtures.asJson(CatalogFixtures.tabbedSearch))

        assertEquals(listOf(CatalogFixtures.CLICKED, CatalogFixtures.SECOND), results.map { it.id })
        assertEquals("First Song", results[0].title)
        assertEquals("Artist One", results[0].artist)
        assertEquals(225_000L, results[0].durationMs)
        assertEquals("https://i.ytimg.com/vi/aa11bb22cc3/hqdefault.jpg", results[0].artworkUrl)
        assertEquals("Other Song", results[1].title)
        assertEquals("Second Artist", results[1].artist)
        assertNull(results[1].durationMs)
    }

    @Test
    fun parsesContinuationOverlayAndCardShapesWithMissingOptionalFields() {
        val results = CatalogParsing.search(CatalogFixtures.asJson(CatalogFixtures.alternateSearch))

        assertEquals(listOf(CatalogFixtures.CLICKED, CatalogFixtures.SECOND), results.map { it.id })
        assertEquals("Alternate Song", results[0].title)
        assertEquals("", results[0].artist)
        assertNull(results[0].artworkUrl)
        assertNull(results[0].durationMs)
        assertEquals("Featured Song", results[1].title)
        assertEquals("Featured Artist", results[1].artist)
    }

    @Test
    fun searchUsesYouTubeMusicEndpointAndWebRemixProfile() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val catalog = fake(CatalogFixtures.tabbedSearch, onRequest = { requests += it })
        try {
            val results = catalog.search("  first song   ")
            assertEquals(2, results.size)
            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("music.youtube.com", request.url.host)
            assertEquals("/youtubei/v1/search", request.url.encodedPath)
            assertEquals("67", request.headers["X-YouTube-Client-Name"])
            assertEquals("https://music.youtube.com", request.headers["X-Origin"])
            assertEquals("false", request.url.parameters["prettyPrint"])
            val payload = Json.parseToJsonElement(
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
            ).jsonObject
            assertEquals("first song", payload["query"]?.jsonPrimitive?.content)
            assertEquals(
                "WEB_REMIX",
                payload["context"]?.jsonObject?.get("client")?.jsonObject
                    ?.get("clientName")?.jsonPrimitive?.content,
            )
            assertFalse(request.headers.contains(HttpHeaders.Cookie))
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
        } finally {
            catalog.close()
        }
    }

    @Test
    fun songIdsFromTitleWatchEndpointsAndPrimitiveTextAreSupported() {
        val response = """{
          "contents": [{"musicResponsiveListItemRenderer": {
            "flexColumns": [{
              "musicResponsiveListItemFlexColumnRenderer": {"text": {
                "runs": [{"text":"Primitive Variant", "navigationEndpoint":{
                  "watchEndpoint":{"videoId":"aa11bb22cc3"}
                }}]
              }}
            }],
            "lengthText": "1:02"
          }}]
        }"""
        val parsed = CatalogParsing.search(CatalogFixtures.asJson(response))
        assertEquals(CatalogFixtures.CLICKED, parsed.single().id)
        assertEquals("Primitive Variant", parsed.single().title)
        assertEquals(62_000L, parsed.single().durationMs)
    }

    @Test
    fun blankSearchDoesNotMakeNetworkRequests() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val catalog = fake("{}", onRequest = { requests += it })
        try {
            assertTrue(catalog.search("  ").isEmpty())
            assertTrue(requests.isEmpty())
        } finally {
            catalog.close()
        }
    }

    @Test
    fun resolvePostsTheExactClickedIdAndSelectsHighestBitrateAac() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val catalog = fake(CatalogFixtures.player(hls = CatalogFixtures.HLS), onRequest = { requests += it })
        try {
            val result = catalog.resolve(track())
            assertEquals(CatalogFixtures.CLICKED, result.trackId)
            assertEquals(CatalogFixtures.AUDIO_HIGH, result.url)
            assertEquals("audio/mp4", result.mimeType)
            assertTrue(result.headers.isEmpty())

            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("www.youtube.com", request.url.host)
            assertEquals("/youtubei/v1/player", request.url.encodedPath)
            assertEquals("101", request.headers["X-YouTube-Client-Name"])
            val payload = Json.parseToJsonElement(
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
            ).jsonObject
            assertEquals(CatalogFixtures.CLICKED, payload["videoId"]?.jsonPrimitive?.content)
            assertNull(payload["query"])
            assertEquals(
                "VISIONOS",
                payload["context"]?.jsonObject?.get("client")?.jsonObject
                    ?.get("clientName")?.jsonPrimitive?.content,
            )
        } finally {
            catalog.close()
        }
    }

    @Test
    fun neverUsesOtherVideoEvenWhenItsSourceIsPlayable() = runTest {
        val catalog = fake(CatalogFixtures.player(videoId = CatalogFixtures.WRONG))
        try {
            val error = assertFailsWith<CatalogException> { catalog.resolve(track()) }
            assertEquals(CatalogException.Reason.IDENTITY_MISMATCH, error.reason)
            assertFalse(error.message.orEmpty().contains(CatalogFixtures.WRONG))
            assertFalse(error.message.orEmpty().contains(CatalogFixtures.AUDIO_HIGH))
        } finally {
            catalog.close()
        }
    }

    @Test
    fun missingPlayerIdentityFailsClosed() = runTest {
        val response = """{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_playlist/test"}}"""
        val catalog = fake(response)
        try {
            assertEquals(
                CatalogException.Reason.IDENTITY_MISMATCH,
                assertFailsWith<CatalogException> { catalog.resolve(track()) }.reason,
            )
        } finally {
            catalog.close()
        }
    }

    @Test
    fun nestedPlayerResponseAndFormatsArrayCanProvideAac() = runTest {
        val response = """{"playerResponse":""" + CatalogFixtures.player() + "}"
        val catalog = fake(response)
        try {
            assertEquals(CatalogFixtures.AUDIO_HIGH, catalog.resolve(track()).url)
        } finally {
            catalog.close()
        }

        val formatsOnly = """{
          "videoDetails":{"videoId":"aa11bb22cc3"},
          "streamingData":{"formats":[{
            "itag":140, "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
            "url":"https://r1---sn-test.googlevideo.com/videoplayback?itag=140"
          }]}
        }"""
        val second = fake(formatsOnly)
        try {
            assertEquals("audio/mp4", second.resolve(track()).mimeType)
        } finally {
            second.close()
        }
    }

    @Test
    fun acceptsFirstPartyHlsOnlyWhenNoCompatibleAacExists() = runTest {
        val catalog = fake(
            CatalogFixtures.player(
                formats = CatalogFixtures.unsupportedFormats,
                hls = CatalogFixtures.HLS,
            ),
        )
        try {
            val resolved = catalog.resolve(track())
            assertEquals(CatalogFixtures.CLICKED, resolved.trackId)
            assertEquals(CatalogFixtures.HLS, resolved.url)
            assertEquals("application/vnd.apple.mpegurl", resolved.mimeType)
            assertTrue(resolved.headers.isEmpty())
        } finally {
            catalog.close()
        }
    }

    @Test
    fun cipherOnlyAudioIsRejectedNotPretendedDeciphered() = runTest {
        val catalog = fake(CatalogFixtures.player(formats = CatalogFixtures.cipherOnly))
        try {
            val error = assertFailsWith<CatalogException> { catalog.resolve(track()) }
            assertEquals(CatalogException.Reason.SIGNATURE_REQUIRED, error.reason)
            assertContains(error.message.orEmpty(), "cannot decipher")
        } finally {
            catalog.close()
        }
    }

    @Test
    fun unsupportedCodecAndSabrOnlyAreRejected() = runTest {
        val catalog = fake(CatalogFixtures.player(formats = CatalogFixtures.unsupportedFormats))
        try {
            val error = assertFailsWith<CatalogException> { catalog.resolve(track()) }
            assertEquals(CatalogException.Reason.UNSUPPORTED_STREAM, error.reason)
            assertContains(error.message.orEmpty(), "AVPlayer-compatible")
        } finally {
            catalog.close()
        }
    }

    @Test
    fun rejectsUntrustedMediaHostsInsteadOfForwardingThemToPlayer() = runTest {
        val formats = """[
            {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
             "url":"https://evil.googlevideo.com.attacker.invalid/videoplayback"}
        ]"""
        val catalog = fake(CatalogFixtures.player(formats = formats))
        try {
            assertEquals(
                CatalogException.Reason.UNSUPPORTED_STREAM,
                assertFailsWith<CatalogException> { catalog.resolve(track()) }.reason,
            )
        } finally {
            catalog.close()
        }
    }

    @Test
    fun encryptedSignatureParameterIsNotForwarded() = runTest {
        val formats = """[
            {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
             "url":"https://r1---sn-test.googlevideo.com/videoplayback?s=encryptedValue"}
        ]"""
        val catalog = fake(CatalogFixtures.player(formats = formats))
        try {
            assertEquals(
                CatalogException.Reason.SIGNATURE_REQUIRED,
                assertFailsWith<CatalogException> { catalog.resolve(track()) }.reason,
            )
        } finally {
            catalog.close()
        }
    }

    @Test
    fun poTokenAndAuthGatesReturnActionableSanitizedErrors() = runTest {
        val po = fake(
            CatalogFixtures.player(
                status = "UNPLAYABLE",
                reason = "Missing PO token; do not expose diagnostic-private-value",
            ),
        )
        try {
            val error = assertFailsWith<CatalogException> { po.resolve(track()) }
            assertEquals(CatalogException.Reason.PO_TOKEN_REQUIRED, error.reason)
            assertFalse(error.message.orEmpty().contains("diagnostic-private-value"))
        } finally {
            po.close()
        }

        val login = fake(CatalogFixtures.player(status = "LOGIN_REQUIRED"))
        try {
            assertEquals(
                CatalogException.Reason.AUTH_REQUIRED,
                assertFailsWith<CatalogException> { login.resolve(track()) }.reason,
            )
        } finally {
            login.close()
        }
    }

    @Test
    fun http403And429MessagesDoNotLeakResponseData() = runTest {
        val forbidden = fake("""{"secret":"do-not-repeat-this"}""", HttpStatusCode.Forbidden)
        try {
            val error = assertFailsWith<CatalogException> { forbidden.resolve(track()) }
            assertEquals(CatalogException.Reason.ACCESS_DENIED, error.reason)
            assertFalse(error.message.orEmpty().contains("do-not-repeat-this"))
        } finally {
            forbidden.close()
        }

        val limited = fake("rate limited", HttpStatusCode.TooManyRequests)
        try {
            assertEquals(
                CatalogException.Reason.RATE_LIMITED,
                assertFailsWith<CatalogException> { limited.resolve(track()) }.reason,
            )
        } finally {
            limited.close()
        }
    }

    @Test
    fun cancellationReachesTheActiveKtorRequest() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val engine = testEngine {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val catalog = InnerTubeCatalog(HttpClient(engine))
        val job = backgroundScope.launch { catalog.resolve(track()) }
        started.await()
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        catalog.close()
    }

    @Test
    fun coldQueuePlaybackBootstrapsVisitorOnceWithoutSearchingForASubstitute() = runTest {
        var bootstraps = 0
        var players = 0
        val engine = testEngine { request ->
            when {
                request.url.encodedPath.endsWith("/browse") -> {
                    bootstraps++
                    respond("""{"responseContext":{"visitorData":"cold-visitor"}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.url.encodedPath.endsWith("/player") -> {
                    players++
                    assertEquals("cold-visitor", request.headers["X-Goog-Visitor-Id"])
                    respond(CatalogFixtures.player(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Resolving a queue item must not search for a song")
            }
        }
        val catalog = InnerTubeCatalog(HttpClient(engine))
        try {
            catalog.resolve(track())
            catalog.resolve(track())
            assertEquals(1, bootstraps)
            assertEquals(2, players)
        } finally { catalog.close() }
    }

    @Test
    fun visitorContextFromSearchCarriesIntoExactVideoRequest() = runTest {
        val engine = testEngine { request ->
            if (request.url.encodedPath.endsWith("/search")) {
                respond("""{"responseContext":{"visitorData":"anonymous-visitor"},"contents":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                assertEquals("anonymous-visitor", request.headers["X-Goog-Visitor-Id"])
                val payload = Json.parseToJsonElement(
                    (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals("anonymous-visitor", payload["context"]?.jsonObject?.get("client")
                    ?.jsonObject?.get("visitorData")?.jsonPrimitive?.content)
                respond(CatalogFixtures.player(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val catalog = InnerTubeCatalog(HttpClient(engine))
        try {
            catalog.search("test")
            assertEquals(CatalogFixtures.CLICKED, catalog.resolve(track()).trackId)
        } finally { catalog.close() }
    }

    @Test
    fun stalledNetworkRequestFailsWithActionableTimeoutInsteadOfHanging() = runTest {
        val engine = testEngine { awaitCancellation() }
        val catalog = InnerTubeCatalog(HttpClient(engine))
        try {
            val error = assertFailsWith<CatalogException> { catalog.resolve(track()) }
            assertEquals(CatalogException.Reason.NETWORK, error.reason)
            assertContains(error.message.orEmpty(), "in time")
        } finally {
            catalog.close()
        }
    }

    @Test
    fun nonAsciiVideoIdsAndLookalikeMimeTypesAreRejected() = runTest {
        val catalog = fake(CatalogFixtures.player())
        try {
            assertEquals(CatalogException.Reason.BAD_VIDEO_ID,
                assertFailsWith<CatalogException> { catalog.resolve(track("éa11bb22cc3")) }.reason)
        } finally { catalog.close() }
        val wrongMime = fake(CatalogFixtures.player(formats = """[
            {"itag":140,"mimeType":"AUDIO/MP4invalid","url":"https://r1.googlevideo.com/videoplayback?itag=140"}
        ]"""))
        try {
            assertEquals(CatalogException.Reason.UNSUPPORTED_STREAM,
                assertFailsWith<CatalogException> { wrongMime.resolve(track()) }.reason)
        } finally { wrongMime.close() }
    }

    @Test
    fun closeIsIdempotentAndRejectsFurtherRequests() = runTest {
        val catalog = fake(CatalogFixtures.player())
        catalog.close()
        catalog.close()
        assertEquals(
            CatalogException.Reason.CLOSED,
            assertFailsWith<CatalogException> { catalog.resolve(track()) }.reason,
        )
    }

    @Test
    fun invalidVideoIdIsRejectedBeforeAnyRequest() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val catalog = fake("{}", onRequest = { requests += it })
        try {
            val error = assertFailsWith<CatalogException> { catalog.resolve(track("not-a-valid-id")) }
            assertEquals(CatalogException.Reason.BAD_VIDEO_ID, error.reason)
            assertTrue(requests.isEmpty())
        } finally {
            catalog.close()
        }
    }
}

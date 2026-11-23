package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minimal KMP youtubei implementation using only Ktor core + kotlinx.serialization JSON.
 *
 * Searches YouTube Music with WEB_REMIX and resolves the exact clicked video through VISIONOS.
 * The latter can supply plain AAC/MP4 or HLS streams, but availability is NOT guaranteed:
 * YouTube may require session authentication, client changes, PO tokens, JS transforms, or SABR.
 *
 * The supplied HttpClient is dedicated to the catalog and is closed by [close].
 * No authenticated session, cookies, signed media URLs or headers are cached or logged.
 */
class InnerTubeCatalog(private val httpClient: HttpClient, private val preferHls: Boolean = false) : MusicCatalog {
    override suspend fun searchItems(query: String): List<MusicCatalogItem> = searchItems(query, null)

    override suspend fun searchItems(query: String, params: String?): List<MusicCatalogItem> {
        ensureOpen()
        val normalized = query.trim().take(200)
        if (normalized.isEmpty()) return emptyList()
        val body = buildJsonObject { put("context", SEARCH_CLIENT.context(visitorData)); put("query", normalized); if (params != null && params.length <= 4096) put("params", params) }
        return CatalogParsing.searchItems(request(SEARCH_CLIENT, "search", body.toString()))
    }

    override suspend fun searchSuggestions(query: String): List<String> {
        ensureOpen()
        val normalized = query.trim().take(200)
        if (normalized.isEmpty()) return emptyList()
        val body = buildJsonObject { put("context", SEARCH_CLIENT.context(visitorData)); put("input", normalized) }
        return CatalogParsing.searchSuggestions(request(SEARCH_CLIENT, "music/get_search_suggestions", body.toString()))
    }

    private var closed = false
    private var visitorData: String? = null
    private val visitorMutex = Mutex()
    private var cachedHomeChips: List<MusicHomeChip>? = null

    override suspend fun radioQueue(clicked: MusicTrack): MusicRadioQueue {
        ensureOpen()
        require(CatalogParsing.isYouTubeVideoId(clicked.id))
        ensureVisitorContext()
        val body = buildJsonObject { put("context", SEARCH_CLIENT.context(visitorData)); put("videoId", clicked.id) }
        var response = request(SEARCH_CLIENT, "next", body.toString())
        val initial = CatalogParsing.radioQueuePage(response)
        val tracks = initial.tracks.toMutableList()
        val seen = mutableSetOf<String>()
        // Android follows the automix preview into the actual mix. Bound malformed cycles.
        while (seen.size < 3 && tracks.size < 500) {
            val endpoint = CatalogParsing.radioAutomixEndpoint(response) ?: break
            val playlist = (endpoint["playlistId"] as? JsonPrimitive)?.contentOrNull ?: break
            if (!seen.add(playlist)) break
            val nextBody = buildJsonObject {
                put("context", SEARCH_CLIENT.context(visitorData))
                listOf("videoId", "playlistId", "playlistSetVideoId", "index", "params").forEach { key ->
                    endpoint[key]?.let { put(key, it) }
                }
            }
            response = request(SEARCH_CLIENT, "next", nextBody.toString())
            tracks += CatalogParsing.radioQueuePage(response).tracks.take(500 - tracks.size)
        }
        return MusicRadioQueue(if (tracks.any { it.id == clicked.id }) tracks else listOf(clicked), initial.title)
    }

    override suspend fun discovery(): MusicDiscovery {
        val explore = browseResponse("FEmusic_explore", null)
        return MusicDiscovery(moods = CatalogParsing.moods(explore), shelves = CatalogParsing.home(explore))
    }

    override suspend fun moodArtwork(mood: MusicMood): String? {
        val page = CatalogParsing.browse(browseResponse(mood.endpoint.id, mood.endpoint.params))
        // Android BrowseResult falls back to the first shelf item's cover on headerless moods.
        return page.artworkUrl ?: page.shelves.asSequence().flatMap { it.items.asSequence() }
            .mapNotNull { item -> when (item) {
                is MusicCatalogItem.Browse -> item.artworkUrl
                is MusicCatalogItem.Track -> item.track.artworkUrl
            } }.firstOrNull()
    }

    override suspend fun home(): List<MusicShelf> {
        ensureOpen()
        ensureVisitorContext()
        val body = buildJsonObject {
            put("context", SEARCH_CLIENT.context(visitorData))
            put("browseId", "FEmusic_home")
        }
        val response = request(SEARCH_CLIENT, "browse", body.toString())
        cachedHomeChips = CatalogParsing.homeChips(response)
        return CatalogParsing.home(response)
    }

    override suspend fun homeChips(): List<MusicHomeChip> {
        if (cachedHomeChips == null) home()
        return cachedHomeChips.orEmpty()
    }

    override suspend fun browse(id: String, params: String?): MusicBrowsePage {
        require(id.isNotBlank() && id.length <= 512)
        val first = CatalogParsing.browse(browseResponse(id, params))
        val tracks = first.tracks.toMutableList()
        var token = first.continuation
        val seen = mutableSetOf<String>()
        while (tracks.size < 500 && seen.size < 10) {
            val currentToken = token ?: break
            if (currentToken.length > 4096 || !seen.add(currentToken)) break
            val body = buildJsonObject {
                put("context", SEARCH_CLIENT.context(visitorData))
                put("continuation", currentToken)
            }
            val page = CatalogParsing.browse(request(SEARCH_CLIENT, "browse", body.toString()))
            tracks += page.tracks.take(500 - tracks.size)
            token = page.continuation
        }
        return first.copy(tracks = tracks, continuation = token)
    }

    private suspend fun browseResponse(id: String, params: String?): JsonElement {
        ensureOpen()
        val body = buildJsonObject {
            put("context", SEARCH_CLIENT.context(visitorData))
            put("browseId", id)
            if (!params.isNullOrBlank() && params.length <= 4096) put("params", params)
        }
        return request(SEARCH_CLIENT, "browse", body.toString())
    }

    override suspend fun search(query: String): List<MusicTrack> {
        ensureOpen()
        val normalized = query.trim().take(200)
        if (normalized.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("context", SEARCH_CLIENT.context(visitorData))
            put("query", normalized)
        }
        return CatalogParsing.search(request(SEARCH_CLIENT, "search", body.toString()))
    }

    override suspend fun resolve(track: MusicTrack): ResolvedAudio {
        ensureOpen()
        val clickedId = track.id
        if (!CatalogParsing.isYouTubeVideoId(clickedId)) {
            throw CatalogException(
                CatalogException.Reason.BAD_VIDEO_ID,
                "This item does not have a valid YouTube video ID and cannot be played.",
            )
        }
        ensureVisitorContext()
        // Never search by title here, and never accept a different ID returned by the player API.
        val body = buildJsonObject {
            put("context", PLAYER_CLIENT.context(visitorData))
            put("videoId", clickedId)
        }
        val resolved = CatalogParsing.resolve(request(PLAYER_CLIENT, "player", body.toString()), clickedId, preferHls)
        if (resolved.mimeType != "application/vnd.apple.mpegurl") return resolved
        // Use the master manifest's audio rendition when available, so a music player
        // need not download/decode its accompanying video variants. URLs stay ephemeral.
        return try {
            val audioUrl = withTimeoutOrNull(3_000) {
                val response = httpClient.get(resolved.url)
                if (response.status != HttpStatusCode.OK) return@withTimeoutOrNull null
                val manifest = response.bodyAsText()
                CatalogParsing.hlsAudioRendition(manifest)
            }
            if (audioUrl == null) resolved else resolved.copy(url = audioUrl)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { resolved }
    }

    private suspend fun ensureVisitorContext() {
        if (visitorData != null) return
        visitorMutex.withLock {
            if (visitorData != null) return@withLock
            // Restored queues can be played before search has established an anonymous session.
            // This bootstrap never chooses a song or changes the requested video identity.
            val body = buildJsonObject {
                put("context", SEARCH_CLIENT.context())
                put("browseId", "FEmusic_home")
            }
            request(SEARCH_CLIENT, "browse", body.toString())
        }
    }

    private suspend fun request(profile: ClientProfile, endpoint: String, body: String): JsonElement {
        ensureOpen()
        val responseText = try {
            withTimeoutOrNull(20_000L) {
                val response = httpClient.post(profile.baseUrl + "/youtubei/v1/" + endpoint) {
                    url.parameters.append("prettyPrint", "false")
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    header("X-Goog-Api-Format-Version", "1")
                    visitorData?.let { header("X-Goog-Visitor-Id", it) }
                    header("X-YouTube-Client-Name", profile.clientId)
                    header("X-YouTube-Client-Version", profile.version)
                    header("X-Origin", profile.baseUrl)
                    header(HttpHeaders.Origin, profile.baseUrl)
                    header(HttpHeaders.Referrer, profile.baseUrl + "/")
                    header(HttpHeaders.UserAgent, profile.userAgent)
                    setBody(body)
                }
                if (response.status.value !in 200..299) throw httpFailure(response.status)
                response.bodyAsText()
            } ?: throw CatalogException(
                CatalogException.Reason.NETWORK,
                "YouTube Music did not respond in time. Check the connection and retry.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CatalogException) {
            throw failure
        } catch (failure: ResponseException) {
            // Client may be configured with expectSuccess = true.
            throw httpFailure(failure.response.status)
        } catch (_: Exception) {
            throw CatalogException(
                CatalogException.Reason.NETWORK,
                "Could not contact YouTube Music. Check the connection and retry.",
            )
        }
        ensureOpen()
        if (responseText.length > 4_000_000) {
            throw CatalogException(
                CatalogException.Reason.INVALID_RESPONSE,
                "YouTube returned an unexpectedly large response. Please retry.",
            )
        }
        return try {
            Json.parseToJsonElement(responseText).also { response ->
                val context = (response as? JsonObject)?.get("responseContext") as? JsonObject
                val visitor = (context?.get("visitorData") as? JsonPrimitive)?.contentOrNull
                if (!visitor.isNullOrBlank() && visitor.length <= 4096) visitorData = visitor
            }
        } catch (_: Exception) {
            throw CatalogException(
                CatalogException.Reason.INVALID_RESPONSE,
                "YouTube returned an unexpected response. This client profile may need an update or sign-in.",
            )
        }
    }

    private fun ensureOpen() {
        if (closed) {
            throw CatalogException(CatalogException.Reason.CLOSED, "The music catalog is closed.")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        visitorData = null
        httpClient.close()
    }

    private fun httpFailure(status: HttpStatusCode): CatalogException =
        when (status.value) {
            401 -> CatalogException(
                CatalogException.Reason.AUTH_REQUIRED,
                "YouTube requires authentication for this request. Anonymous playback is unavailable.",
            )
            403 -> CatalogException(
                CatalogException.Reason.ACCESS_DENIED,
                "YouTube refused this client (403). A valid account, PO token, or updated client may be required.",
            )
            429 -> CatalogException(
                CatalogException.Reason.RATE_LIMITED,
                "YouTube is rate-limiting requests. Retry later rather than repeatedly requesting this song.",
            )
            in 500..599 -> CatalogException(
                CatalogException.Reason.SERVER_ERROR,
                "YouTube is temporarily unavailable. Retry later.",
            )
            else -> CatalogException(
                CatalogException.Reason.ACCESS_DENIED,
                "YouTube rejected this request. The selected client or access context may no longer be supported.",
            )
        }

    private data class ClientProfile(
        val clientName: String,
        val clientId: String,
        val version: String,
        val userAgent: String,
        val baseUrl: String,
        val osName: String? = null,
        val osVersion: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
    ) {
        fun context(visitorData: String? = null) = buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", clientName)
                put("clientVersion", version)
                put("userAgent", userAgent)
                put("hl", "en")
                put("gl", "US")
                if (visitorData != null) put("visitorData", visitorData)
                if (osName != null) put("osName", osName)
                if (osVersion != null) put("osVersion", osVersion)
                if (deviceMake != null) put("deviceMake", deviceMake)
                if (deviceModel != null) put("deviceModel", deviceModel)
            })
            put("request", buildJsonObject { put("internalExperimentFlags", JsonArray(emptyList())); put("useSsl", true) })
            put("user", buildJsonObject { put("lockedSafetyMode", false) })
        }
    }

    private companion object {
        // See core/ InnerTube.ytClient(), and yt-dlp's public youtubei client profiles.
        private val SEARCH_CLIENT = ClientProfile(
            clientName = "WEB_REMIX",
            clientId = "67",
            version = "1.20260707.12.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            baseUrl = "https://music.youtube.com",
        )

        // Native Apple player-compatible response preference; deliberately no JS cipher or POT.
        private val PLAYER_CLIENT = ClientProfile(
            clientName = "VISIONOS",
            clientId = "101",
            version = "1.02",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            baseUrl = "https://www.youtube.com",
            osName = "visionOS",
            osVersion = "26.5.23O471",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
        )
    }
}

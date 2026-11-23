package moe.rukamori.archivetune.shared

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Failure messages deliberately contain no server-supplied text, signed URLs, request headers,
 * identifiers, or raw HTTP exceptions. The caller may show [message] to a user.
 */
class CatalogException(
    val reason: Reason,
    message: String,
) : IllegalStateException(message) {
    enum class Reason {
        CLOSED,
        BAD_VIDEO_ID,
        NETWORK,
        ACCESS_DENIED,
        AUTH_REQUIRED,
        PO_TOKEN_REQUIRED,
        RATE_LIMITED,
        SERVER_ERROR,
        INVALID_RESPONSE,
        IDENTITY_MISMATCH,
        UNPLAYABLE,
        SIGNATURE_REQUIRED,
        UNSUPPORTED_STREAM,
    }
}

/** JSON projections, not a strict wire schema: youtubei renderer wrappers change frequently. */
internal object CatalogParsing {
    fun searchItems(response: JsonElement): List<MusicCatalogItem> {
        val items = linkedMapOf<String, MusicCatalogItem>()
        walkRenderers(response) { key, value ->
            if (key in rendererNames) {
                if (items.size < MAX_SEARCH_RESULTS) parseCatalogItem(key, value)?.let { item ->
                    val id = when (item) {
                        is MusicCatalogItem.Track -> "track:${item.track.id}"
                        is MusicCatalogItem.Browse -> "browse:${item.id}"
                    }
                    if (!items.containsKey(id)) items[id] = item
                }
                true
            } else false
        }
        return items.values.toList()
    }

    fun searchSuggestions(response: JsonElement): List<String> {
        val queries = mutableListOf<String>()
        walkRenderers(response) { key, value ->
            if (key == "searchSuggestionRenderer") {
                value.field("suggestion").displayText()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
                    ?.let { if (queries.size < 20 && it !in queries) queries += it }
                true
            } else false
        }
        return queries
    }

    private const val MAX_SEARCH_RESULTS = 60
    private const val MAX_RENDERER_WALK_DEPTH = 40
    private const val MAX_RENDERER_VISITS = 12_000
    private val rendererNames = setOf(
        "musicResponsiveListItemRenderer",
        "musicTwoRowItemRenderer",
        "musicCardShelfRenderer",
        "videoRenderer",
        "compactVideoRenderer",
    )
    private val durationPattern = Regex("""^\d{1,3}:\d{2}(?::\d{2})?$""")
    private val codecPattern = Regex("""(?i)\bcodecs\s*=\s*["']?([^"';\s]+)""")
    private val aacItags = setOf(139, 140, 141, 256, 258, 325, 328)

    fun search(response: JsonElement): List<MusicTrack> {
        val tracks = linkedMapOf<String, MusicTrack>()
        var visited = 0

        fun visit(value: JsonElement?, depth: Int) {
            if (depth > MAX_RENDERER_WALK_DEPTH || visited >= MAX_RENDERER_VISITS ||
                tracks.size >= MAX_SEARCH_RESULTS
            ) return
            visited++
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (tracks.size >= MAX_SEARCH_RESULTS) return@forEach
                    if (key in rendererNames && child is JsonObject) {
                        parseSearchRenderer(key, child)?.let { track ->
                            if (!tracks.containsKey(track.id)) tracks[track.id] = track
                        }
                    } else {
                        visit(child, depth + 1)
                    }
                }
                is JsonArray -> value.forEach { visit(it, depth + 1) }
                else -> Unit
            }
        }

        visit(response, 0)
        return tracks.values.toList()
    }

    fun radioQueue(response: JsonElement, clicked: MusicTrack): MusicRadioQueue {
        val queue = radioQueuePage(response)
        // A server-selected currentIndex can point elsewhere. Preserve the explicit click.
        return if (queue.tracks.any { it.id == clicked.id }) queue
            else queue.copy(tracks = listOf(clicked))
    }

    fun radioQueuePage(response: JsonElement): MusicRadioQueue {
        val tracks = mutableListOf<MusicTrack>()
        var title: String? = null
        walkRenderers(response) { key, value ->
            when (key) {
                "musicQueueHeaderRenderer" -> {
                    title = value.field("subtitle").displayText() ?: value.field("title").displayText()
                    true
                }
                "playlistPanelVideoRenderer" -> {
                    if (tracks.size < 500) parseSearchRenderer("videoRenderer", value)?.let(tracks::add)
                    true
                }
                else -> false
            }
        }
        return MusicRadioQueue(tracks, title)
    }

    fun radioAutomixEndpoint(response: JsonElement): JsonObject? {
        var endpoint: JsonObject? = null
        walkRenderers(response) { key, value ->
            if (key == "automixPreviewVideoRenderer") {
                val candidate = value.field("content").field("automixPlaylistVideoRenderer")
                    .field("navigationEndpoint").field("watchPlaylistEndpoint") as? JsonObject
                if (candidate?.field("playlistId").str()?.takeIf { it.length in 1..512 } != null) endpoint = candidate
                true
            } else false
        }
        return endpoint
    }

    fun moods(response: JsonElement): List<MusicMood> {
        val items = mutableListOf<MusicMood>()
        var visits = 0
        fun walk(node: JsonElement?, depth: Int) {
            if (depth > MAX_RENDERER_WALK_DEPTH || ++visits > MAX_RENDERER_VISITS || items.size >= 60) return
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (key == "musicNavigationButtonRenderer") {
                        val title = value.field("buttonText").displayText()
                        val endpoint = value.field("clickCommand").field("browseEndpoint")
                        val id = endpoint.field("browseId").str()
                        val color = value.field("solid").field("leftStripeColor").str()?.toLongOrNull()
                        if (title != null && id != null && color != null) items += MusicMood(
                            title, color, MusicCatalogItem.Browse(id, title, params = endpoint.field("params").str()),
                        )
                    } else walk(value, depth + 1)
                }
                is JsonArray -> node.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(response, 0)
        return items.distinctBy { it.endpoint.id to it.endpoint.params }
    }

    fun home(response: JsonElement): List<MusicShelf> {
        val shelves = mutableListOf<MusicShelf>()
        walkRenderers(response) { key, child ->
            if ((key == "musicCarouselShelfRenderer" || key == "musicShelfRenderer") && shelves.size < 40) {
                val header = child.field("header").field("musicCarouselShelfBasicHeaderRenderer")
                val title = header.field("title").displayText() ?: child.field("title").displayText()
                val items = mutableListOf<MusicCatalogItem>()
                val featured = mutableListOf<MusicFeaturedCard>()
                child.field("contents").elements().take(100).forEach { wrapper ->
                    (wrapper as? JsonObject)?.forEach { (type, renderer) ->
                        if (renderer is JsonObject && type in rendererNames + "musicMultiRowListItemRenderer") {
                            val item = parseCatalogItem(type, renderer)
                            item?.let(items::add)
                            if (type == "musicCardShelfRenderer" && item is MusicCatalogItem.Browse) {
                                val preview = renderer.field("contents").elements().mapNotNull {
                                    (it.field("musicResponsiveListItemRenderer") as? JsonObject)?.let { row ->
                                        parseSearchRenderer("musicResponsiveListItemRenderer", row)
                                    }
                                }
                                if (preview.isNotEmpty()) {
                                    featured += MusicFeaturedCard(item, preview)
                                    items += preview.map { MusicCatalogItem.Track(it) }
                                }
                            }
                        }
                    }
                }
                if (!title.isNullOrBlank() && items.isNotEmpty()) {
                    val endpoint = header.field("moreContentButton").field("buttonRenderer")
                        .field("navigationEndpoint").field("browseEndpoint")
                    shelves += MusicShelf(title, items.mapNotNull { (it as? MusicCatalogItem.Track)?.track },
                        items, header.field("strapline").displayText(), artwork(header),
                        endpoint.field("browseId").str(), endpoint.field("params").str(), featuredCards = featured)
                }
                true
            } else if (key == "musicCardShelfRenderer" && shelves.size < 40) {
                val item = parseCatalogItem(key, child) as? MusicCatalogItem.Browse
                val preview = child.field("contents").elements().mapNotNull {
                    (it.field("musicResponsiveListItemRenderer") as? JsonObject)?.let { row ->
                        parseSearchRenderer("musicResponsiveListItemRenderer", row)
                    }
                }
                if (item != null && preview.isNotEmpty()) {
                    val title = child.field("header").field("musicCardShelfHeaderBasicRenderer")
                        .field("title").displayText() ?: item.title
                    shelves += MusicShelf(title, preview, featuredCards = listOf(MusicFeaturedCard(item, preview)))
                }
                true
            } else false
        }
        return shelves
    }

    fun homeChips(response: JsonElement): List<MusicHomeChip> {
        val chips = mutableListOf<MusicHomeChip>()
        walkRenderers(response) { key, child ->
            if (key == "chipCloudChipRenderer" && chips.size < 50) {
                val endpoint = child.field("navigationEndpoint").field("browseEndpoint")
                val title = child.field("text").displayText()
                val id = endpoint.field("browseId").str()
                if (!title.isNullOrBlank() && !id.isNullOrBlank()) chips += MusicHomeChip(title, id, endpoint.field("params").str())
                true
            } else false
        }
        return chips
    }

    fun browse(response: JsonElement): MusicBrowsePage {
        var title: String? = null
        var subtitle: String? = null
        var image: String? = null
        var metadata: String? = null
        var description: String? = null
        val tracks = mutableListOf<MusicTrack>()
        var continuation: String? = null
        walkRenderers(response) { key, child ->
            when {
                key == "nextContinuationData" -> {
                    if (continuation == null) continuation = child.field("continuation").str()
                    true
                }
                key in setOf("musicDetailHeaderRenderer", "musicResponsiveHeaderRenderer", "musicImmersiveHeaderRenderer") -> {
                    if (title == null) {
                        title = child.field("title").displayText()
                        subtitle = child.field("subtitle").displayText() ?: child.field("secondSubtitle").displayText()
                        image = artwork(child)
                        metadata = child.field("secondSubtitle").displayText()
                        description = child.field("description").displayText()
                    }
                    false
                }
                key == "musicResponsiveListItemRenderer" && tracks.size < 500 -> {
                    parseSearchRenderer(key, child)?.let(tracks::add)
                    true
                }
                else -> false
            }
        }
        return MusicBrowsePage(title.orEmpty(), subtitle, image, tracks, home(response), continuation, metadata, description)
    }

    private fun parseCatalogItem(type: String, renderer: JsonObject): MusicCatalogItem? {
        val titleColumn = renderer.field("flexColumns").elements().firstOrNull()
            .field("musicResponsiveListItemFlexColumnRenderer").field("text")
        val endpoint = renderer.field("navigationEndpoint").field("browseEndpoint")
            ?: renderer.field("onTap").field("browseEndpoint")
            ?: renderer.field("title").field("runs").elements().firstOrNull().field("navigationEndpoint").field("browseEndpoint")
            ?: titleColumn.field("runs").elements().firstOrNull().field("navigationEndpoint").field("browseEndpoint")
        val id = endpoint.field("browseId").str()
        if (id != null) {
            val title = titleColumn.displayText() ?: renderer.field("title").displayText() ?: return null
            val secondary = renderer.field("flexColumns").elements().drop(1).firstOrNull()
                .field("musicResponsiveListItemFlexColumnRenderer").field("text").displayText()
                ?: renderer.field("subtitle").displayText()
            val pageType = endpoint.field("browseEndpointContextSupportedConfigs")
                .field("browseEndpointContextMusicConfig").field("pageType").str().orEmpty()
            val kind = when {
                pageType.contains("ARTIST") || id.startsWith("UC") -> MusicBrowseKind.ARTIST
                pageType.contains("ALBUM") || id.startsWith("MPRE") -> MusicBrowseKind.ALBUM
                pageType.contains("PLAYLIST") || id.startsWith("VL") -> MusicBrowseKind.PLAYLIST
                else -> MusicBrowseKind.OTHER
            }
            return MusicCatalogItem.Browse(id, title, secondary, artwork(renderer), kind, endpoint.field("params").str())
        }
        return parseSearchRenderer(type, renderer)?.let { MusicCatalogItem.Track(it) }
    }

    private fun artwork(renderer: JsonElement?): String? {
        val candidates = listOf(
            renderer.field("thumbnail").field("musicThumbnailRenderer").field("thumbnail"),
            renderer.field("thumbnailRenderer").field("musicThumbnailRenderer").field("thumbnail"),
            renderer.field("thumbnail"), renderer.field("thumbnailRenderer").field("thumbnail"),
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            (candidate.field("thumbnails").elements() + candidate.field("croppedSquareThumbnailRenderer")
                .field("thumbnail").field("thumbnails").elements()).asReversed()
                .firstNotNullOfOrNull { it.field("url").str()?.takeIf(::isPublicThumbnail) }
        }
    }

    /** Bounded walk, with callers able to prune a renderer after processing it. */
    private fun walkRenderers(response: JsonElement, visit: (String, JsonObject) -> Boolean) {
        var count = 0
        fun walk(value: JsonElement?, depth: Int) {
            if (depth > MAX_RENDERER_WALK_DEPTH || ++count > MAX_RENDERER_VISITS) return
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (child !is JsonObject || !visit(key, child)) walk(child, depth + 1)
                }
                is JsonArray -> value.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(response, 0)
    }

    /**
     * Stream response is bound to the *clicked* video ID, never to a title or search guess.
     * Unsupported media/cipher/SABR/POT cannot be made playable by passing it to AVPlayer.
     */
    fun resolve(response: JsonElement, clickedId: String, preferHls: Boolean = false): ResolvedAudio {
        val player = response.field("playerResponse") ?: response
        val playability = player.field("playabilityStatus")
        val status = playability.field("status").str()?.uppercase()
        val reasonText = buildString {
            append(playability.field("reason").str().orEmpty())
            playability.field("messages").elements().forEach {
                append(' ')
                append(it.str().orEmpty())
            }
            append(' ')
            append(playability.field("errorScreen").field("playerErrorMessageRenderer")
                .field("reason").displayText().orEmpty())
        }.lowercase()

        val responseId = player.field("videoDetails").field("videoId").str()
            ?: player.field("videoId").str()
        if (responseId != null && responseId != clickedId) {
            throw CatalogException(
                CatalogException.Reason.IDENTITY_MISMATCH,
                "YouTube returned a different video than the song you selected. Playback stopped.",
            )
        }

        if (reasonText.contains("po token") || reasonText.contains("potoken") ||
            reasonText.contains("proof of origin") || reasonText.contains("proof-of-origin") ||
            status == "PO_TOKEN_REQUIRED"
        ) {
            throw CatalogException(
                CatalogException.Reason.PO_TOKEN_REQUIRED,
                "YouTube requires a proof-of-origin token for this stream. This client cannot supply one.",
            )
        }
        if (status == "LOGIN_REQUIRED" || status == "AGE_CHECK_REQUIRED" ||
            status == "CONTENT_CHECK_REQUIRED" || status == "AGE_VERIFICATION_REQUIRED" ||
            reasonText.contains("sign in") || reasonText.contains("login required")
        ) {
            throw CatalogException(
                CatalogException.Reason.AUTH_REQUIRED,
                "This song requires a YouTube account or age verification; anonymous playback is unavailable.",
            )
        }
        if (status != null && status != "OK") {
            throw CatalogException(
                CatalogException.Reason.UNPLAYABLE,
                "YouTube did not allow playback for this song (unavailable, region-limited, or client-blocked).",
            )
        }
        if (responseId == null) {
            throw CatalogException(
                CatalogException.Reason.IDENTITY_MISMATCH,
                "YouTube omitted the video identity, so playback cannot be verified safely.",
            )
        }

        val streaming = player.field("streamingData")
        val hls = validatedMediaUrl(streaming.field("hlsManifestUrl").str())
        if (preferHls && hls != null && !hasEncryptedSignature(hls)) {
            // AVFoundation counts the edit-list duration twice for some YouTube direct
            // fragmented MP4 files. HLS carries the actual segment timeline; don't alter
            // the native clock or substitute catalog duration to conceal the mismatch.
            return ResolvedAudio(clickedId, hls, "application/vnd.apple.mpegurl")
        }
        val formats = streaming.field("adaptiveFormats").elements() +
            streaming.field("formats").elements()
        var hasCipher = formats.any { candidate ->
            candidate.field("signatureCipher").str() != null ||
                candidate.field("cipher").str() != null
        }
        val aac = formats.mapNotNull { candidate ->
            val mime = candidate.field("mimeType").str().orEmpty()
            if (!isSupportedAac(mime, candidate.field("itag").str()?.toIntOrNull())) return@mapNotNull null
            val rawUrl = candidate.field("url").str() ?: return@mapNotNull null
            val safeUrl = validatedMediaUrl(rawUrl) ?: return@mapNotNull null
            if (hasEncryptedSignature(safeUrl)) {
                hasCipher = true
                return@mapNotNull null
            }
            val bitrate = candidate.field("bitrate").str()?.toLongOrNull()?.coerceAtLeast(0)
                ?: candidate.field("averageBitrate").str()?.toLongOrNull()?.coerceAtLeast(0)
                ?: 0L
            bitrate to safeUrl
        }.maxByOrNull { it.first }

        if (aac != null) {
            return ResolvedAudio(
                trackId = clickedId,
                url = aac.second,
                mimeType = "audio/mp4",
            )
        }

        if (hls != null && !hasEncryptedSignature(hls)) {
            return ResolvedAudio(
                trackId = clickedId,
                url = hls,
                mimeType = "application/vnd.apple.mpegurl",
            )
        }
        if (hls != null && hasEncryptedSignature(hls)) hasCipher = true

        if (hasCipher) {
            throw CatalogException(
                CatalogException.Reason.SIGNATURE_REQUIRED,
                "YouTube supplied encrypted signatures; this client cannot decipher them. Try a supported playback client.",
            )
        }
        throw CatalogException(
            CatalogException.Reason.UNSUPPORTED_STREAM,
            "No AVPlayer-compatible AAC/MP4 or HLS stream was returned. YouTube may require a token, sign-in, or SABR playback.",
        )
    }

    /** Bounded HLS projection: prefer AAC-LC's audio-only rendition, retain master otherwise. */
    fun hlsAudioRendition(manifest: String): String? {
        if (manifest.length > 512_000 || !manifest.trimStart().startsWith("#EXTM3U")) return null
        val lines = manifest.lineSequence().take(2000).toList()
        fun attributes(line: String): Map<String, String> =
            Regex("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))").findAll(line.substringAfter(':'))
                .associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3]) }
        val codecs = mutableMapOf<String, String>()
        lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }.forEach { line ->
            val fields = attributes(line)
            fields["AUDIO"]?.let { codecs[it] = fields["CODECS"].orEmpty() }
        }
        return lines.asSequence().filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes)
            .filter { it["TYPE"] == "AUDIO" }
            .mapNotNull { fields ->
                val url = validatedMediaUrl(fields["URI"])?.takeUnless(::hasEncryptedSignature) ?: return@mapNotNull null
                val codec = codecs[fields["GROUP-ID"]].orEmpty()
                val rank = when { "mp4a.40.2" in codec -> 2; "mp4a.40.5" in codec -> 1; else -> 0 }
                Triple(rank, fields["DEFAULT"] == "YES", url)
            }.sortedWith(compareByDescending<Triple<Int, Boolean, String>> { it.first }.thenByDescending { it.second })
            .firstOrNull()?.third
    }

    internal fun isYouTubeVideoId(id: String): Boolean =
        id.length == 11 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    private fun parseSearchRenderer(type: String, renderer: JsonObject): MusicTrack? {
        val id = when (type) {
            "videoRenderer", "compactVideoRenderer" -> renderer.field("videoId").str()
                ?: renderer.field("navigationEndpoint").field("watchEndpoint").field("videoId").str()
            else -> renderer.field("playlistItemData").field("videoId").str()
                ?: renderer.field("navigationEndpoint").field("watchEndpoint").field("videoId").str()
                ?: renderer.field("onTap").field("watchEndpoint").field("videoId").str()
                ?: renderer.field("overlay").field("musicItemThumbnailOverlayRenderer")
                    .field("content").field("musicPlayButtonRenderer")
                    .field("playNavigationEndpoint").field("watchEndpoint").field("videoId").str()
                ?: renderer.field("title").field("runs").elements().firstOrNull()
                    .field("navigationEndpoint").field("watchEndpoint").field("videoId").str()
                ?: renderer.field("flexColumns").elements().firstOrNull()
                    .field("musicResponsiveListItemFlexColumnRenderer").field("text")
                    .field("runs").elements().firstOrNull()
                    .field("navigationEndpoint").field("watchEndpoint").field("videoId").str()
        } ?: return null
        if (!isYouTubeVideoId(id)) return null

        val titleColumn = renderer.field("flexColumns").elements().firstOrNull()
            .field("musicResponsiveListItemFlexColumnRenderer").field("text")
        val title = (titleColumn.displayText()
            ?: renderer.field("title").displayText()
            ?: renderer.field("headline").displayText()
            ?: "").trim()
        if (title.isEmpty()) return null

        val secondaryColumn = renderer.field("flexColumns").elements().drop(1).firstOrNull()
            .field("musicResponsiveListItemFlexColumnRenderer").field("text")
        val artist = (
            renderer.field("ownerText").displayText()
                ?: renderer.field("shortBylineText").displayText()
                ?: renderer.field("longBylineText").displayText()
                ?: artistFromRuns(secondaryColumn)
                ?: artistFromRuns(renderer.field("subtitle"))
                ?: ""
        ).trim()
        val durationText = renderer.field("fixedColumns").elements().firstOrNull()
            .field("musicResponsiveListItemFixedColumnRenderer").field("text").displayText()
            ?: renderer.field("lengthText").displayText()
            ?: renderer.field("thumbnailOverlays").elements().firstOrNull()
                .field("thumbnailOverlayTimeStatusRenderer").field("text").displayText()
        val duration = parseDuration(durationText)
            ?: secondaryColumn.field("runs").elements().firstNotNullOfOrNull {
                parseDuration(it.field("text").str())
            }
            ?: parseDuration(renderer.field("subtitle").displayText())

        val thumbnails = listOf(
            renderer.field("thumbnail").field("musicThumbnailRenderer").field("thumbnail").field("thumbnails"),
            renderer.field("thumbnailRenderer").field("musicThumbnailRenderer").field("thumbnail").field("thumbnails"),
            renderer.field("thumbnail").field("thumbnails"),
            renderer.field("thumbnailRenderer").field("thumbnail").field("thumbnails"),
            renderer.field("thumbnail").field("musicThumbnailRenderer").field("thumbnail")
                .field("croppedSquareThumbnailRenderer").field("thumbnail").field("thumbnails"),
        )
        val artwork = thumbnails.firstNotNullOfOrNull { thumbList ->
            thumbList.elements().asReversed().firstNotNullOfOrNull { thumb ->
                thumb.field("url").str()?.takeIf(::isPublicThumbnail)
            }
        }
        return MusicTrack(id = id, title = title, artist = artist, artworkUrl = artwork, durationMs = duration)
    }

    private fun artistFromRuns(text: JsonElement?): String? =
        text.field("runs").elements().mapNotNull { it.field("text").str()?.trim() }
            .firstOrNull { part ->
                part.isNotBlank() && part != "•" && part != "-" && part != "·" &&
                    part.lowercase() !in setOf("song", "video", "album", "single", "artist", "episode") &&
                    parseDuration(part) == null
            } ?: text.field("simpleText").str()

    private fun parseDuration(value: String?): Long? {
        val valueTrimmed = value?.trim() ?: return null
        if (!durationPattern.matches(valueTrimmed)) return null
        val fields = valueTrimmed.split(':').mapNotNull { it.toLongOrNull() }
        if (fields.size !in 2..3 || fields.last() >= 60L) return null
        val seconds = when (fields.size) {
            2 -> fields[0] * 60L + fields[1]
            else -> if (fields[1] >= 60L) return null
                else fields[0] * 3600L + fields[1] * 60L + fields[2]
        }
        return seconds.takeIf { it > 0L }?.times(1000L)
    }

    private fun isSupportedAac(mimeType: String, itag: Int?): Boolean {
        if (!mimeType.substringBefore(';').trim().equals("audio/mp4", ignoreCase = true)) return false
        val codec = codecPattern.find(mimeType)?.groupValues?.getOrNull(1)
        return if (codec != null) codec.startsWith("mp4a.40.", ignoreCase = true)
        else itag in aacItags
    }

    /** AVPlayer receives first-party HTTPS media URLs only; never forward a response-provided host. */
    private fun validatedMediaUrl(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.length in 15..16_384 } ?: return null
        if (!candidate.startsWith("https://", ignoreCase = true)) return null
        val parsed = try { Url(candidate) } catch (_: Exception) { return null }
        val host = parsed.host.lowercase()
        if (parsed.protocol != URLProtocol.HTTPS ||
            !(host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
        ) return null
        return candidate
    }

    private fun hasEncryptedSignature(url: String): Boolean =
        try { Url(url).parameters["s"] != null } catch (_: Exception) { true }

    private fun isPublicThumbnail(value: String): Boolean {
        if (!value.startsWith("https://", ignoreCase = true)) return false
        val parsed = try { Url(value) } catch (_: Exception) { return false }
        val host = parsed.host.lowercase()
        return parsed.protocol == URLProtocol.HTTPS &&
            (host == "ytimg.com" || host.endsWith(".ytimg.com") ||
                host == "ggpht.com" || host.endsWith(".ggpht.com") ||
                host == "googleusercontent.com" || host.endsWith(".googleusercontent.com"))
    }

    private fun JsonElement?.field(name: String): JsonElement? =
        (this as? JsonObject)?.get(name)

    private fun JsonElement?.elements(): List<JsonElement> =
        (this as? JsonArray)?.toList().orEmpty()

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonElement?.displayText(): String? {
        val simple = field("simpleText").str() ?: str()
        if (simple != null) return simple
        return field("runs").elements().mapNotNull { it.field("text").str() }
            .joinToString("").takeIf { it.isNotBlank() }
    }
}

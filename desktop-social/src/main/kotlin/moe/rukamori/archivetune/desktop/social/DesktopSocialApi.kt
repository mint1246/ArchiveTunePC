package moe.rukamori.archivetune.desktop.social

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SocialApiException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

class DesktopSocialApi(
    val baseUrl: String = DEFAULT_BASE_URL,
    internal val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    suspend fun exchangeAuthCode(code: String, nonce: String): StoredSocialSession {
        val payload = JSONObject().put("code", code).put("nonce", nonce)
        val value = requestJson("POST", "/v1/auth/exchange", body = payload)
        return StoredSocialSession(
            token = value.getString("token"),
            expiresAt = value.getLong("expiresAt"),
        )
    }

    suspend fun me(token: String): SocialUser =
        parseUser(requestJson("GET", "/v1/me", token).getJSONObject("user"))

    suspend fun friends(token: String): List<SocialUser> =
        requestJson("GET", "/v1/friends", token)
            .getJSONArray("friends")
            .mapObjects(::parseUser)

    suspend fun presence(token: String): Map<String, SocialPresence> {
        val array = requestJson("GET", "/v1/friends/presence", token).getJSONArray("friends")
        return buildMap {
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val userId = entry.getString("userId")
                put(userId, parsePresence(entry.getJSONObject("presence")))
            }
        }
    }

    suspend fun requests(token: String): Pair<List<SocialFriendRequest>, List<SocialFriendRequest>> {
        val value = requestJson("GET", "/v1/friend-requests", token)
        return value.getJSONArray("incoming").mapObjects(::parseFriendRequest) to
            value.getJSONArray("outgoing").mapObjects(::parseFriendRequest)
    }

    suspend fun search(token: String, query: String): List<SocialUser> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return requestJson("GET", "/v1/users/search?q=$encoded", token)
            .getJSONArray("users")
            .mapObjects(::parseUser)
    }

    suspend fun sendFriendRequest(token: String, userId: String) {
        request("POST", "/v1/friend-requests/${encodePath(userId)}", token = token)
    }

    suspend fun acceptFriendRequest(token: String, userId: String) {
        request("POST", "/v1/friend-requests/${encodePath(userId)}/accept", token = token)
    }

    suspend fun declineFriendRequest(token: String, userId: String) {
        request("DELETE", "/v1/friend-requests/${encodePath(userId)}", token = token)
    }

    suspend fun removeFriend(token: String, userId: String) {
        request("DELETE", "/v1/friends/${encodePath(userId)}", token = token)
    }

    suspend fun block(token: String, userId: String) {
        request("POST", "/v1/blocks/${encodePath(userId)}", token = token)
    }

    suspend fun sendPartyInvite(token: String, userId: String, partyUrl: String) {
        request(
            method = "POST",
            path = "/v1/party-invites/${encodePath(userId)}",
            token = token,
            body = JSONObject().put("partyUrl", partyUrl),
        )
    }

    suspend fun uploadStatistics(token: String, snapshot: SocialStatisticsSnapshot) {
        request(
            method = "PUT",
            path = "/v1/statistics",
            token = token,
            body = snapshot.toJson(),
        )
    }

    suspend fun friendStatistics(token: String, userId: String): SocialStatisticsSnapshot =
        parseStatisticsSnapshot(
            requestJson("GET", "/v1/friends/${encodePath(userId)}/statistics", token)
                .getJSONObject("statistics"),
        )

    suspend fun chatMessages(token: String, userId: String): List<SocialChatMessage> =
        requestJson("GET", "/v1/friends/${encodePath(userId)}/messages", token)
            .getJSONArray("messages")
            .mapObjects(::parseChatMessage)

    suspend fun sendChatMessage(
        token: String,
        userId: String,
        kind: SocialChatMessageKind,
        payload: JSONObject,
    ): SocialChatMessage =
        parseChatMessage(
            requestJson(
                method = "POST",
                path = "/v1/friends/${encodePath(userId)}/messages",
                token = token,
                body = JSONObject()
                    .put("kind", kind.name.lowercase())
                    .put("payload", payload),
            ).getJSONObject("message"),
        )

    suspend fun logout(token: String) {
        request("POST", "/v1/auth/logout", token = token)
    }

    fun discordLoginUrl(port: Int, nonce: String): URI =
        URI.create(
            "$baseUrl/auth/discord/start?port=$port&nonce=" +
                URLEncoder.encode(nonce, StandardCharsets.UTF_8),
        )

    internal fun liveUri(deviceId: String): URI =
        URI.create(
            baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
                "/v1/live?device=" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8),
        )

    private suspend fun requestJson(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
    ): JSONObject = JSONObject(request(method, path, token, body))

    private suspend fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
    ): String = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(30))
            .header("accept", "application/json")
        token?.let { builder.header("authorization", "Bearer $it") }
        val publisher = body?.toString()?.let {
            builder.header("content-type", "application/json")
            HttpRequest.BodyPublishers.ofString(it)
        } ?: HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val message = runCatching { JSONObject(response.body()).optString("error") }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: "ArchiveTune social request failed (${response.statusCode()})"
            throw SocialApiException(response.statusCode(), message)
        }
        response.body()
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        const val DEFAULT_BASE_URL = "https://archivetune-social.archivetune.workers.dev"
    }
}

internal fun parseUser(value: JSONObject): SocialUser =
    SocialUser(
        id = value.getString("id"),
        discordId = value.getString("discordId"),
        username = value.getString("username"),
        displayName = value.optNullableString("displayName"),
        avatarUrl = value.optNullableString("avatarUrl"),
    )

private fun parseFriendRequest(value: JSONObject): SocialFriendRequest =
    SocialFriendRequest(
        user = parseUser(value.getJSONObject("user")),
        createdAt = value.getLong("createdAt"),
    )

internal fun parseChatMessage(value: JSONObject): SocialChatMessage {
    val kind = when (value.getString("kind").lowercase()) {
        "text" -> SocialChatMessageKind.TEXT
        "song" -> SocialChatMessageKind.SONG
        "queue" -> SocialChatMessageKind.QUEUE
        "playlist" -> SocialChatMessageKind.PLAYLIST
        else -> error("Unsupported social chat message kind")
    }
    val payload = value.optJSONObject("payload") ?: JSONObject()
    return SocialChatMessage(
        id = value.get("id").toString(),
        senderId = value.getString("senderId"),
        receiverId = value.getString("recipientId"),
        kind = kind,
        text = if (kind == SocialChatMessageKind.TEXT) payload.optNullableString("text") else null,
        song = if (kind == SocialChatMessageKind.SONG) parseSharedSong(payload) else null,
        queue = if (kind == SocialChatMessageKind.QUEUE) parseSharedQueue(payload) else null,
        playlist = if (kind == SocialChatMessageKind.PLAYLIST) parseSharedPlaylist(payload) else null,
        createdAt = value.getLong("createdAt"),
    )
}

internal fun SocialSharedSong.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put(
            "artists",
            JSONArray().also { array ->
                artist?.takeIf(String::isNotBlank)?.let { array.put(JSONObject().put("name", it)) }
            },
        )
        .putOpt("artworkUri", artworkUrl)

internal fun SocialSharedQueue.toJson(): JSONObject {
    val sharedSongs = songs.take(50)
    return JSONObject()
        .putOpt("title", title)
        .put("currentIndex", currentIndex.coerceIn(0, (sharedSongs.size - 1).coerceAtLeast(0)))
        .put("items", JSONArray().also { array -> sharedSongs.forEach { array.put(it.toJson()) } })
}

internal fun SocialSharedPlaylist.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .putOpt("artworkUri", artworkUrl)

private fun parseSharedSong(value: JSONObject): SocialSharedSong =
    SocialSharedSong(
        id = value.getString("id"),
        title = value.getString("title"),
        artist = value.optJSONArray("artists")?.let { artists ->
            buildList {
                for (index in 0 until artists.length()) {
                    artists.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }.joinToString().takeIf(String::isNotBlank)
        },
        artworkUrl = value.optNullableString("artworkUri"),
    )

private fun parseSharedQueue(value: JSONObject): SocialSharedQueue =
    SocialSharedQueue(
        title = value.optNullableString("title"),
        songs = value.optJSONArray("items")?.mapObjects(::parseSharedSong).orEmpty(),
        currentIndex = value.optInt("currentIndex", 0),
    )

private fun parseSharedPlaylist(value: JSONObject): SocialSharedPlaylist =
    SocialSharedPlaylist(
        id = value.getString("id"),
        title = value.getString("title"),
        subtitle = null,
        artworkUrl = value.optNullableString("artworkUri"),
    )

private fun SocialStatisticsSnapshot.toJson(): JSONObject =
    JSONObject()
        .put("updatedAt", updatedAt)
        .put(
            "ranges",
            JSONObject().also { rangesJson ->
                ranges.forEach { (range, snapshot) -> rangesJson.put(range, snapshot.toJson()) }
            },
        )

private fun SocialStatisticsRangeSnapshot.toJson(): JSONObject =
    JSONObject()
        .put(
            "summary",
            JSONObject()
                .put("totalListeningMillis", summary.totalListeningMillis)
                .put("totalPlays", summary.totalPlays)
                .put("uniqueSongs", summary.uniqueSongs)
                .put("uniqueArtists", summary.uniqueArtists)
                .put("uniqueAlbums", summary.uniqueAlbums),
        )
        .putOpt("favoriteArtist", favoriteArtist?.toJson())
        .putOpt("favoriteSong", favoriteSong?.toJson())
        .put("dailyListening", dailyListening.toBucketJsonArray())
        .put("listeningByDayOfWeek", listeningByDayOfWeek.toBucketJsonArray())
        .put("listeningByHour", listeningByHour.toBucketJsonArray())
        .put("topSongs", topSongs.toMediaJsonArray())
        .put("topArtists", topArtists.toMediaJsonArray())
        .put("topAlbums", topAlbums.toMediaJsonArray())

private fun SocialStatisticsMedia.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .putOpt("subtitle", subtitle)
        .putOpt("artworkUrl", artworkUrl)
        .put("listeningMillis", listeningMillis)
        .put("playCount", playCount)

private fun List<SocialStatisticsBucket>.toBucketJsonArray(): JSONArray =
    JSONArray().also { array ->
        forEach { bucket ->
            array.put(
                JSONObject()
                    .put("label", bucket.label)
                    .put("listeningMillis", bucket.listeningMillis),
            )
        }
    }

private fun List<SocialStatisticsMedia>.toMediaJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { array.put(it.toJson()) } }

private fun parseStatisticsSnapshot(value: JSONObject): SocialStatisticsSnapshot {
    val rangesJson = value.getJSONObject("ranges")
    val ranges = buildMap {
        val keys = rangesJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, parseStatisticsRange(rangesJson.getJSONObject(key)))
        }
    }
    return SocialStatisticsSnapshot(
        ranges = ranges,
        updatedAt = value.optLong("updatedAt", 0L),
    )
}

private fun parseStatisticsRange(value: JSONObject): SocialStatisticsRangeSnapshot {
    val summary = value.getJSONObject("summary")
    return SocialStatisticsRangeSnapshot(
        summary = SocialStatisticsSummary(
            totalListeningMillis = summary.optLong("totalListeningMillis", 0L),
            totalPlays = summary.optLong("totalPlays", 0L),
            uniqueSongs = summary.optInt("uniqueSongs", 0),
            uniqueArtists = summary.optInt("uniqueArtists", 0),
            uniqueAlbums = summary.optInt("uniqueAlbums", 0),
        ),
        favoriteArtist = value.optJSONObject("favoriteArtist")?.let(::parseStatisticsMedia),
        favoriteSong = value.optJSONObject("favoriteSong")?.let(::parseStatisticsMedia),
        dailyListening = value.optJSONArray("dailyListening").parseStatisticsBuckets(),
        listeningByDayOfWeek = value.optJSONArray("listeningByDayOfWeek").parseStatisticsBuckets(),
        listeningByHour = value.optJSONArray("listeningByHour").parseStatisticsBuckets(),
        topSongs = value.optJSONArray("topSongs").parseStatisticsMediaList(),
        topArtists = value.optJSONArray("topArtists").parseStatisticsMediaList(),
        topAlbums = value.optJSONArray("topAlbums").parseStatisticsMediaList(),
    )
}

private fun parseStatisticsMedia(value: JSONObject): SocialStatisticsMedia =
    SocialStatisticsMedia(
        id = value.getString("id"),
        title = value.getString("title"),
        subtitle = value.optNullableString("subtitle"),
        artworkUrl = value.optNullableString("artworkUrl"),
        listeningMillis = value.optLong("listeningMillis", 0L),
        playCount = value.optLong("playCount", 0L),
    )

private fun JSONArray?.parseStatisticsBuckets(): List<SocialStatisticsBucket> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        getJSONObject(index).let { value ->
            SocialStatisticsBucket(
                label = value.getString("label"),
                listeningMillis = value.optLong("listeningMillis", 0L),
            )
        }
    }
}

private fun JSONArray?.parseStatisticsMediaList(): List<SocialStatisticsMedia> {
    if (this == null) return emptyList()
    return List(length()) { index -> parseStatisticsMedia(getJSONObject(index)) }
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

internal fun parsePresence(value: JSONObject): SocialPresence =
    SocialPresence(
        online = value.optBoolean("online", false),
        songId = value.optNullableString("songId"),
        title = value.optNullableString("title"),
        artist = value.optNullableString("artist"),
        artworkUrl = value.optNullableString("artworkUrl"),
        partyJoinable = value.optBoolean("partyJoinable", false),
    )

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { index -> transform(getJSONObject(index)) }

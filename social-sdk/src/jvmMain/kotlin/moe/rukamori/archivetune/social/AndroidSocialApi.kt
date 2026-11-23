/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AndroidSocialApi(
    override val baseUrl: String = DEFAULT_BASE_URL,
) : SocialApi {
    override suspend fun exchangeAuthCode(code: String, nonce: String): StoredSocialSession {
        val value = requestJson(
            method = "POST",
            path = "/v1/auth/exchange",
            body = JSONObject().put("code", code).put("nonce", nonce),
        )
        return StoredSocialSession(
            token = value.getString("token"),
            expiresAt = value.getLong("expiresAt"),
        )
    }

    override suspend fun me(token: String): SocialUser =
        parseSocialUser(requestJson("GET", "/v1/me", token).getJSONObject("user"))

    override suspend fun friends(token: String): List<SocialUser> =
        requestJson("GET", "/v1/friends", token)
            .getJSONArray("friends")
            .mapObjects(::parseSocialUser)

    override suspend fun presence(token: String): Map<String, SocialPresence> {
        val array = requestJson("GET", "/v1/friends/presence", token).getJSONArray("friends")
        return buildMap {
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                put(entry.getString("userId"), parseSocialPresence(entry.getJSONObject("presence")))
            }
        }
    }

    override suspend fun requests(token: String): Pair<List<SocialFriendRequest>, List<SocialFriendRequest>> {
        val value = requestJson("GET", "/v1/friend-requests", token)
        return value.getJSONArray("incoming").mapObjects(::parseFriendRequest) to
            value.getJSONArray("outgoing").mapObjects(::parseFriendRequest)
    }

    override suspend fun search(token: String, query: String): List<SocialUser> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return requestJson("GET", "/v1/users/search?q=$encoded", token)
            .getJSONArray("users")
            .mapObjects(::parseSocialUser)
    }

    override suspend fun sendFriendRequest(token: String, userId: String) {
        request("POST", "/v1/friend-requests/${encodePath(userId)}", token)
    }

    override suspend fun acceptFriendRequest(token: String, userId: String) {
        request("POST", "/v1/friend-requests/${encodePath(userId)}/accept", token)
    }

    override suspend fun declineFriendRequest(token: String, userId: String) {
        request("DELETE", "/v1/friend-requests/${encodePath(userId)}", token)
    }

    override suspend fun removeFriend(token: String, userId: String) {
        request("DELETE", "/v1/friends/${encodePath(userId)}", token)
    }

    override suspend fun block(token: String, userId: String) {
        request("POST", "/v1/blocks/${encodePath(userId)}", token)
    }

    override suspend fun sendPartyInvite(token: String, userId: String, partyUrl: String) {
        request(
            method = "POST",
            path = "/v1/party-invites/${encodePath(userId)}",
            token = token,
            body = JSONObject().put("partyUrl", partyUrl),
        )
    }

    override suspend fun chatMessages(token: String, userId: String): List<SocialChatMessage> =
        requestJson("GET", "/v1/friends/${encodePath(userId)}/messages", token)
            .getJSONArray("messages")
            .mapObjects(::parseSocialChatMessage)

    override suspend fun sendChatMessage(
        token: String,
        userId: String,
        message: SocialOutgoingMessage,
    ): SocialChatMessage {
        val (kind, payload) = message.toWirePayload()
        val response = requestJson(
            method = "POST",
            path = "/v1/friends/${encodePath(userId)}/messages",
            token = token,
            body = JSONObject().put("kind", kind).put("payload", payload),
        )
        return parseSocialChatMessage(response.getJSONObject("message"))
    }

    override suspend fun uploadStatistics(token: String, snapshot: SocialStatisticsSnapshot) {
        request(
            method = "PUT",
            path = "/v1/statistics",
            token = token,
            body = socialStatisticsSnapshotJson(snapshot),
        )
    }

    override suspend fun friendStatistics(token: String, userId: String): SocialStatisticsSnapshot =
        parseSocialStatisticsSnapshot(
            requestJson("GET", "/v1/friends/${encodePath(userId)}/statistics", token)
                .getJSONObject("statistics"),
        )

    override suspend fun logout(token: String) {
        request("POST", "/v1/auth/logout", token)
    }

    override fun discordLoginUrl(port: Int, nonce: String): String =
        "$baseUrl/auth/discord/start?port=$port&nonce=${URLEncoder.encode(nonce, Charsets.UTF_8.name())}"

    override fun liveUrl(deviceId: String): String =
        baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/v1/live?device=${URLEncoder.encode(deviceId, Charsets.UTF_8.name())}"

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
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) {
            connection.outputStream.use { output -> output.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(response).optString("error") }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: "ArchiveTune social request failed ($status)"
            throw SocialApiException(status, message)
        }
        response
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val DEFAULT_BASE_URL = "https://archivetune-social.archivetune.workers.dev"
    }
}

fun parseSocialUser(value: JSONObject): SocialUser =
    SocialUser(
        id = value.getString("id"),
        discordId = value.getString("discordId"),
        username = value.getString("username"),
        displayName = value.optNullableString("displayName"),
        avatarUrl = value.optNullableString("avatarUrl"),
    )

private fun parseFriendRequest(value: JSONObject): SocialFriendRequest =
    SocialFriendRequest(
        user = parseSocialUser(value.getJSONObject("user")),
        createdAt = value.getLong("createdAt"),
    )

fun parseSocialPresence(value: JSONObject): SocialPresence =
    SocialPresence(
        online = value.optBoolean("online", false),
        songId = value.optNullableString("songId"),
        title = value.optNullableString("title"),
        artist = value.optNullableString("artist"),
        artworkUrl = value.optNullableString("artworkUrl"),
        partyJoinable = value.optBoolean("partyJoinable", false),
    )

fun parseSocialChatMessage(value: JSONObject): SocialChatMessage {
    val kind = when (value.getString("kind").lowercase()) {
        "text" -> SocialChatMessageKind.TEXT
        "song" -> SocialChatMessageKind.SONG
        "queue" -> SocialChatMessageKind.QUEUE
        "playlist" -> SocialChatMessageKind.PLAYLIST
        else -> throw IOException("Unsupported social chat message kind")
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

fun parseSocialStatisticsSnapshot(value: JSONObject): SocialStatisticsSnapshot {
    val rangesJson = value.optJSONObject("ranges") ?: JSONObject()
    val ranges = buildMap {
        val keys = rangesJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            rangesJson.optJSONObject(key)?.let { put(key, parseSocialStatisticsRange(it)) }
        }
    }
    return SocialStatisticsSnapshot(
        ranges = ranges,
        updatedAt = value.optLong("updatedAt", 0L),
    )
}

private fun parseSocialStatisticsRange(value: JSONObject): SocialStatisticsRangeSnapshot {
    val summary = value.optJSONObject("summary") ?: JSONObject()
    return SocialStatisticsRangeSnapshot(
        summary = SocialStatisticsSummary(
            totalListeningMillis = summary.optLong("totalListeningMillis", 0L),
            totalPlays = summary.optLong("totalPlays", 0L),
            uniqueSongs = summary.optInt("uniqueSongs", 0),
            uniqueArtists = summary.optInt("uniqueArtists", 0),
            uniqueAlbums = summary.optInt("uniqueAlbums", 0),
        ),
        favoriteArtist = value.optJSONObject("favoriteArtist")?.let(::parseSocialStatisticsMedia),
        favoriteSong = value.optJSONObject("favoriteSong")?.let(::parseSocialStatisticsMedia),
        dailyListening = value.optJSONArray("dailyListening").toStatisticsBucketList(),
        listeningByDayOfWeek = value.optJSONArray("listeningByDayOfWeek").toStatisticsBucketList(),
        listeningByHour = value.optJSONArray("listeningByHour").toStatisticsBucketList(),
        topSongs = value.optJSONArray("topSongs").toStatisticsMediaList(),
        topArtists = value.optJSONArray("topArtists").toStatisticsMediaList(),
        topAlbums = value.optJSONArray("topAlbums").toStatisticsMediaList(),
    )
}

private fun parseSocialStatisticsMedia(value: JSONObject): SocialStatisticsMedia =
    SocialStatisticsMedia(
        id = value.getString("id"),
        title = value.getString("title"),
        subtitle = value.optNullableString("subtitle"),
        artworkUrl = value.optNullableString("artworkUrl"),
        listeningMillis = value.optLong("listeningMillis", 0L),
        playCount = value.optLong("playCount", 0L),
    )

private fun JSONArray?.toStatisticsMediaList(): List<SocialStatisticsMedia> =
    if (this == null) emptyList() else List(length()) { parseSocialStatisticsMedia(getJSONObject(it)) }

private fun JSONArray?.toStatisticsBucketList(): List<SocialStatisticsBucket> =
    if (this == null) {
        emptyList()
    } else {
        List(length()) { index ->
            getJSONObject(index).let { value ->
                SocialStatisticsBucket(
                    label = value.getString("label"),
                    listeningMillis = value.optLong("listeningMillis", 0L),
                )
            }
        }
    }

fun socialStatisticsSnapshotJson(snapshot: SocialStatisticsSnapshot): JSONObject =
    JSONObject()
        .put("updatedAt", snapshot.updatedAt)
        .put(
            "ranges",
            JSONObject().also { rangesJson ->
                snapshot.ranges.forEach { (range, value) -> rangesJson.put(range, value.toJson()) }
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

fun SocialOutgoingMessage.toWirePayload(): Pair<String, JSONObject> = when (this) {
    is SocialOutgoingMessage.Text -> "text" to JSONObject().put("text", text.trim())
    is SocialOutgoingMessage.Song -> "song" to song.toJson()
    is SocialOutgoingMessage.Queue -> "queue" to queue.toJson()
    is SocialOutgoingMessage.Playlist -> "playlist" to playlist.toJson()
}

private fun SocialSharedSong.toJson(): JSONObject =
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

private fun SocialSharedQueue.toJson(): JSONObject {
    val sharedSongs = songs.take(50)
    return JSONObject()
        .putOpt("title", title)
        .put("currentIndex", currentIndex.coerceIn(0, (sharedSongs.size - 1).coerceAtLeast(0)))
        .put("items", JSONArray().also { array -> sharedSongs.forEach { array.put(it.toJson()) } })
}

private fun SocialSharedPlaylist.toJson(): JSONObject =
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
        id = value.optString("id"),
        title = value.getString("title"),
        artworkUrl = value.optNullableString("artworkUri"),
    )

internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { index -> transform(getJSONObject(index)) }

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Parsed realtime frame from the ArchiveTune social WebSocket service. */
sealed interface SocialRealtimeFrame {
    data class InitialPresence(val presence: Map<String, SocialPresence>) : SocialRealtimeFrame
    data class FriendPresence(val userId: String, val presence: SocialPresence) : SocialRealtimeFrame
    data class FriendRequest(val from: SocialUser?) : SocialRealtimeFrame
    data class FriendAccepted(val user: SocialUser?) : SocialRealtimeFrame
    data object FriendRemoved : SocialRealtimeFrame
    data class PartyInvite(val invite: SocialPartyInvite) : SocialRealtimeFrame
    data class ChatMessage(val message: SocialChatMessage) : SocialRealtimeFrame
}

/** Portable kotlinx.serialization.json codecs for ArchiveTune's social wire format. */
object SocialWireJson {
    fun parseObject(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalStateException("Expected JSON object")

    fun parseSession(obj: JsonObject): StoredSocialSession = StoredSocialSession(
        token = obj.requiredString("token"),
        expiresAt = obj.long("expiresAt") ?: Long.MAX_VALUE,
    )

    fun parseUser(obj: JsonObject): SocialUser = SocialUser(
        id = obj.string("id").orEmpty(),
        discordId = obj.string("discordId").orEmpty(),
        username = obj.string("username").orEmpty(),
        displayName = obj.nullableString("displayName") ?: obj.nullableString("name"),
        avatarUrl = obj.nullableString("avatarUrl"),
    )

    fun parseFriendRequest(obj: JsonObject): SocialFriendRequest = SocialFriendRequest(
        user = parseUser(obj.requiredObject("user")),
        createdAt = obj.long("createdAt") ?: 0L,
    )

    fun parsePresence(obj: JsonObject): SocialPresence = SocialPresence(
        online = obj.boolean("online") ?: false,
        songId = obj.nullableString("songId"),
        title = obj.nullableString("title"),
        artist = obj.nullableString("artist"),
        artworkUrl = obj.nullableString("artworkUrl"),
        partyJoinable = obj.boolean("partyJoinable") ?: false,
    )

    fun parseChatMessage(obj: JsonObject): SocialChatMessage {
        val kind = when (obj.requiredString("kind").lowercase()) {
            "text" -> SocialChatMessageKind.TEXT
            "song" -> SocialChatMessageKind.SONG
            "queue" -> SocialChatMessageKind.QUEUE
            "playlist" -> SocialChatMessageKind.PLAYLIST
            else -> throw IllegalStateException("Unsupported social chat message kind")
        }
        val payload = obj["payload"] as? JsonObject ?: buildJsonObject {}
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("Missing message id")
        return SocialChatMessage(
            id = id,
            senderId = obj.requiredString("senderId"),
            receiverId = obj.requiredString("recipientId"),
            kind = kind,
            text = if (kind == SocialChatMessageKind.TEXT) payload.nullableString("text") else null,
            song = if (kind == SocialChatMessageKind.SONG) parseSharedSong(payload) else null,
            queue = if (kind == SocialChatMessageKind.QUEUE) parseSharedQueue(payload) else null,
            playlist = if (kind == SocialChatMessageKind.PLAYLIST) parseSharedPlaylist(payload) else null,
            createdAt = obj.long("createdAt") ?: 0L,
        )
    }

    fun parseStatisticsSnapshot(obj: JsonObject): SocialStatisticsSnapshot {
        val rangesObj = obj["ranges"] as? JsonObject ?: buildJsonObject {}
        val ranges = buildMap {
            rangesObj.forEach { (key, value) ->
                (value as? JsonObject)?.let { put(key, parseStatisticsRange(it)) }
            }
        }
        return SocialStatisticsSnapshot(
            ranges = ranges,
            updatedAt = obj.long("updatedAt") ?: 0L,
        )
    }

    fun encodeStatisticsSnapshot(snapshot: SocialStatisticsSnapshot): JsonObject = buildJsonObject {
        put("updatedAt", snapshot.updatedAt)
        put("ranges", buildJsonObject {
            snapshot.ranges.forEach { (range, value) -> put(range, encodeStatisticsRange(value)) }
        })
    }

    fun encodeOutgoingMessage(message: SocialOutgoingMessage): Pair<String, JsonObject> = when (message) {
        is SocialOutgoingMessage.Text -> "text" to buildJsonObject { put("text", message.text.trim()) }
        is SocialOutgoingMessage.Song -> "song" to encodeSharedSong(message.song)
        is SocialOutgoingMessage.Queue -> "queue" to encodeSharedQueue(message.queue)
        is SocialOutgoingMessage.Playlist -> "playlist" to encodeSharedPlaylist(message.playlist)
    }

    fun parseRealtimeFrame(text: String, nowMillis: () -> Long): SocialRealtimeFrame? {
        val value = runCatching { parseObject(text) }.getOrNull() ?: return null
        return when (value.string("type")) {
            "presence.initial" -> {
                val friends = value["friends"] as? JsonArray ?: return null
                val updates = buildMap {
                    friends.forEach { entry ->
                        val obj = entry as? JsonObject ?: return@forEach
                        val userId = obj.nullableString("userId") ?: return@forEach
                        val presenceObj = obj["presence"] as? JsonObject ?: buildJsonObject {}
                        put(userId, parsePresence(presenceObj))
                    }
                }
                SocialRealtimeFrame.InitialPresence(updates)
            }
            "friend_presence" -> {
                val userId = value.nullableString("userId") ?: return null
                val presenceObj = value["presence"] as? JsonObject ?: return null
                SocialRealtimeFrame.FriendPresence(userId, parsePresence(presenceObj))
            }
            "friend_request" -> {
                val from = (value["from"] as? JsonObject)?.let(::parseUser)
                SocialRealtimeFrame.FriendRequest(from)
            }
            "friend_accepted" -> {
                val user = (value["user"] as? JsonObject)?.let(::parseUser)
                SocialRealtimeFrame.FriendAccepted(user)
            }
            "friend_removed" -> SocialRealtimeFrame.FriendRemoved
            "party_invite" -> {
                val sender = (value["sender"] as? JsonObject)?.let(::parseUser) ?: return null
                val partyUrl = value.nullableString("partyUrl") ?: return null
                val sentAt = value.long("sentAt") ?: nowMillis()
                SocialRealtimeFrame.PartyInvite(SocialPartyInvite(sender, partyUrl, sentAt))
            }
            "message" -> {
                val msgObj = value["message"] as? JsonObject ?: return null
                val msg = runCatching { parseChatMessage(msgObj) }.getOrNull() ?: return null
                SocialRealtimeFrame.ChatMessage(msg)
            }
            else -> null
        }
    }

    private fun parseStatisticsRange(obj: JsonObject): SocialStatisticsRangeSnapshot {
        val summary = obj["summary"] as? JsonObject ?: buildJsonObject {}
        return SocialStatisticsRangeSnapshot(
            summary = SocialStatisticsSummary(
                totalListeningMillis = summary.long("totalListeningMillis") ?: 0L,
                totalPlays = summary.long("totalPlays") ?: 0L,
                uniqueSongs = summary.int("uniqueSongs") ?: 0,
                uniqueArtists = summary.int("uniqueArtists") ?: 0,
                uniqueAlbums = summary.int("uniqueAlbums") ?: 0,
            ),
            favoriteArtist = (obj["favoriteArtist"] as? JsonObject)?.let(::parseStatisticsMedia),
            favoriteSong = (obj["favoriteSong"] as? JsonObject)?.let(::parseStatisticsMedia),
            dailyListening = (obj["dailyListening"] as? JsonArray).toBucketList(),
            listeningByDayOfWeek = (obj["listeningByDayOfWeek"] as? JsonArray).toBucketList(),
            listeningByHour = (obj["listeningByHour"] as? JsonArray).toBucketList(),
            topSongs = (obj["topSongs"] as? JsonArray).toMediaList(),
            topArtists = (obj["topArtists"] as? JsonArray).toMediaList(),
            topAlbums = (obj["topAlbums"] as? JsonArray).toMediaList(),
        )
    }

    private fun parseStatisticsMedia(obj: JsonObject): SocialStatisticsMedia = SocialStatisticsMedia(
        id = obj.requiredString("id"),
        title = obj.requiredString("title"),
        subtitle = obj.nullableString("subtitle"),
        artworkUrl = obj.nullableString("artworkUrl"),
        listeningMillis = obj.long("listeningMillis") ?: 0L,
        playCount = obj.long("playCount") ?: 0L,
    )

    private fun JsonArray?.toMediaList(): List<SocialStatisticsMedia> =
        this?.mapNotNull { (it as? JsonObject)?.let(::parseStatisticsMedia) }.orEmpty()

    private fun JsonArray?.toBucketList(): List<SocialStatisticsBucket> =
        this?.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            SocialStatisticsBucket(
                label = obj.requiredString("label"),
                listeningMillis = obj.long("listeningMillis") ?: 0L,
            )
        }.orEmpty()

    private fun encodeStatisticsRange(value: SocialStatisticsRangeSnapshot): JsonObject = buildJsonObject {
        put("summary", buildJsonObject {
            put("totalListeningMillis", value.summary.totalListeningMillis)
            put("totalPlays", value.summary.totalPlays)
            put("uniqueSongs", value.summary.uniqueSongs)
            put("uniqueArtists", value.summary.uniqueArtists)
            put("uniqueAlbums", value.summary.uniqueAlbums)
        })
        value.favoriteArtist?.let { put("favoriteArtist", encodeStatisticsMedia(it)) }
        value.favoriteSong?.let { put("favoriteSong", encodeStatisticsMedia(it)) }
        put("dailyListening", encodeBuckets(value.dailyListening))
        put("listeningByDayOfWeek", encodeBuckets(value.listeningByDayOfWeek))
        put("listeningByHour", encodeBuckets(value.listeningByHour))
        put("topSongs", encodeMediaList(value.topSongs))
        put("topArtists", encodeMediaList(value.topArtists))
        put("topAlbums", encodeMediaList(value.topAlbums))
    }

    private fun encodeStatisticsMedia(value: SocialStatisticsMedia): JsonObject = buildJsonObject {
        put("id", value.id)
        put("title", value.title)
        value.subtitle?.let { put("subtitle", it) }
        value.artworkUrl?.let { put("artworkUrl", it) }
        put("listeningMillis", value.listeningMillis)
        put("playCount", value.playCount)
    }

    private fun encodeBuckets(buckets: List<SocialStatisticsBucket>): JsonArray = buildJsonArray {
        buckets.forEach { bucket ->
            add(buildJsonObject {
                put("label", bucket.label)
                put("listeningMillis", bucket.listeningMillis)
            })
        }
    }

    private fun encodeMediaList(list: List<SocialStatisticsMedia>): JsonArray = buildJsonArray {
        list.forEach { add(encodeStatisticsMedia(it)) }
    }

    private fun encodeSharedSong(song: SocialSharedSong): JsonObject = buildJsonObject {
        put("id", song.id)
        put("title", song.title)
        put("artists", buildJsonArray {
            song.artist?.takeIf(String::isNotBlank)?.let { add(buildJsonObject { put("name", it) }) }
        })
        song.artworkUrl?.let { put("artworkUri", it) }
    }

    private fun encodeSharedQueue(queue: SocialSharedQueue): JsonObject {
        val sharedSongs = queue.songs.take(50)
        return buildJsonObject {
            queue.title?.let { put("title", it) }
            put("currentIndex", queue.currentIndex.coerceIn(0, (sharedSongs.size - 1).coerceAtLeast(0)))
            put("items", buildJsonArray { sharedSongs.forEach { add(encodeSharedSong(it)) } })
        }
    }

    private fun encodeSharedPlaylist(playlist: SocialSharedPlaylist): JsonObject = buildJsonObject {
        put("id", playlist.id)
        put("title", playlist.title)
        playlist.artworkUrl?.let { put("artworkUri", it) }
    }

    private fun parseSharedSong(obj: JsonObject): SocialSharedSong = SocialSharedSong(
        id = obj.requiredString("id"),
        title = obj.requiredString("title"),
        artist = (obj["artists"] as? JsonArray)?.mapNotNull { entry ->
            (entry as? JsonObject)?.nullableString("name")
        }?.joinToString()?.takeIf(String::isNotBlank),
        artworkUrl = obj.nullableString("artworkUri"),
    )

    private fun parseSharedQueue(obj: JsonObject): SocialSharedQueue = SocialSharedQueue(
        title = obj.nullableString("title"),
        songs = (obj["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseSharedSong) }.orEmpty(),
        currentIndex = obj.int("currentIndex") ?: 0,
    )

    private fun parseSharedPlaylist(obj: JsonObject): SocialSharedPlaylist = SocialSharedPlaylist(
        id = obj.string("id").orEmpty(),
        title = obj.requiredString("title"),
        artworkUrl = obj.nullableString("artworkUri"),
    )

    internal fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    internal fun JsonObject.nullableString(key: String): String? =
        string(key)?.takeIf(String::isNotBlank)

    internal fun JsonObject.requiredString(key: String): String =
        string(key) ?: throw IllegalStateException("Missing string field '$key'")

    internal fun JsonObject.requiredObject(key: String): JsonObject =
        get(key) as? JsonObject ?: throw IllegalStateException("Missing object field '$key'")

    internal fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.longOrNull

    internal fun JsonObject.int(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    internal fun JsonObject.boolean(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.booleanOrNull
}

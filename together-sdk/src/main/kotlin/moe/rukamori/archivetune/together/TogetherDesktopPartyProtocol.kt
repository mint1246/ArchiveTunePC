/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Compatibility codec for the desktop Play Together Quick-Tunnel protocol. */
object TogetherDesktopPartyProtocol {
    private const val TYPE_FULL = "full"
    private const val TYPE_TRANSPORT = "transport"
    private const val TYPE_PERMISSIONS = "permissions"
    private const val TYPE_OFFER = "offer"
    private const val TYPE_ANSWER = "answer"
    private const val TYPE_PING = "ping"
    private const val TYPE_PONG = "pong"

    val knownTypes =
        setOf(
            TYPE_FULL,
            TYPE_TRANSPORT,
            TYPE_PERMISSIONS,
            TYPE_OFFER,
            TYPE_ANSWER,
            TYPE_PING,
            TYPE_PONG,
            "set_playing",
            "skip_next",
            "skip_previous",
            "seek",
            "queue_select",
            "queue_replace",
            "queue_add",
            "queue_play_next",
            "queue_remove",
        )

    fun parse(raw: String): JsonObject? =
        runCatching { TogetherJson.json.parseToJsonElement(raw).jsonObject }.getOrNull()

    fun type(message: JsonObject): String? = message.string("type")

    fun isKnown(message: JsonObject): Boolean = type(message) in knownTypes

    fun decodePermissions(message: JsonObject): TogetherGuestPermissions =
        TogetherGuestPermissions(
            canEditQueue = message.boolean("canEditQueue", false),
            canControlPlayback = message.boolean("canControlPlayback", false),
            canChangeSong = message.boolean("canChangeSong", false),
            canSeek = message.boolean("canSeek", false),
        )

    fun mergeRemoteSnapshot(
        message: JsonObject,
        sessionId: String,
        selfParticipantId: String,
        previous: TogetherRoomState?,
        permissions: TogetherGuestPermissions,
        nowElapsedRealtimeMs: Long,
    ): TogetherRoomState? {
        return when (type(message)) {
            TYPE_FULL -> {
                val tracks = message["queue"]?.jsonArrayOrNull()?.mapNotNull(::decodeTrack).orEmpty()
                val maxIndex = (tracks.size - 1).coerceAtLeast(0)
                TogetherRoomState(
                    sessionId = sessionId,
                    hostId = DESKTOP_HOST_ID,
                    participants = participants(selfParticipantId, permissions),
                    settings = permissions.toRoomSettings(),
                    queue = tracks,
                    queueHash = message.string("queueRevision").orEmpty(),
                    currentIndex = message.int("currentIndex", 0).coerceIn(0, maxIndex),
                    isPlaying = message.boolean("playWhenReady", false),
                    positionMs = message.long("positionMs", 0L).coerceAtLeast(0L),
                    repeatMode = repeatMode(message.string("repeatMode")),
                    shuffleEnabled = message.boolean("shuffleEnabled", false),
                    sentAtElapsedRealtimeMs = nowElapsedRealtimeMs,
                )
            }

            TYPE_TRANSPORT -> {
                val old = previous ?: return null
                val maxIndex = (old.queue.size - 1).coerceAtLeast(0)
                old.copy(
                    participants = participants(selfParticipantId, permissions),
                    settings = permissions.toRoomSettings(),
                    currentIndex = message.int("currentIndex", old.currentIndex).coerceIn(0, maxIndex),
                    isPlaying = message.boolean("playWhenReady", old.isPlaying),
                    positionMs = message.long("positionMs", old.positionMs).coerceAtLeast(0L),
                    repeatMode = repeatMode(message.string("repeatMode"), old.repeatMode),
                    shuffleEnabled = message.boolean("shuffleEnabled", old.shuffleEnabled),
                    sentAtElapsedRealtimeMs = nowElapsedRealtimeMs,
                )
            }

            TYPE_PERMISSIONS -> {
                val old = previous
                    ?: TogetherRoomState(
                        sessionId = sessionId,
                        hostId = DESKTOP_HOST_ID,
                    )
                old.copy(
                    participants = participants(selfParticipantId, permissions),
                    settings = permissions.toRoomSettings(),
                    sentAtElapsedRealtimeMs = nowElapsedRealtimeMs,
                )
            }

            else -> null
        }
    }

    fun encodePermissions(permissions: TogetherGuestPermissions): String =
        buildJsonObject {
            put("type", TYPE_PERMISSIONS)
            put("canEditQueue", permissions.canEditQueue)
            put("canControlPlayback", permissions.canControlPlayback)
            put("canChangeSong", permissions.canChangeSong)
            put("canSeek", permissions.canSeek)
        }.toString()

    fun encodeFull(state: TogetherRoomState): String =
        buildJsonObject {
            put("type", TYPE_FULL)
            put("queueRevision", state.queueHash)
            put("queueTitle", "")
            put("currentIndex", state.currentIndex)
            put("positionMs", state.positionMs)
            put("playWhenReady", state.isPlaying)
            put("repeatMode", repeatModeName(state.repeatMode))
            put("shuffleEnabled", state.shuffleEnabled)
            put("speed", 1.0)
            put("queue", buildJsonArray { state.queue.forEach { add(encodeTrack(it)) } })
        }.toString()

    fun encodeTransport(state: TogetherRoomState): String =
        buildJsonObject {
            put("type", TYPE_TRANSPORT)
            put("currentIndex", state.currentIndex)
            put("positionMs", state.positionMs)
            put("playWhenReady", state.isPlaying)
            put("repeatMode", repeatModeName(state.repeatMode))
            put("shuffleEnabled", state.shuffleEnabled)
            put("speed", 1.0)
        }.toString()

    fun encodePong(nonce: Long): String =
        buildJsonObject {
            put("type", TYPE_PONG)
            put("nonce", nonce)
        }.toString()

    fun encodePing(nonce: Long): String =
        buildJsonObject {
            put("type", TYPE_PING)
            put("nonce", nonce)
        }.toString()

    fun encodeControl(action: ControlAction, state: TogetherRoomState?): String? =
        when (action) {
            ControlAction.Play -> command("set_playing") { put("playWhenReady", true) }
            ControlAction.Pause -> command("set_playing") { put("playWhenReady", false) }
            ControlAction.SkipNext -> command("skip_next")
            ControlAction.SkipPrevious -> command("skip_previous")
            is ControlAction.SeekTo -> command("seek") { put("positionMs", action.positionMs.coerceAtLeast(0L)) }
            is ControlAction.SeekToIndex -> encodeQueueSelect(action.index, action.positionMs, state)
            is ControlAction.SeekToTrack -> {
                val index = state?.queue?.indexOfFirst { it.id == action.trackId } ?: -1
                encodeQueueSelect(index, action.positionMs, state)
            }
            is ControlAction.SetRepeatMode,
            is ControlAction.SetShuffleEnabled,
            -> null
        }

    fun encodeAddTrack(
        track: TogetherTrack,
        mode: AddTrackMode,
        state: TogetherRoomState?,
    ): String =
        buildJsonObject {
            put("type", if (mode == AddTrackMode.PLAY_NEXT) "queue_play_next" else "queue_add")
            put("queueRevision", state?.queueHash.orEmpty())
            put("items", buildJsonArray { add(encodeTrack(track)) })
        }.toString()

    fun encodePlayNowTrack(
        track: TogetherTrack,
        state: TogetherRoomState?,
    ): String {
        val queue = state?.queue.orEmpty().toMutableList()
        val insertionIndex =
            if (queue.isEmpty()) {
                0
            } else {
                (state?.currentIndex?.coerceIn(0, queue.lastIndex)?.plus(1) ?: queue.size).coerceAtMost(queue.size)
            }
        queue.add(insertionIndex, track)
        return buildJsonObject {
            put("type", "queue_replace")
            put("startIndex", insertionIndex)
            put("queueTitle", "")
            put("items", buildJsonArray { queue.forEach { add(encodeTrack(it)) } })
        }.toString()
    }

    fun decodeGuestControl(message: JsonObject): ControlAction? =
        when (type(message)) {
            "set_playing" -> if (message.boolean("playWhenReady", false)) ControlAction.Play else ControlAction.Pause
            "skip_next" -> ControlAction.SkipNext
            "skip_previous" -> ControlAction.SkipPrevious
            "seek" -> message["positionMs"]?.jsonPrimitive?.longOrNull?.let { ControlAction.SeekTo(it.coerceAtLeast(0L)) }
            "queue_select" -> {
                val index = message.int("logicalIndex", -1)
                if (index < 0) null else ControlAction.SeekToIndex(index = index, positionMs = 0L)
            }
            else -> null
        }

    fun decodeGuestAddTracks(message: JsonObject): Pair<AddTrackMode, List<TogetherTrack>>? {
        val mode =
            when (type(message)) {
                "queue_add" -> AddTrackMode.ADD_TO_QUEUE
                "queue_play_next" -> AddTrackMode.PLAY_NEXT
                else -> return null
            }
        val tracks = message["items"]?.jsonArrayOrNull()?.mapNotNull(::decodeTrack).orEmpty()
        return if (tracks.isEmpty()) null else mode to tracks
    }

    fun decodeGuestQueueReplace(message: JsonObject): Pair<Int, List<TogetherTrack>>? {
        if (type(message) != "queue_replace") return null
        val tracks = message["items"]?.jsonArrayOrNull()?.mapNotNull(::decodeTrack).orEmpty()
        if (tracks.isEmpty()) return null
        val startIndex = message.int("startIndex", 0)
        return if (startIndex in tracks.indices) startIndex to tracks else null
    }

    fun decodeGuestQueueRemove(message: JsonObject): Pair<Int, String>? {
        if (type(message) != "queue_remove") return null
        val index = message.int("logicalIndex", -1)
        val expectedId = message.string("expectedId").orEmpty()
        return if (index >= 0 && expectedId.isNotBlank()) index to expectedId else null
    }

    fun nonce(message: JsonObject): Long = message.long("nonce", 0L)

    private fun encodeQueueSelect(
        index: Int,
        positionMs: Long,
        state: TogetherRoomState?,
    ): String? {
        val snapshot = state ?: return null
        val item = snapshot.queue.getOrNull(index) ?: return null
        return buildJsonObject {
            put("type", "queue_select")
            put("logicalIndex", index)
            put("expectedId", item.id)
            put("queueRevision", snapshot.queueHash)
            if (positionMs > 0L) put("positionMs", positionMs)
        }.toString()
    }

    private inline fun command(
        type: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): String = buildJsonObject {
        put("type", type)
        extra()
    }.toString()

    private fun participants(
        selfParticipantId: String,
        permissions: TogetherGuestPermissions,
    ): List<TogetherParticipant> =
        listOf(
            TogetherParticipant(
                id = DESKTOP_HOST_ID,
                name = "Host",
                isHost = true,
                permissions = TogetherGuestPermissions.Full,
            ),
            TogetherParticipant(
                id = selfParticipantId,
                name = "This device",
                permissions = permissions,
            ),
        )

    private fun TogetherGuestPermissions.toRoomSettings() =
        TogetherRoomSettings(
            allowGuestsToAddTracks = canEditQueue,
            allowGuestsToControlPlayback = canControlPlayback || canChangeSong || canSeek,
            requireHostApprovalToJoin = false,
        )

    private fun encodeTrack(track: TogetherTrack): JsonObject =
        buildJsonObject {
            put("id", track.id)
            put("title", track.title)
            put(
                "artists",
                buildJsonArray {
                    track.artists.forEach { artist ->
                        add(
                            buildJsonObject {
                                put("id", "")
                                put("name", artist)
                            },
                        )
                    }
                },
            )
            put("albumId", "")
            put("albumTitle", "")
            put("artworkUri", track.thumbnailUrl.orEmpty())
            put("durationHintMs", if (track.durationSec >= 0) JsonPrimitive(track.durationSec * 1000L) else JsonPrimitive(0L))
            put("explicit", false)
            put("isMusicVideo", false)
        }

    private fun decodeTrack(element: kotlinx.serialization.json.JsonElement): TogetherTrack? {
        val json = runCatching { element.jsonObject }.getOrNull() ?: return null
        val id = json.string("id")?.takeIf(String::isNotBlank) ?: return null
        val title = json.string("title").orEmpty()
        val artists =
            json["artists"]
                ?.jsonArrayOrNull()
                ?.mapNotNull { artist ->
                    runCatching { artist.jsonObject.string("name") }.getOrNull()?.takeIf(String::isNotBlank)
                }.orEmpty()
        val durationHintMs = json["durationHintMs"]?.jsonPrimitive?.longOrNull ?: -1L
        return TogetherTrack(
            id = id,
            title = title,
            artists = artists,
            durationSec = if (durationHintMs >= 0L) (durationHintMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else -1,
            thumbnailUrl = json.string("artworkUri")?.takeIf(String::isNotBlank),
        )
    }

    private fun repeatMode(value: String?, fallback: Int = 0): Int =
        when (value?.uppercase()) {
            "OFF" -> 0
            "ONE" -> 1
            "ALL" -> 2
            else -> fallback
        }

    private fun repeatModeName(value: Int): String =
        when (value) {
            1 -> "ONE"
            2 -> "ALL"
            else -> "OFF"
        }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.boolean(name: String, fallback: Boolean): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull ?: fallback

    private fun JsonObject.int(name: String, fallback: Int): Int = this[name]?.jsonPrimitive?.intOrNull ?: fallback

    private fun JsonObject.long(name: String, fallback: Long): Long = this[name]?.jsonPrimitive?.longOrNull ?: fallback

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

    const val DESKTOP_HOST_ID = "desktop-host"
}

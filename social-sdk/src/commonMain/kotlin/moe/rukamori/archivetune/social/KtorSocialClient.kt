/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.social.SocialWireJson.nullableString
import moe.rukamori.archivetune.social.SocialWireJson.requiredObject
import moe.rukamori.archivetune.social.SocialWireJson.requiredString

/**
 * Multiplatform Ktor implementation of [SocialApi].
 *
 * Caller retains ownership of [httpClient] and is responsible for closing it.
 */
class KtorSocialClient(
    private val httpClient: HttpClient,
    override val baseUrl: String = DEFAULT_SOCIAL_BASE_URL,
) : SocialApi {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    override suspend fun exchangeAuthCode(code: String, nonce: String): StoredSocialSession {
        val obj = requestJson(
            method = HttpMethod.Post,
            path = "/v1/auth/exchange",
            body = buildJsonObject {
                put("code", code)
                put("nonce", nonce)
            },
        )
        return SocialWireJson.parseSession(obj)
    }

    override suspend fun me(token: String): SocialUser =
        SocialWireJson.parseUser(requestJson(HttpMethod.Get, "/v1/me", token).requiredObject("user"))

    override suspend fun friends(token: String): List<SocialUser> =
        (requestJson(HttpMethod.Get, "/v1/friends", token)["friends"] as? JsonArray)
            .orEmpty()
            .take(500)
            .mapNotNull { (it as? JsonObject)?.let(SocialWireJson::parseUser) }

    override suspend fun presence(token: String): Map<String, SocialPresence> {
        val array = requestJson(HttpMethod.Get, "/v1/friends/presence", token)["friends"] as? JsonArray
            ?: return emptyMap()
        return buildMap {
            array.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val userId = obj.requiredString("userId")
                put(userId, SocialWireJson.parsePresence(obj.requiredObject("presence")))
            }
        }
    }

    override suspend fun requests(token: String): Pair<List<SocialFriendRequest>, List<SocialFriendRequest>> {
        val obj = requestJson(HttpMethod.Get, "/v1/friend-requests", token)
        val incoming = (obj["incoming"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(SocialWireJson::parseFriendRequest) }
        val outgoing = (obj["outgoing"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(SocialWireJson::parseFriendRequest) }
        return incoming to outgoing
    }

    override suspend fun search(token: String, query: String): List<SocialUser> {
        val encoded = query.encodeURLQueryComponent()
        val obj = requestJson(HttpMethod.Get, "/v1/users/search?q=$encoded", token)
        return (obj["users"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(SocialWireJson::parseUser) }
    }

    override suspend fun sendFriendRequest(token: String, userId: String) {
        requestText(HttpMethod.Post, "/v1/friend-requests/${userId.encodeURLPathPart()}", token)
    }

    override suspend fun acceptFriendRequest(token: String, userId: String) {
        requestText(HttpMethod.Post, "/v1/friend-requests/${userId.encodeURLPathPart()}/accept", token)
    }

    override suspend fun declineFriendRequest(token: String, userId: String) {
        requestText(HttpMethod.Delete, "/v1/friend-requests/${userId.encodeURLPathPart()}", token)
    }

    override suspend fun removeFriend(token: String, userId: String) {
        requestText(HttpMethod.Delete, "/v1/friends/${userId.encodeURLPathPart()}", token)
    }

    override suspend fun block(token: String, userId: String) {
        requestText(HttpMethod.Post, "/v1/blocks/${userId.encodeURLPathPart()}", token)
    }

    override suspend fun sendPartyInvite(token: String, userId: String, partyUrl: String) {
        requestText(
            method = HttpMethod.Post,
            path = "/v1/party-invites/${userId.encodeURLPathPart()}",
            token = token,
            body = buildJsonObject { put("partyUrl", partyUrl) },
        )
    }

    override suspend fun chatMessages(token: String, userId: String): List<SocialChatMessage> {
        val obj = requestJson(HttpMethod.Get, "/v1/friends/${userId.encodeURLPathPart()}/messages", token)
        return (obj["messages"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(SocialWireJson::parseChatMessage) }
    }

    override suspend fun sendChatMessage(
        token: String,
        userId: String,
        message: SocialOutgoingMessage,
    ): SocialChatMessage {
        val (kind, payload) = SocialWireJson.encodeOutgoingMessage(message)
        val response = requestJson(
            method = HttpMethod.Post,
            path = "/v1/friends/${userId.encodeURLPathPart()}/messages",
            token = token,
            body = buildJsonObject {
                put("kind", kind)
                put("payload", payload)
            },
        )
        return SocialWireJson.parseChatMessage(response.requiredObject("message"))
    }

    override suspend fun uploadStatistics(token: String, snapshot: SocialStatisticsSnapshot) {
        requestText(
            method = HttpMethod.Put,
            path = "/v1/statistics",
            token = token,
            body = SocialWireJson.encodeStatisticsSnapshot(snapshot),
        )
    }

    override suspend fun friendStatistics(token: String, userId: String): SocialStatisticsSnapshot {
        val response = requestJson(HttpMethod.Get, "/v1/friends/${userId.encodeURLPathPart()}/statistics", token)
        return SocialWireJson.parseStatisticsSnapshot(response.requiredObject("statistics"))
    }

    override suspend fun logout(token: String) {
        requestText(HttpMethod.Post, "/v1/auth/logout", token)
    }

    override fun discordLoginUrl(port: Int, nonce: String): String =
        URLBuilder("$normalizedBaseUrl/auth/discord/start").apply {
            parameters.append("port", port.toString())
            parameters.append("nonce", nonce)
        }.buildString()

    override fun liveUrl(deviceId: String): String {
        val wsBase = when {
            normalizedBaseUrl.startsWith("https://") -> "wss://" + normalizedBaseUrl.removePrefix("https://")
            normalizedBaseUrl.startsWith("http://") -> "ws://" + normalizedBaseUrl.removePrefix("http://")
            else -> normalizedBaseUrl
        }
        return URLBuilder("$wsBase/v1/live").apply {
            parameters.append("device", deviceId)
        }.buildString()
    }

    private suspend fun requestJson(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: JsonObject? = null,
    ): JsonObject = SocialWireJson.parseObject(requestText(method, path, token, body))

    private suspend fun requestText(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: JsonObject? = null,
    ): String {
        val response = httpClient.request("$normalizedBaseUrl$path") {
            this.method = method
            accept(ContentType.Application.Json)
            token?.let { bearerAuth(it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val text = response.bodyAsText()
        val status = response.status.value
        if (status !in 200..299) {
            val message = runCatching { SocialWireJson.parseObject(text).nullableString("error") }
                .getOrNull()
                ?: "ArchiveTune social request failed ($status)"
            throw SocialApiException(status, message)
        }
        return text
    }
}

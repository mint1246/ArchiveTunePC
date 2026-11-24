/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.social.SocialSessionStore
import moe.rukamori.archivetune.social.StoredSocialSession

/** Keychain-backed persistent session store for ArchiveTune Friends on iOS. */
internal class IosSocialSessionStore : SocialSessionStore {
    override suspend fun read(): StoredSocialSession? = withContext(Dispatchers.Default) {
        val raw = IosKeychain.read(KEY) ?: return@withContext null
        try {
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return@withContext null
            val token = (obj["token"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@withContext null
            val expiresAt = (obj["expiresAt"] as? JsonPrimitive)?.longOrNull ?: return@withContext null
            StoredSocialSession(token = token, expiresAt = expiresAt)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun write(session: StoredSocialSession) = withContext(Dispatchers.Default) {
        val payload = buildJsonObject {
            put("token", session.token)
            put("expiresAt", session.expiresAt)
        }.toString()
        IosKeychain.write(KEY, payload)
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.Default) {
        IosKeychain.delete(KEY)
    }

    private companion object {
        const val KEY = "discord_social_session_v1"
    }
}

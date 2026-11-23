/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import io.ktor.http.Url
import io.ktor.http.encodeURLQueryComponent
import kotlinx.coroutines.awaitCancellation

/** Process-owned in-memory [SocialSessionStore] (credentials never enter disk metadata stores). */
class InMemorySocialSessionStore : SocialSessionStore {
    private val lock = SocialSessionLock()
    private var current: StoredSocialSession? = null

    override suspend fun read(): StoredSocialSession? = lock.withLock { current }

    override suspend fun write(session: StoredSocialSession) {
        lock.withLock { current = session }
    }

    override suspend fun clear() {
        lock.withLock { current = null }
    }
}

/** No-op realtime transport for REST-only social sessions (e.g. lightweight iOS Friends tab). */
object NoOpSocialRealtimeTransport : SocialRealtimeTransport {
    override suspend fun connect(
        token: String,
        deviceId: String,
        onConnected: suspend () -> Unit,
        onMessage: (String) -> Unit,
    ) {
        awaitCancellation()
    }

    override suspend fun publishPresence(presence: SocialPresence) = Unit

    override fun disconnect() = Unit

    override fun close() = Unit
}

/**
 * Pure-KMP canonicalizer matching `TogetherQuickTunnelInvite.decode(raw)?.inviteUrl`:
 * requires `https` or `wss`, `*.trycloudflare.com` (excluding `api.trycloudflare.com`),
 * path `/party`, and a `token` longer than 10 characters.
 */
internal fun canonicalizeQuickTunnelInviteUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
    if (url.user != null || url.password != null) return null
    val scheme = url.protocol.name.lowercase()
    if (scheme != "https" && scheme != "wss") return null
    val host = url.host.trim().lowercase()
    if (host.length <= ".trycloudflare.com".length ||
        host.startsWith('.') ||
        host.contains("..") ||
        !host.endsWith(".trycloudflare.com") ||
        host == "api.trycloudflare.com"
    ) {
        return null
    }
    if (url.encodedPath != "/party") return null
    val key = url.parameters["token"]?.trim().orEmpty()
    if (key.length <= 10) return null
    val explicitPort = url.specifiedPort.takeIf { it > 0 && it != 443 }
    val portSuffix = if (explicitPort != null) ":$explicitPort" else ""
    return "https://$host$portSuffix/party?token=${key.encodeURLQueryComponent()}"
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class TogetherQuickTunnelJoinInfo(
    val inviteUrl: String,
    val wsUrl: String,
    val sessionId: String,
    val sessionKey: String,
)

object TogetherQuickTunnelInvite {
    private val secureRandom = SecureRandom()

    /** Same 24-byte URL-safe token shape used by desktop Play Together. */
    fun randomSessionKey(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun encode(
        publicBaseUrl: String,
        sessionKey: String,
    ): String {
        require(sessionKey.length > 10) { "Together session key is invalid" }
        val base = URI(publicBaseUrl.trim())
        require(base.scheme.equals("https", ignoreCase = true)) { "Quick Tunnel must use HTTPS" }
        require(isQuickTunnelHost(base.host)) { "Invalid Cloudflare Quick Tunnel host" }

        val charset = StandardCharsets.UTF_8.name()
        val query = "token=${URLEncoder.encode(sessionKey, charset)}"
        return URI("https", null, base.host, normalizedPort(base.port), "/party", query, null).toASCIIString()
    }

    fun decode(raw: String): TogetherQuickTunnelJoinInfo? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "wss") return null
        if (!isQuickTunnelHost(uri.host) || uri.rawPath != "/party") return null

        val query = parseQuery(uri.rawQuery)
        val key = query["token"]?.trim().orEmpty()
        if (key.length <= 10) return null
        val sessionId = query["sid"]?.trim()?.takeIf(String::isNotBlank) ?: sessionIdForKey(key)

        val port = normalizedPort(uri.port)
        val canonicalQuery = "token=${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}"
        val ws = URI("wss", null, uri.host, port, "/party", canonicalQuery, null).toASCIIString()
        val https = URI("https", null, uri.host, port, "/party", canonicalQuery, null).toASCIIString()
        return TogetherQuickTunnelJoinInfo(
            inviteUrl = https,
            wsUrl = ws,
            sessionId = sessionId,
            sessionKey = key,
        )
    }

    private fun isQuickTunnelHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase().orEmpty()
        return normalized.isNotBlank() &&
            normalized.endsWith(".trycloudflare.com") &&
            normalized != "api.trycloudflare.com"
    }

    internal fun sessionIdForKey(sessionKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionKey.toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun normalizedPort(port: Int): Int = if (port == 443) -1 else port

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val charset = StandardCharsets.UTF_8.name()
        return rawQuery
            .split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = URLDecoder.decode(pair.substring(0, separator), charset)
                val value = URLDecoder.decode(pair.substring(separator + 1), charset)
                name to value
            }.toMap()
    }
}

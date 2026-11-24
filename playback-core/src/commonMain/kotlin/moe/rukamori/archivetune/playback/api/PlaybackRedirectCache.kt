/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import moe.rukamori.archivetune.playback.internal.PlaybackLock
import moe.rukamori.archivetune.playback.internal.platformCurrentTimeMillis

/**
 * Bounded, in-memory cache of validated same-family HTTPS redirect targets for media URLs.
 *
 * Cold or niche streams on CDN edge nodes (e.g. `rr3---sn-*.googlevideo.com`) often respond with
 * HTTP 302 to a serving node (`rr1---sn-*.googlevideo.com`). Caching the post-redirect URL avoids
 * paying an extra TLS handshake and redirect round-trip on subsequent range reads, seeks, cache
 * fragment boundaries, or crossfade opens.
 *
 * Security invariants:
 * - Only HTTPS -> HTTPS redirects within the same media host family are stored.
 * - Credentials (`user@host`) and control characters are rejected.
 * - Callers must call [invalidate] and retry the original URL if a cached redirect fails.
 */
class PlaybackRedirectCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = ::platformCurrentTimeMillis,
) {
    private data class Entry(
        val targetUrl: String,
        val expiresAtMs: Long,
    )

    private val lock = PlaybackLock()
    private val entries = LinkedHashMap<String, Entry>()

    /** Returns the cached redirected URL if present and fresh, otherwise [originalUrl]. */
    fun resolve(originalUrl: String): String =
        lock.withLock {
            val entry = entries[originalUrl] ?: return@withLock originalUrl
            if (entry.expiresAtMs <= nowMs()) {
                entries.remove(originalUrl)
                return@withLock originalUrl
            }
            // Refresh LRU order.
            entries.remove(originalUrl)
            entries[originalUrl] = entry
            entry.targetUrl
        }

    /** Records [finalUrl] as the redirect target for [originalUrl] when it is safe to reuse. */
    fun record(originalUrl: String, finalUrl: String): Boolean {
        if (!isSafeMediaRedirect(originalUrl, finalUrl)) return false
        val now = nowMs()
        val urlExpiryMs = extractStreamUrlExpiryEpochMs(finalUrl)
        val ttlExpiryMs = now + ttlMs.coerceAtLeast(1_000L)
        val expiresAtMs =
            if (urlExpiryMs != null) {
                minOf(ttlExpiryMs, (urlExpiryMs - EXPIRY_SAFETY_MS).coerceAtLeast(now))
            } else {
                ttlExpiryMs
            }
        if (expiresAtMs <= now) return false
        lock.withLock {
            entries.remove(originalUrl)
            entries[originalUrl] = Entry(finalUrl, expiresAtMs)
            evictLocked(now)
        }
        return true
    }

    /** Removes any cached redirect for [originalUrl] (call on HTTP 4xx/5xx or transport failure). */
    fun invalidate(originalUrl: String) {
        lock.withLock {
            entries.remove(originalUrl)
        }
    }

    /** Clears all cached redirect mappings. */
    fun clear() {
        lock.withLock {
            entries.clear()
        }
    }

    private fun evictLocked(now: Long) {
        val expired = entries.entries.filter { it.value.expiresAtMs <= now }.map { it.key }
        expired.forEach(entries::remove)
        val cap = maxEntries.coerceAtLeast(1)
        while (entries.size > cap) {
            val oldestKey = entries.entries.firstOrNull()?.key ?: break
            entries.remove(oldestKey)
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 64
        const val DEFAULT_TTL_MS: Long = 20L * 60L * 1_000L
        private const val EXPIRY_SAFETY_MS: Long = 30_000L

        private val SAFE_MEDIA_HOST_FAMILIES = listOf(
            "googlevideo.com",
            "googleusercontent.com",
        )

        /**
         * Returns true when [finalUrl] is a distinct, valid HTTPS URL in the same host family as
         * [originalUrl] (or exact same host) with no embedded credentials or control characters.
         */
        fun isSafeMediaRedirect(originalUrl: String, finalUrl: String): Boolean {
            if (originalUrl == finalUrl) return false
            val originalHost = extractSafeHttpsHost(originalUrl) ?: return false
            val finalHost = extractSafeHttpsHost(finalUrl) ?: return false
            if (originalHost == finalHost) return true
            return SAFE_MEDIA_HOST_FAMILIES.any { suffix ->
                hostMatchesSuffix(originalHost, suffix) && hostMatchesSuffix(finalHost, suffix)
            }
        }

        internal fun extractSafeHttpsHost(rawUrl: String): String? {
            if (rawUrl.length < 10 || rawUrl.any { it.isWhitespace() || it.code < 32 }) return null
            if (!rawUrl.startsWith("https://", ignoreCase = true)) return null
            val authority = rawUrl.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isBlank() || '@' in authority) return null
            val host = if (authority.startsWith("[")) {
                authority.substringAfter('[').substringBefore(']')
            } else {
                authority.substringBefore(':')
            }
            return host.lowercase().takeIf(String::isNotBlank)
        }

        private fun hostMatchesSuffix(host: String, suffix: String): Boolean =
            host == suffix || host.endsWith(".$suffix")
    }
}

/**
 * Extracts an epoch-millisecond expiry timestamp from a YouTube/CDN signed URL (`expire=` query
 * parameter or `/expire/<epoch>/` HLS path segment), or null if absent.
 */
fun extractStreamUrlExpiryEpochMs(url: String): Long? {
    val queryIdx = url.indexOf('?')
    if (queryIdx >= 0 && queryIdx + 1 < url.length) {
        val query = url.substring(queryIdx + 1).substringBefore('#')
        for (part in query.split('&')) {
            if (part.startsWith("expire=")) {
                val seconds = part.substring(7).toLongOrNull()
                if (seconds != null && seconds > 1_000_000_000L) return seconds * 1_000L
            }
        }
    }
    val pathMarker = "/expire/"
    val pathIdx = url.indexOf(pathMarker)
    if (pathIdx >= 0) {
        val tail = url.substring(pathIdx + pathMarker.length)
        val seconds = tail.substringBefore('/').substringBefore('?').toLongOrNull()
        if (seconds != null && seconds > 1_000_000_000L) return seconds * 1_000L
    }
    return null
}

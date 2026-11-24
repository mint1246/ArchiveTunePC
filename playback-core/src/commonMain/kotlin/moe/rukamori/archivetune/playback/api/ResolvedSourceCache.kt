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
 * Bounded, strictly in-memory TTL cache for [ResolvedPlaybackSource] instances.
 *
 * Signed media URLs are never written to disk and are automatically evicted before their
 * expiration window ([expirySafetyMs]). Shared across Android, iOS, and Desktop SDK consumers
 * so next-track pre-resolution, repeat, and skip-back avoid redundant resolver round-trips.
 */
class ResolvedSourceCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
    private val expirySafetyMs: Long = DEFAULT_EXPIRY_SAFETY_MS,
    private val nowMs: () -> Long = ::platformCurrentTimeMillis,
) {
    private data class Entry(
        val source: ResolvedPlaybackSource,
        val expiresAtMs: Long,
    )

    private val lock = PlaybackLock()
    private val entries = LinkedHashMap<String, Entry>()

    /** Returns a fresh cached source for [key], or null if missing/expired. */
    fun get(key: String): ResolvedPlaybackSource? =
        lock.withLock {
            val entry = entries[key] ?: return@withLock null
            val now = nowMs()
            if (!isFresh(entry, now)) {
                entries.remove(key)
                return@withLock null
            }
            entries.remove(key)
            entries[key] = entry
            entry.source
        }

    /** Stores [source] under [key] if it has sufficient remaining lifetime. */
    fun put(key: String, source: ResolvedPlaybackSource): Boolean {
        val now = nowMs()
        val inferredExpiry =
            source.expiresAtEpochMs
                ?: extractStreamUrlExpiryEpochMs(source.uri)
                ?: (now + defaultTtlMs.coerceAtLeast(1_000L))
        val normalizedSource =
            if (source.expiresAtEpochMs == null) {
                source.copy(expiresAtEpochMs = inferredExpiry)
            } else {
                source
            }
        val entry = Entry(normalizedSource, inferredExpiry)
        if (!isFresh(entry, now)) return false
        lock.withLock {
            entries.remove(key)
            entries[key] = entry
            evictLocked(now)
        }
        return true
    }

    /** Removes one cache entry by exact [key]. */
    fun invalidate(key: String) {
        lock.withLock {
            entries.remove(key)
        }
    }

    /** Removes all entries whose key starts with [prefix] (e.g. a track ID). */
    fun invalidatePrefix(prefix: String) {
        lock.withLock {
            val matching = entries.keys.filter { it.startsWith(prefix) }
            matching.forEach(entries::remove)
        }
    }

    /** Removes all entries whose resolved URI matches [uri]. */
    fun invalidateUri(uri: String) {
        lock.withLock {
            val matching = entries.entries.filter { it.value.source.uri == uri }.map { it.key }
            matching.forEach(entries::remove)
        }
    }

    /** Clears the entire cache (call on auth/quality/locale change or shutdown). */
    fun clear() {
        lock.withLock {
            entries.clear()
        }
    }

    private fun isFresh(entry: Entry, now: Long): Boolean =
        entry.expiresAtMs > now + expirySafetyMs

    private fun evictLocked(now: Long) {
        val expired = entries.entries.filterNot { isFresh(it.value, now) }.map { it.key }
        expired.forEach(entries::remove)
        val cap = maxEntries.coerceAtLeast(1)
        while (entries.size > cap) {
            val oldestKey = entries.entries.firstOrNull()?.key ?: break
            entries.remove(oldestKey)
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 64
        const val DEFAULT_TTL_MS: Long = 5L * 60L * 1_000L
        const val DEFAULT_EXPIRY_SAFETY_MS: Long = 60_000L
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLowLatencyPrimitivesTest {
    @Test
    fun startupContentionGateUnblocksOnReadyAndHonorsTimeout() = runTest {
        val gate = PlaybackStartupContentionGate(defaultMaxWaitMs = 2_000L)
        assertTrue(gate.awaitStartupClear())

        val token = gate.beginStartup()
        assertTrue(gate.isStartingUp.value)

        val waiter = async { gate.awaitStartupClear(maxWaitMs = 2_000L) }
        runCurrent()
        assertFalse(waiter.isCompleted)

        // Stale token must not prematurely release the gate.
        gate.markReady(token - 1)
        runCurrent()
        assertFalse(waiter.isCompleted)

        gate.markReady(token)
        runCurrent()
        assertTrue(waiter.isCompleted)
        assertTrue(waiter.await())

        // Timeout fallback guarantees background work never hangs indefinitely.
        gate.beginStartup()
        val timedOut = async { gate.awaitStartupClear(maxWaitMs = 1_500L) }
        advanceTimeBy(1_501L)
        runCurrent()
        assertTrue(timedOut.isCompleted)
        assertFalse(timedOut.await())
    }

    @Test
    fun startupContentionGateTracksPlaybackSnapshots() {
        val gate = PlaybackStartupContentionGate()
        gate.observeSnapshot(PlaybackSnapshot(status = PlaybackStatus.RESOLVING, playWhenReady = true))
        assertTrue(gate.isStartingUp.value)

        gate.observeSnapshot(PlaybackSnapshot(status = PlaybackStatus.LOADING, playWhenReady = true))
        assertTrue(gate.isStartingUp.value)

        gate.observeSnapshot(PlaybackSnapshot(status = PlaybackStatus.READY, playWhenReady = true, positionMs = 0L))
        assertFalse(gate.isStartingUp.value)

        // Mid-track rebuffer should not re-engage initial startup gating.
        gate.observeSnapshot(PlaybackSnapshot(status = PlaybackStatus.BUFFERING, playWhenReady = true, positionMs = 12_000L))
        assertFalse(gate.isStartingUp.value)
    }

    @Test
    fun redirectCacheAcceptsSameFamilyHttpsAndEvictsExpiredOrInvalidated() {
        var now = 1_700_000_000_000L
        val cache = PlaybackRedirectCache(maxEntries = 2, ttlMs = 60_000L, nowMs = { now })

        val origin = "https://rr3---sn-abc.googlevideo.com/videoplayback?id=1&expire=1700003600"
        val edge = "https://rr1---sn-xyz.googlevideo.com/videoplayback?id=1&expire=1700003600"

        assertTrue(cache.record(origin, edge))
        assertEquals(edge, cache.resolve(origin))

        // Reject cross-family, non-HTTPS, or credentialed redirects.
        assertFalse(cache.record(origin, "https://evil.example.com/videoplayback"))
        assertFalse(cache.record(origin, "http://rr1---sn-xyz.googlevideo.com/videoplayback"))
        assertFalse(cache.record(origin, "https://user:pass@rr1---sn-xyz.googlevideo.com/videoplayback"))

        // Explicit invalidation restores the original URL.
        cache.invalidate(origin)
        assertEquals(origin, cache.resolve(origin))

        // TTL expiry restores the original URL.
        assertTrue(cache.record(origin, edge))
        now += 61_000L
        assertEquals(origin, cache.resolve(origin))
    }

    @Test
    fun resolvedSourceCacheHonorsUrlExpirySafetyMarginAndInvalidation() {
        var now = 1_700_000_000_000L
        val cache = ResolvedSourceCache(maxEntries = 4, expirySafetyMs = 60_000L, nowMs = { now })

        val hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1700000300/id/abc/index.m3u8"
        val source = ResolvedPlaybackSource(uri = hlsUrl, mimeType = "application/vnd.apple.mpegurl")

        assertTrue(cache.put("abc|HIGH", source))
        assertEquals(hlsUrl, cache.get("abc|HIGH")?.uri)
        assertEquals(1_700_000_300_000L, cache.get("abc|HIGH")?.expiresAtEpochMs)

        // Within 60s of expiry -> treated as stale.
        now = 1_700_000_245_000L
        assertNull(cache.get("abc|HIGH"))

        // Prefix invalidation clears all quality variants of a track.
        now = 1_700_000_000_000L
        assertTrue(cache.put("track1|HIGH", source))
        assertTrue(cache.put("track1|LOW", source))
        assertTrue(cache.put("track2|HIGH", source))
        cache.invalidatePrefix("track1|")
        assertNull(cache.get("track1|HIGH"))
        assertNull(cache.get("track1|LOW"))
        assertEquals(hlsUrl, cache.get("track2|HIGH")?.uri)
    }
}

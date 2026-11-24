/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import java.io.IOException
import moe.rukamori.archivetune.playback.api.PlaybackRedirectCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectCachingDataSourceTest {
    @Test
    fun recordsSafeRedirectAndReusesItOnSubsequentOpensWithFallbackOnFailure() {
        val origin = "https://rr3---sn-origin.googlevideo.com/videoplayback?id=1"
        val edge = "https://rr1---sn-edge.googlevideo.com/videoplayback?id=1"
        var failEdge = false
        val openedUrls = mutableListOf<String>()
        var closeCount = 0

        val cache = PlaybackRedirectCache()
        fun openOnce(): RedirectOpenOutcome =
            openWithRedirectCache(
                originalUrl = origin,
                redirectCache = cache,
                openUrl = { requested ->
                    openedUrls += requested
                    if (requested == edge && failEdge) {
                        throw IOException("stale edge redirect")
                    }
                    val finalUrl = if (requested == origin) edge else requested
                    1024L to finalUrl
                },
                closeBeforeFallback = { closeCount++ },
            )

        // First open hits origin, learns edge redirect.
        val first = openOnce()
        assertEquals(1024L, first.length)
        assertFalse(first.usedCachedRedirect)

        // Second open bypasses origin and goes straight to edge.
        val second = openOnce()
        assertEquals(1024L, second.length)
        assertTrue(second.usedCachedRedirect)
        assertEquals(listOf(origin, edge), openedUrls)

        // If edge fails later, cache closes, invalidates, and falls back seamlessly to origin.
        failEdge = true
        val third = openOnce()
        assertEquals(1024L, third.length)
        assertFalse(third.usedCachedRedirect)
        assertEquals(1, closeCount)
        assertEquals(listOf(origin, edge, edge, origin), openedUrls)
    }
}

package moe.rukamori.archivetune.playback.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDataSourcesTest {
    @Test
    fun localAndInvalidatedRequestsBypassBothCachesAndMetadataLookup() {
        val skipCache: (String) -> Boolean = { it == "invalidated" }
        val unexpected: (String) -> Nothing = { error("Consulted the cache for $it") }

        for (scheme in listOf("content", "FILE", "android.resource")) {
            assertEquals(PlaybackRoute.DIRECT, playbackRoute(scheme, "local", skipCache, unexpected, unexpected))
        }
        assertEquals(PlaybackRoute.DIRECT, playbackRoute(null, "invalidated", skipCache, unexpected, unexpected))
    }

    @Test
    fun aDownloadWinsOnlyWhenTheEntireResourceIsAvailable() {
        val calls = mutableListOf<String>()
        val inspect: (String) -> Unit = { calls.add("metadata:$it") }
        val downloaded: (String) -> Boolean = {
            calls.add("download:$it")
            it == "complete"
        }

        assertEquals(PlaybackRoute.DOWNLOAD, playbackRoute(null, "complete", { false }, inspect, downloaded))
        assertEquals(PlaybackRoute.STREAM, playbackRoute(null, "partial", { false }, inspect, downloaded))
        assertEquals(PlaybackRoute.STREAM, playbackRoute(null, null, { false }, inspect, downloaded))
        assertEquals(listOf("metadata:complete", "download:complete", "metadata:partial", "download:partial"), calls)
    }

    @Test
    fun directUrlsAndLocalUrisNeverGoBackThroughDeferredResolution() {
        for (scheme in listOf("https", "HTTP", "content", "file", "android.resource")) {
            assertFalse(isDeferredPlaybackScheme(scheme))
        }
        assertTrue(isDeferredPlaybackScheme(null))
    }
}

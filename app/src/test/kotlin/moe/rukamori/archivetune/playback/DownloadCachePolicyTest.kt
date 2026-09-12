/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCachePolicyTest {
    @Test
    fun completePlaybackCache_canBeReusedWhenNoDownloadBytesExist() {
        assertTrue(
            shouldReuseCompletePlaybackCache(
                hasPartialDownload = false,
                playbackContentLength = 1_000L,
                fullyCached = true,
            ),
        )
    }

    @Test
    fun partialPlaybackCache_neverBypassesFreshResolution() {
        assertFalse(
            shouldReuseCompletePlaybackCache(
                hasPartialDownload = false,
                playbackContentLength = 1_000L,
                fullyCached = false,
            ),
        )
    }

    @Test
    fun unknownPlaybackLength_neverBypassesFreshResolution() {
        assertFalse(
            shouldReuseCompletePlaybackCache(
                hasPartialDownload = false,
                playbackContentLength = null,
                fullyCached = true,
            ),
        )
    }

    @Test
    fun existingPartialDownload_neverMixesPlaybackCacheRepresentation() {
        assertFalse(
            shouldReuseCompletePlaybackCache(
                hasPartialDownload = true,
                playbackContentLength = 1_000L,
                fullyCached = true,
            ),
        )
    }

    @Test
    fun partialResume_prefersPersistedDownloadMetadataItag() {
        assertTrue(
            resolvePinnedDownloadFormatId(
                hasPartialDownload = true,
                metadataFormatId = 251,
                databaseFormatId = 140,
            ) == 251,
        )
    }

    @Test
    fun partialResume_fallsBackToDatabaseItagAfterPlaybackCacheCopyRestart() {
        assertTrue(
            resolvePinnedDownloadFormatId(
                hasPartialDownload = true,
                metadataFormatId = null,
                databaseFormatId = 251,
            ) == 251,
        )
    }

    @Test
    fun freshDownload_doesNotPinStaleDatabaseFormat() {
        assertFalse(
            resolvePinnedDownloadFormatId(
                hasPartialDownload = false,
                metadataFormatId = null,
                databaseFormatId = 251,
            ) != null,
        )
    }
}

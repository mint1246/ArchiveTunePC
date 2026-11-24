/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.playback.ios

import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IosPlaybackSourceValidationTest {
    @Test
    fun acceptsValidHttpsAndLocalFileSources() {
        assertNull(
            validateIosPlaybackSource(
                ResolvedPlaybackSource(
                    uri = "https://example.com/audio.m4a",
                    mimeType = "audio/mp4",
                ),
            ),
        )
        assertNull(
            validateIosPlaybackSource(
                ResolvedPlaybackSource(
                    uri = "file:///var/mobile/Containers/Data/audio.m4a",
                    mimeType = "audio/m4a",
                ),
            ),
        )
        assertNull(
            validateIosPlaybackSource(
                ResolvedPlaybackSource(
                    uri = "file:///var/mobile/Documents/song.mp3",
                    mimeType = "audio/mpeg",
                ),
            ),
        )
    }

    @Test
    fun rejectsInsecureHttpAndPathTraversal() {
        assertNotNull(
            validateIosPlaybackSource(
                ResolvedPlaybackSource(
                    uri = "http://example.com/audio.m4a",
                    mimeType = "audio/mp4",
                ),
            ),
        )
        assertNotNull(
            validateIosPlaybackSource(
                ResolvedPlaybackSource(
                    uri = "file:///var/mobile/../etc/passwd",
                    mimeType = "audio/mp4",
                ),
            ),
        )
        assertNotNull(
            validateIosPlaybackSource(
                ResolvedPlaybackSource(
                    uri = "https://example.com/video.webm",
                    mimeType = "audio/webm",
                ),
            ),
        )
    }
}

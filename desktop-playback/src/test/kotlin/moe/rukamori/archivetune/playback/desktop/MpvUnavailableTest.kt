/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackFailure
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MpvUnavailableTest {
    @Test
    fun `missing runtime becomes a recoverable player error`() = runBlocking {
        val audioFile = createTempFile(prefix = "archivetune-playback-", suffix = ".audio")
        val controller =
            MpvPlaybackController(
                sourceResolver = DesktopConcretePlaybackSourceResolver,
                configuration =
                    MpvConfiguration(
                        executable = Path.of("definitely-missing", "archivetune-mpv-test.exe").toString(),
                        startupTimeoutMs = 50L,
                    ),
            )
        try {
            controller.replaceQueue(
                items =
                    listOf(
                        PlaybackItem(
                            metadata = PlaybackMetadata(id = "local-test", title = "Local test"),
                            source = PlaybackSource.LocalFile(audioFile.toString()),
                        ),
                    ),
            )

            val failed = withTimeout(5_000) { controller.state.first { it.status == PlaybackStatus.ERROR } }
            assertEquals(PlaybackFailure.Kind.ENGINE_UNAVAILABLE, failed.error?.kind)
            assertFalse(failed.isPlaying)
        } finally {
            controller.close()
            java.nio.file.Files.deleteIfExists(audioFile)
        }
    }
}

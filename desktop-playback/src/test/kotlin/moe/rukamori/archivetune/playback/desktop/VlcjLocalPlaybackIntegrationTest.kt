/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the real in-process backend when a local libVLC runtime is available.
 *
 * The test is intentionally runtime-gated so normal unit-test environments do not need VLC
 * installed. Set ARCHIVETUNE_VLC_DIR or keep the verified spike runtime under
 * %LOCALAPPDATA%/ArchiveTuneSpikes/runtime to run it locally.
 */
class VlcjLocalPlaybackIntegrationTest {
    @Test
    fun `plays and controls a local wav through libVLC`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val wave = createWaveFile()
        val item = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "vlcj-local-integration",
                title = "VLCJ local integration",
            ),
            source = PlaybackSource.LocalFile(wave.toString()),
        )
        val resolver = PlaybackSourceResolver { queued ->
            DesktopConcretePlaybackSourceResolver.resolve(queued)
        }
        val controller = VlcjPlaybackController(
            sourceResolver = resolver,
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )

        try {
            controller.replaceQueue(listOf(item))
            awaitState(controller, "initial playback") { it.status == PlaybackStatus.READY && it.durationMs > 0L }
            assertTrue(controller.state.value.durationMs >= 1_000L)

            controller.pause()
            awaitState(controller, "pause") { !it.playWhenReady && it.status == PlaybackStatus.READY }

            controller.play()
            awaitState(controller, "resume") { it.isPlaying }

            controller.seekTo(500L)
            awaitState(controller, "seek") { it.positionMs in 350L..900L }

            controller.setSpeed(1.5f)
            controller.setVolume(0.35f)
            assertEquals(1.5f, controller.state.value.speed)
            awaitState(controller, "volume") { it.volume in 0.34f..0.36f }

            controller.stop()
            awaitState(controller, "stop") { it.status == PlaybackStatus.IDLE && it.positionMs == 0L }
        } finally {
            controller.close()
            Files.deleteIfExists(wave)
        }
    }

    private suspend fun awaitState(
        controller: VlcjPlaybackController,
        label: String,
        predicate: (moe.rukamori.archivetune.playback.api.PlaybackSnapshot) -> Boolean,
    ) {
        try {
            withTimeout(20_000L) {
                controller.state.first(predicate)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $label; state=${controller.state.value}", timeout)
        }
    }

    private fun createWaveFile(): Path {
        val sampleRate = 44_100
        val durationSeconds = 2
        val samples = sampleRate * durationSeconds
        val data = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = (kotlin.math.sin(index * 2.0 * Math.PI * 440.0 / sampleRate) * 8_000.0).toInt()
            data[index * 2] = (value and 0xff).toByte()
            data[index * 2 + 1] = (value shr 8).toByte()
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val path = Files.createTempFile("archivetune-vlcj-", ".wav")
        AudioInputStream(data.inputStream(), format, samples.toLong()).use { input ->
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile())
        }
        return path
    }
}

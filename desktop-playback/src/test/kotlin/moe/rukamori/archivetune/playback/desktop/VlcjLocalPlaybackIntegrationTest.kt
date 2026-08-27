/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sun.jna.Pointer
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
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.callback.AudioCallback

/**
 * Exercises the real in-process backend when a local libVLC runtime is available.
 *
 * The test is intentionally runtime-gated so normal unit-test environments do not need VLC
 * installed. Set ARCHIVETUNE_VLC_DIR or keep the verified spike runtime under
 * %LOCALAPPDATA%/ArchiveTuneSpikes/runtime to run it locally.
 */
class VlcjLocalPlaybackIntegrationTest {
    @Test
    fun `pitch bridge actually shifts decoded audio`() {
        val runtime = VlcRuntimeLocator.find() ?: return
        val wave = createWaveFile(durationSeconds = 2, channels = 1)
        try {
            val base = captureFrequency(runtime, wave, 0f)
            val octaveUp = captureFrequency(runtime, wave, 12f)
            assertTrue(base in 420.0..460.0, "expected ~440 Hz, got $base")
            assertTrue(octaveUp in 840.0..920.0, "expected ~880 Hz after +12 st, got $octaveUp")
        } finally {
            Files.deleteIfExists(wave)
        }
    }

    @Test
    fun `restoring a paused queue does not start libVLC`() = runBlocking {
        val resolutionCalls = AtomicInteger()
        val item = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "paused-restore",
                title = "Paused restore",
                durationHintMs = 10_000L,
            ),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver {
                resolutionCalls.incrementAndGet()
                Result.failure(AssertionError("paused queue restore must not resolve media"))
            },
        )

        try {
            controller.replaceQueue(
                items = listOf(item),
                startPositionMs = 4_321L,
                playWhenReady = false,
            )

            assertEquals(0, resolutionCalls.get())
            assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
            assertEquals(false, controller.state.value.playWhenReady)
            assertEquals(4_321L, controller.state.value.positionMs)
            assertEquals(item, controller.state.value.currentItem)
        } finally {
            controller.close()
        }
    }

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

    @Test
    fun `decodes local media to stereo 44k stem input wav`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val source = createWaveFile()
        val output = Files.createTempFile("archivetune-stem-input-", ".wav")
        Files.deleteIfExists(output)
        val item = PlaybackItem(
            metadata = PlaybackMetadata(id = "stem-decode", title = "Stem decode", durationHintMs = 2_000L),
            source = PlaybackSource.LocalFile(source.toString()),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )

        try {
            controller.decodeStemInput(item, output)
            val format = AudioSystem.getAudioFileFormat(output.toFile()).format
            assertEquals(44_100f, format.sampleRate)
            assertEquals(2, format.channels)
            assertTrue(Files.size(output) > 44L)
        } finally {
            controller.close()
            Files.deleteIfExists(source)
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun `decodes optional external media to stem input wav`() = runBlocking {
        val external = System.getenv("ARCHIVETUNE_STEM_DECODE_FILE")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)
            ?: return@runBlocking
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val output = Files.createTempFile("archivetune-stem-external-", ".wav")
        Files.deleteIfExists(output)
        val item = PlaybackItem(
            metadata = PlaybackMetadata(id = "stem-external", title = external.fileName.toString()),
            source = PlaybackSource.LocalFile(external.toString()),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )

        try {
            controller.decodeStemInput(item, output)
            val format = AudioSystem.getAudioFileFormat(output.toFile()).format
            assertEquals(44_100f, format.sampleRate)
            assertEquals(2, format.channels)
            assertTrue(Files.size(output) > 44L)
        } finally {
            controller.close()
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun `compresses generated stem wav to opus cache`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val source = createWaveFile(durationSeconds = 8, channels = 2)
        val opus = Files.createTempFile("archivetune-stem-cache-", ".opus")
        val decoded = Files.createTempFile("archivetune-stem-cache-decoded-", ".wav")
        Files.deleteIfExists(opus)
        Files.deleteIfExists(decoded)
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )

        try {
            controller.compressStemForCache(source, opus)
            assertTrue(Files.isRegularFile(opus))
            assertTrue(Files.size(opus) < Files.size(source) / 4)
            Files.newInputStream(opus).use { input ->
                val magic = ByteArray(4)
                assertEquals(4, input.read(magic))
                assertTrue(magic.contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())))
            }

            val item = PlaybackItem(
                metadata = PlaybackMetadata(id = "stem-opus-roundtrip", title = "Stem Opus roundtrip"),
                source = PlaybackSource.LocalFile(opus.toString()),
            )
            controller.decodeStemInput(item, decoded)
            val format = AudioSystem.getAudioFileFormat(decoded.toFile()).format
            assertEquals(44_100f, format.sampleRate)
            assertEquals(2, format.channels)
            assertTrue(Files.size(decoded) > 44L)
        } finally {
            controller.close()
            Files.deleteIfExists(source)
            Files.deleteIfExists(opus)
            Files.deleteIfExists(decoded)
        }
    }

    @Test
    fun `commits generated stem set as opus and removes temporary wavs`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val directory = Files.createTempDirectory("archivetune-stem-set-")
        val template = createWaveFile(durationSeconds = 4, channels = 2)
        val names = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
        names.forEach { name -> Files.copy(template, directory.resolve("$name.wav")) }
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )

        try {
            val compressed = controller.compressGeneratedStemSetForCache(directory)
            assertEquals(6, compressed.size)
            names.forEach { name ->
                assertTrue(Files.isRegularFile(directory.resolve("$name.opus")))
                assertTrue(!Files.exists(directory.resolve("$name.wav")))
                assertTrue(!Files.exists(directory.resolve(".$name.opus.part")))
            }
        } finally {
            controller.close()
            Files.deleteIfExists(template)
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
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

    private fun createWaveFile(durationSeconds: Int = 2, channels: Int = 1): Path {
        val sampleRate = 44_100
        val samples = sampleRate * durationSeconds
        val data = ByteArray(samples * channels * 2)
        for (index in 0 until samples) {
            val value = (kotlin.math.sin(index * 2.0 * Math.PI * 440.0 / sampleRate) * 8_000.0).toInt()
            for (channel in 0 until channels) {
                val offset = (index * channels + channel) * 2
                data[offset] = (value and 0xff).toByte()
                data[offset + 1] = (value shr 8).toByte()
            }
        }
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val path = Files.createTempFile("archivetune-vlcj-", ".wav")
        AudioInputStream(data.inputStream(), format, samples.toLong()).use { input ->
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile())
        }
        return path
    }

    private fun captureFrequency(runtime: VlcRuntimeLayout, wave: Path, pitch: Float): Double {
        VlcRuntimeLocator.prepareNative(runtime)
        val factory = MediaPlayerFactory(*runtime.mediaPlayerFactoryOptions())
        val player = factory.mediaPlayers().newMediaPlayer()
        val captured = ArrayList<Short>(60_000)
        val firstAudio = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val collecting = AtomicBoolean(false)
        val callback = object : AudioCallback {
            override fun play(mediaPlayer: MediaPlayer, samples: Pointer, sampleCount: Int, pts: Long) {
                firstAudio.countDown()
                if (!collecting.get()) return
                val block = samples.getShortArray(0, sampleCount)
                synchronized(captured) {
                    if (captured.size < 60_000) captured.addAll(block.asList())
                    if (captured.size >= 50_000) ready.countDown()
                }
            }
            override fun pause(mediaPlayer: MediaPlayer, pts: Long) = Unit
            override fun resume(mediaPlayer: MediaPlayer, pts: Long) = Unit
            override fun flush(mediaPlayer: MediaPlayer, pts: Long) = Unit
            override fun drain(mediaPlayer: MediaPlayer) = Unit
            override fun setVolume(volume: Float, mute: Boolean) = Unit
        }
        try {
            player.audio().callback("S16N", 44_100, 1, callback, true)
            assertTrue(player.media().play(wave.toUri().toASCIIString()))
            assertTrue(firstAudio.await(8, TimeUnit.SECONDS), "timed out waiting for VLC audio output")
            assertTrue(VlcPitchBridge.applyToActiveAudioOutput(player, pitch), "active VLC audio output unavailable")
            player.controls().setTime(0)
            synchronized(captured) { captured.clear() }
            collecting.set(true)
            assertTrue(ready.await(8, TimeUnit.SECONDS), "timed out capturing pitched PCM")
            val samples = synchronized(captured) { captured.toShortArray() }
            val from = 8_000.coerceAtMost(samples.size)
            var crossings = 0
            for (index in from + 1 until samples.size) {
                if (samples[index - 1] <= 0 && samples[index] > 0) crossings++
            }
            val seconds = (samples.size - from) / 44_100.0
            return crossings / seconds
        } finally {
            runCatching { player.controls().stop() }
            runCatching { player.release() }
            runCatching { factory.release() }
        }
    }
}

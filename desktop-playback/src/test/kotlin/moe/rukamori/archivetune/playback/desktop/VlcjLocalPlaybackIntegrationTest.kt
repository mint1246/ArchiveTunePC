/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
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
        // The native pitch bridge relies on a private Windows VLC 3 x64 structure layout.
        // It must never dereference that layout on Linux.
        if (!VlcPitchBridge.isSupportedOnCurrentPlatform()) return
        val runtime = VlcRuntimeLocator.find() ?: return
        val wave = createWaveFile(durationSeconds = 2, channels = 1)
        try {
            val base = captureFrequency(runtime, wave, 0f)
            assertTrue(base in 438.0..442.0, "expected ~440 Hz, got $base")
            listOf(-7f, -1f, 1f, 5f, 7f, 12f).forEach { semitones ->
                val measured = captureFrequency(runtime, wave, semitones)
                val expected = 440.0 * Math.pow(2.0, semitones / 12.0)
                val cents = 1200.0 * (kotlin.math.ln(measured / expected) / kotlin.math.ln(2.0))
                println("pitch $semitones st: measured=$measured expected=$expected errorCents=$cents")
                assertTrue(kotlin.math.abs(cents) < 8.0, "pitch $semitones st is ${cents} cents off: measured=$measured expected=$expected")
            }
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
    fun `crossfade still overlaps when next source resolves slowly`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val secondResolutionCalls = AtomicInteger()
        val firstWave = createWaveFile(durationSeconds = 6)
        val secondWave = createWaveFile(durationSeconds = 6)
        val first = PlaybackItem(
            metadata = PlaybackMetadata(id = "crossfade-slow-first", title = "Crossfade slow first", durationHintMs = 6_000L),
            source = PlaybackSource.LocalFile(firstWave.toString()),
        )
        val second = PlaybackItem(
            metadata = PlaybackMetadata(id = "crossfade-slow-second", title = "Crossfade slow second", durationHintMs = 6_000L),
            source = PlaybackSource.LocalFile(secondWave.toString()),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver { item ->
                if (item === second) {
                    secondResolutionCalls.incrementAndGet()
                    delay(2_500L)
                }
                DesktopConcretePlaybackSourceResolver.resolve(item)
            },
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )
        try {
            controller.setCrossfadeConfiguration(DesktopCrossfadeConfiguration(enabled = true, durationMs = 1_000L, preserveGaplessAlbumTransitions = false))
            controller.replaceQueue(listOf(first, second))
            awaitState(controller, "slow crossfade first playback") { it.currentItem?.metadata?.id == first.metadata.id && it.isPlaying }
            val factoryField = controller.javaClass.getDeclaredField("factory").apply { isAccessible = true }
            val initialFactory = factoryField.get(controller)
            val secondaryField = controller.javaClass.getDeclaredField("secondaryCrossfadePlayer").apply { isAccessible = true }
            val retiredField = controller.javaClass.getDeclaredField("retiredCrossfadePlayers").apply { isAccessible = true }
            var overlapObserved = false
            withTimeout(15_000L) {
                while (controller.state.value.currentItem?.metadata?.id != second.metadata.id) {
                    val secondaryPlayer = secondaryField.get(controller) as? MediaPlayer
                    overlapObserved = overlapObserved || secondaryPlayer != null
                    delay(20L)
                }
            }
            assertTrue(overlapObserved, "crossfade should prepare a slow next source before the fade window")
            assertTrue(factoryField.get(controller) === initialFactory, "crossfade should reuse the existing libVLC instance")
            assertEquals(1, secondResolutionCalls.get(), "promoting the prepared player must not reload the incoming source")
            assertTrue(
                retiredField.get(controller).let { it is Collection<*> && it.isNotEmpty() },
                "crossfade promotion should defer outgoing player teardown",
            )
            val retired = (retiredField.get(controller) as Collection<*>).first()!!
            val retiredPlayer = retired.javaClass
                .getDeclaredField("player")
                .apply { isAccessible = true }
                .get(retired) as MediaPlayer
            assertFalse(
                retiredPlayer.status().isPlaying(),
                "muted outgoing player must be paused before its natural EOF",
            )
            delay(300L)
            assertTrue(controller.state.value.isPlaying, "promoted player should remain audible after handoff")
            delay(900L)
            assertTrue(
                controller.state.value.isPlaying,
                "promoted player must keep playing after the outgoing VLC player is retired",
            )
            val activePlayer = controller.javaClass
                .getDeclaredField("player")
                .apply { isAccessible = true }
                .get(controller) as MediaPlayer
            assertTrue(
                activePlayer.status().isPlaying(),
                "promoted libVLC MediaPlayer itself must still be playing after outgoing retirement",
            )
            withTimeout(2_000L) {
                while (retiredField.get(controller).let { it is Collection<*> && it.isNotEmpty() }) {
                    delay(20L)
                }
            }
        } finally {
            controller.close()
            delay(250L)
            Files.deleteIfExists(firstWave)
            Files.deleteIfExists(secondWave)
        }
    }

    @Test
    fun `crossfade keeps advancing through the queue`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val firstWave = createWaveFile(durationSeconds = 4)
        val secondWave = createWaveFile(durationSeconds = 4)
        val thirdWave = createWaveFile(durationSeconds = 4)
        val first = PlaybackItem(
            metadata = PlaybackMetadata(id = "crossfade-chain-first", title = "Crossfade chain first", durationHintMs = 4_000L),
            source = PlaybackSource.LocalFile(firstWave.toString()),
        )
        val second = PlaybackItem(
            metadata = PlaybackMetadata(id = "crossfade-chain-second", title = "Crossfade chain second", durationHintMs = 4_000L),
            source = PlaybackSource.LocalFile(secondWave.toString()),
        )
        val third = PlaybackItem(
            metadata = PlaybackMetadata(id = "crossfade-chain-third", title = "Crossfade chain third", durationHintMs = 4_000L),
            source = PlaybackSource.LocalFile(thirdWave.toString()),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )
        try {
            controller.setCrossfadeConfiguration(
                DesktopCrossfadeConfiguration(
                    enabled = true,
                    durationMs = 750L,
                    preserveGaplessAlbumTransitions = false,
                ),
            )
            controller.replaceQueue(listOf(first, second, third))
            awaitState(controller, "first crossfade-chain track") {
                it.currentItem?.metadata?.id == first.metadata.id && it.isPlaying
            }
            awaitState(controller, "second crossfade-chain track") {
                it.currentItem?.metadata?.id == second.metadata.id && it.isPlaying
            }
            awaitState(controller, "third crossfade-chain track") {
                it.currentItem?.metadata?.id == third.metadata.id && it.isPlaying
            }
        } finally {
            controller.close()
            listOf(firstWave, secondWave, thirdWave).forEach { path ->
                repeat(20) {
                    if (runCatching { Files.deleteIfExists(path) }.isSuccess) return@forEach
                    delay(50L)
                }
            }
        }
    }

    @Test
    fun `stopped after deferred crossfade eof does not disable autoplay`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val wave = createWaveFile(durationSeconds = 3)
        val item = PlaybackItem(
            metadata = PlaybackMetadata(id = "deferred-eof-stop", title = "Deferred EOF stop", durationHintMs = 3_000L),
            source = PlaybackSource.LocalFile(wave.toString()),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )
        try {
            controller.replaceQueue(listOf(item))
            awaitState(controller, "deferred EOF source playback") { it.isPlaying }

            val player = controller.javaClass.getDeclaredField("player").apply { isAccessible = true }
                .get(controller) as MediaPlayer
            val listener = controller.javaClass.getDeclaredField("listener").apply { isAccessible = true }
                .get(controller) as MediaPlayerEventAdapter
            controller.javaClass.getDeclaredField("crossfadeSuppressFinishedRunId").apply { isAccessible = true }
                .set(controller, 77L)
            controller.javaClass.getDeclaredField("deferredFinishedRunId").apply { isAccessible = true }
                .set(controller, 77L)

            listener.stopped(player)
            delay(100L)

            assertTrue(
                controller.state.value.playWhenReady,
                "the stopped event paired with a deferred crossfade EOF must not pause the next auto-advanced track",
            )
        } finally {
            controller.close()
            delay(250L)
            Files.deleteIfExists(wave)
        }
    }

    @Test
    fun `crossfade keeps streamed callback media playing after promotion`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val firstWave = createWaveFile(durationSeconds = 5)
        val secondWave = createWaveFile(durationSeconds = 5)
        val thirdWave = createWaveFile(durationSeconds = 5)
        val files = listOf(firstWave, secondWave, thirdWave)
        val server = startRangeServer(files)
        val port = server.address.port
        val items = files.mapIndexed { index, _ ->
            PlaybackItem(
                metadata = PlaybackMetadata(
                    id = "callback-crossfade-$index",
                    title = "Callback crossfade $index",
                    durationHintMs = 5_000L,
                ),
                source = PlaybackSource.Remote(
                    url = "http://127.0.0.1:$port/$index.wav",
                    mimeType = "audio/wav",
                ),
            )
        }
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        ).apply {
            applicationHttpTransportPredicate = { true }
        }
        try {
            controller.setCrossfadeConfiguration(
                DesktopCrossfadeConfiguration(
                    enabled = true,
                    durationMs = 1_000L,
                    preserveGaplessAlbumTransitions = false,
                ),
            )
            controller.replaceQueue(items)
            awaitState(controller, "first callback crossfade track") {
                it.currentItem?.metadata?.id == items[0].metadata.id && it.isPlaying
            }
            awaitState(controller, "second callback crossfade track") {
                it.currentItem?.metadata?.id == items[1].metadata.id && it.isPlaying
            }
            delay(1_500L)
            val promoted = controller.javaClass
                .getDeclaredField("player")
                .apply { isAccessible = true }
                .get(controller) as MediaPlayer
            assertTrue(promoted.status().isPlaying(), "promoted callback-media player stopped after crossfade handoff")
            awaitState(controller, "third callback crossfade track") {
                it.currentItem?.metadata?.id == items[2].metadata.id && it.isPlaying
            }
        } finally {
            controller.close()
            server.stop(0)
            files.forEach { path ->
                repeat(20) {
                    if (runCatching { Files.deleteIfExists(path) }.isSuccess) return@forEach
                    delay(50L)
                }
            }
        }
    }

    @Test
    fun `pitch is reapplied when playback advances to the next queue item`() = runBlocking {
        // This native pitch bridge is Windows-specific and deliberately disabled on Linux.
        if (!VlcPitchBridge.isSupportedOnCurrentPlatform()) return@runBlocking
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val firstWave = createWaveFile(durationSeconds = 2)
        val secondWave = createWaveFile(durationSeconds = 2)
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )
        val pitchNeedsApply = controller.javaClass.getDeclaredField("pitchNeedsApply").apply {
            isAccessible = true
        }

        fun pitchPending(): Boolean = pitchNeedsApply.getBoolean(controller)

        try {
            val first = PlaybackItem(
                metadata = PlaybackMetadata(id = "pitch-first", title = "Pitch first"),
                source = PlaybackSource.LocalFile(firstWave.toString()),
            )
            val second = PlaybackItem(
                metadata = PlaybackMetadata(id = "pitch-second", title = "Pitch second"),
                source = PlaybackSource.LocalFile(secondWave.toString()),
            )
            controller.replaceQueue(listOf(first, second))
            awaitState(controller, "first pitched playback") { it.currentItem?.metadata?.id == first.metadata.id && it.status == PlaybackStatus.READY }
            controller.setPitch(5f)
            // Model the normal steady state after the first item's playing callback has applied
            // the pitch. The regression is the next load forgetting to arm this flag again.
            pitchNeedsApply.setBoolean(controller, false)

            controller.skipNext()
            withTimeout(10_000L) {
                while (!pitchPending()) delay(10L)
            }
            assertTrue(pitchPending(), "new media must reapply the persisted pitch")
            awaitState(controller, "second playback") { it.currentItem?.metadata?.id == second.metadata.id && it.status == PlaybackStatus.READY }
            withTimeout(10_000L) {
                while (pitchPending()) delay(10L)
            }
            assertFalse(pitchPending(), "persisted pitch must finish applying to the next item's audio output")
            controller.stop()
        } finally {
            controller.close()
            Files.deleteIfExists(firstWave)
            Files.deleteIfExists(secondWave)
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

    private fun startRangeServer(files: List<Path>): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            files.forEachIndexed { index, path ->
                createContext("/$index.wav") { exchange ->
                    val bytes = Files.readAllBytes(path)
                    val range = exchange.requestHeaders.getFirst("Range")
                    val match = Regex("""bytes=(\\d+)-(\\d*)""").matchEntire(range.orEmpty())
                    val start = match?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0L, bytes.size.toLong()) ?: 0L
                    val requestedEnd = match?.groupValues?.get(2)?.toLongOrNull()
                    val end = (requestedEnd ?: (bytes.size - 1L)).coerceIn(start, bytes.size - 1L)
                    val length = (end - start + 1L).coerceAtLeast(0L)
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.responseHeaders.add("Content-Type", "audio/wav")
                    if (match != null) {
                        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/" + bytes.size)
                        exchange.sendResponseHeaders(206, length)
                    } else {
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                    }
                    exchange.responseBody.use { output ->
                        if (match != null) {
                            output.write(bytes, start.toInt(), length.toInt())
                        } else {
                            output.write(bytes)
                        }
                    }
                }
            }
            start()
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

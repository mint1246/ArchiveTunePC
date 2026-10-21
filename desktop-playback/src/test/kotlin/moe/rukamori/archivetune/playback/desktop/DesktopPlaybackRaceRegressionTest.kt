/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori/archivetune
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.TransitionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regressions for the desktop playback races that broke long queues and user seeks.
 *
 * The tests need a local libVLC runtime, so they are skipped when none is installed.
 */
class DesktopPlaybackRaceRegressionTest {
    @Test
    fun `a catalog duration that disagrees with the decoded media advances the queue`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val shortWave = createWaveFile(durationSeconds = 2)
        val nextWave = createWaveFile(durationSeconds = 4)
        // The catalog entry claims far more than the decoded audio. The decoded length is the
        // truth, so the natural end of the media must advance the queue instead of failing.
        val first = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "catalog-duration-first",
                title = "Catalog duration first",
                durationHintMs = 20_000L,
            ),
            source = PlaybackSource.LocalFile(shortWave.toString()),
        )
        val second = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "catalog-duration-second",
                title = "Catalog duration second",
                durationHintMs = 4_000L,
            ),
            source = PlaybackSource.LocalFile(nextWave.toString()),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )
        val failures = mutableListOf<PlaybackSnapshot>()
        val collector = launch {
            controller.state.collect { snapshot ->
                if (snapshot.status == PlaybackStatus.ERROR) failures += snapshot
            }
        }

        try {
            controller.setVolume(0f)
            controller.replaceQueue(listOf(first, second))
            awaitState(controller, "first track playing") {
                it.currentItem?.metadata?.id == first.metadata.id && it.status == PlaybackStatus.READY
            }
            delay(200L)
            // The engine knows the decoded length of the media, the catalog entry does not.
            assertTrue(
                controller.state.value.durationMs in 1_000L..4_000L,
                "the decoded length must replace the catalog hint, " +
                    "durationMs=${controller.state.value.durationMs}",
            )
            awaitState(controller, "auto advance to the second track") {
                it.currentItem?.metadata?.id == second.metadata.id && it.status == PlaybackStatus.READY
            }
            assertTrue(controller.state.value.playWhenReady, "the auto-advanced track must keep playing")
            assertTrue(failures.isEmpty(), "playback must not fail: ${failures.map { it.error?.message }}")
        } finally {
            collector.cancel()
            controller.close()
            Files.deleteIfExists(shortWave)
            Files.deleteIfExists(nextWave)
        }
    }

    @Test
    fun `a track that ends at its decoded length ends the queue instead of failing`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val wave = createWaveFile(durationSeconds = 2)
        val item = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "catalog-duration-single",
                title = "Catalog duration single",
                durationHintMs = 20_000L,
            ),
            source = PlaybackSource.LocalFile(wave.toString()),
        )
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )

        try {
            controller.setVolume(0f)
            controller.replaceQueue(listOf(item))
            val terminal = withTimeout(20_000L) {
                controller.state.first {
                    it.status == PlaybackStatus.ENDED || it.status == PlaybackStatus.ERROR
                }
            }
            assertEquals(
                PlaybackStatus.ENDED,
                terminal.status,
                "the decoded media ended naturally: ${terminal.error?.message}",
            )
        } finally {
            controller.close()
            Files.deleteIfExists(wave)
        }
    }

    @Test
    fun `a seek during a delayed resolution resumes at the requested position`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val wave = createWaveFile(durationSeconds = 30)
        val item = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "delayed-resolution-seek",
                title = "Delayed resolution seek",
                durationHintMs = 30_000L,
            ),
            source = PlaybackSource.LocalFile(wave.toString()),
        )
        val resolverGate = CompletableDeferred<Unit>()
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver { queued ->
                resolverGate.await()
                DesktopConcretePlaybackSourceResolver.resolve(queued)
            },
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
        )

        try {
            controller.setVolume(0f)
            controller.replaceQueue(listOf(item))
            awaitState(controller, "resolving state") { it.status == PlaybackStatus.RESOLVING }
            controller.seekTo(7_000L)
            resolverGate.complete(Unit)
            awaitState(controller, "playing state") { it.status == PlaybackStatus.READY }
            delay(300L)
            val positionMs = controller.state.value.positionMs
            assertTrue(
                positionMs in 6_500L..12_000L,
                "the user seek must survive the pending load, positionMs=$positionMs",
            )
        } finally {
            controller.close()
            Files.deleteIfExists(wave)
        }
    }

    @Test
    fun `a stream recovery resumes at the position the user seeked to`() = runBlocking {
        val runtime = VlcRuntimeLocator.find() ?: return@runBlocking
        val wave = createWaveFile(durationSeconds = 30)
        val item = PlaybackItem(
            metadata = PlaybackMetadata(
                id = "recovery-user-seek",
                title = "Recovery user seek",
                durationHintMs = 30_000L,
            ),
            source = PlaybackSource.LocalFile(wave.toString()),
        )
        val recoveryStarted = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val retryRequested = CompletableDeferred<Unit>()
        val controller = VlcjPlaybackController(
            sourceResolver = PlaybackSourceResolver(DesktopConcretePlaybackSourceResolver::resolve),
            runtimeConfiguration = VlcRuntimeConfiguration(directory = runtime.root),
            onStreamHttpFailure = { _, _, _ ->
                recoveryStarted.complete(Unit)
                releaseRecovery.await()
                true
            },
        )
        val events = launch {
            controller.events.collect { event ->
                if (event is PlaybackEvent.MediaTransition && event.reason == TransitionReason.RETRY) {
                    retryRequested.complete(Unit)
                }
            }
        }

        try {
            controller.setVolume(0f)
            controller.replaceQueue(listOf(item))
            awaitState(controller, "playing state") { it.status == PlaybackStatus.READY }
            delay(200L)

            controller.simulateStreamHttpFailure(httpStatus = 403)
            withTimeout(10_000L) { recoveryStarted.await() }
            // The user moves the position while the recovery callback is suspended.
            controller.seekTo(9_000L)
            releaseRecovery.complete(Unit)

            // The recovery has to reload the media instead of restoring the old position.
            withTimeout(20_000L) { retryRequested.await() }
            withTimeout(20_000L) {
                controller.state.first { it.status == PlaybackStatus.READY && it.positionMs > 5_000L }
            }
            val positionMs = controller.state.value.positionMs
            assertTrue(
                positionMs in 8_500L..16_000L,
                "the recovery must resume at the position the user asked for, positionMs=$positionMs",
            )
        } finally {
            events.cancel()
            controller.close()
            Files.deleteIfExists(wave)
        }
    }

    private suspend fun awaitState(
        controller: VlcjPlaybackController,
        label: String,
        predicate: (PlaybackSnapshot) -> Boolean,
    ) {
        try {
            withTimeout(20_000L) { controller.state.first(predicate) }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $label; state=${controller.state.value}", timeout)
        }
    }

    private fun createWaveFile(durationSeconds: Int): Path {
        val sampleRate = 44_100
        val samples = sampleRate * durationSeconds
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val path = Files.createTempFile("archivetune-race-regression-", ".wav")
        AudioInputStream(ByteArrayInputStream(ByteArray(samples * 2)), format, samples.toLong()).use { input ->
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile())
        }
        return path
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopStemCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `worker gains preserve all six stems in worker order`() {
        val mix = DesktopStemControls(
            vocals = DesktopStemChannelControl(value = 0.2f),
            guitar = DesktopStemChannelControl(value = 0.3f),
            piano = DesktopStemChannelControl(value = 0.4f),
        ).effectiveMix()

        // Worker/source order is drums, bass, other, vocals, guitar, piano. A regression that
        // drops guitar/piano or reorders channels would silently mute the live mixer controls.
        assertEquals(listOf(1f, 1f, 1f, 0.2f, 0.3f, 0.4f), mix.workerGains())
    }

    @Test
    fun `mute and solo are resolved for every channel`() {
        val mix = DesktopStemControls(
            vocals = DesktopStemChannelControl(value = 0.5f, solo = true),
            drums = DesktopStemChannelControl(muted = true),
            bass = DesktopStemChannelControl(value = 0.25f),
            guitar = DesktopStemChannelControl(value = 0.75f, solo = true),
        ).effectiveMix()

        assertEquals(0.5f, mix.vocals)
        assertEquals(0f, mix.drums)
        assertEquals(0f, mix.bass)
        assertEquals(0.75f, mix.guitar)
        assertEquals(0f, mix.other)
        assertEquals(0f, mix.piano)
    }

    @Test
    fun `controls clamp non finite and out of range gains`() {
        val controls = DesktopStemControls(
            vocals = DesktopStemChannelControl(value = Float.NaN),
            drums = DesktopStemChannelControl(value = Float.POSITIVE_INFINITY),
            bass = DesktopStemChannelControl(value = -2f),
            other = DesktopStemChannelControl(value = 5f),
        ).sanitized()

        assertEquals(1f, controls.vocals.value)
        assertEquals(1f, controls.drums.value)
        assertEquals(0f, controls.bass.value)
        assertEquals(1f, controls.other.value)
    }

    @Test
    fun `inspect reports ready only for a complete six stem cache`() {
        val item = item("abcdefghijk")
        val stems = temporaryFolder.newFolder("stems").toPath()
        val cache = writeCompleteCache(stems, item)
        val scope = testScope()
        val coordinator = coordinator(scope, item, stems)
        try {
            coordinator.inspect(item)
            val state = coordinator.state.value
            assertEquals(DesktopStemPhase.READY, state.phase)
            assertEquals(cache, state.cacheDirectory)
            assertTrue(state.ready)
            assertFalse(state.rendererActive)

            val missingOne = item("lmnopqrstuv")
            coordinator.inspect(missingOne)
            assertEquals(DesktopStemPhase.IDLE, coordinator.state.value.phase)
            assertEquals(missingOne.metadata.id, coordinator.state.value.trackId)

            coordinator.inspect(null)
            assertEquals(DesktopStemState(), coordinator.state.value)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `cache directory key stays stable across extraction`() {
        val item = item("abcdefghijk")
        val stems = temporaryFolder.newFolder("stems").toPath()
        val cache = writeCompleteCache(stems, item)

        // The coordinator's persisted cache layout must not change with the module boundary:
        // an old install's generated stems are still found by the extracted implementation.
        assertEquals(stems.resolve("dc09f5cf8c36aebce651d220d1672971"), cache)
    }

    @Test
    fun `existing caches are indexed on startup`() = runBlocking {
        val item = item("abcdefghijk")
        val stems = temporaryFolder.newFolder("stems").toPath()
        writeCompleteCache(stems, item)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = coordinator(scope, item, stems)
        try {
            withTimeout(3_000) {
                while (item.metadata.id !in coordinator.readyTrackIds.value) delay(10)
            }
            assertTrue(item.metadata.id in coordinator.readyTrackIds.value)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `controls are scoped per track and default resets them`() {
        val first = item("aaaaaaaaaaa")
        val second = item("bbbbbbbbbbb")
        val stems = temporaryFolder.newFolder("stems").toPath()
        val scope = testScope()
        val coordinator = coordinator(scope, first, stems)
        try {
            coordinator.setControls(first, DesktopStemControls(vocals = DesktopStemChannelControl(muted = true)))
            coordinator.setControls(second, DesktopStemControls(guitar = DesktopStemChannelControl(solo = true)))

            assertTrue(coordinator.controlsFor(first).vocals.muted)
            assertFalse(coordinator.controlsFor(second).vocals.muted)
            assertTrue(coordinator.controlsFor(second).guitar.solo)

            coordinator.setControls(first, DesktopStemControls())
            assertEquals(DesktopStemControls(), coordinator.controlsFor(first))
            assertEquals(DesktopStemChannelControl(solo = true), coordinator.controlsFor(second).guitar)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `close restores normal VLC audio and cancels queued work`() {
        val item = item("abcdefghijk")
        val stems = temporaryFolder.newFolder("stems").toPath()
        val scope = testScope()
        val bridge = FakeBridge(snapshot(item))
        val coordinator = DesktopStemCoordinator(
            playback = bridge,
            applicationScope = scope,
            modelsDirectory = temporaryFolder.newFolder("models").toPath(),
            stemsDirectory = stems,
            modelManifestUrl = "https://example.invalid/models/manifest.json",
        )
        coordinator.enqueueAll(listOf(item, item("lmnopqrstuv")))
        coordinator.close()

        // Shutdown must hand audio back to VLC synchronously because DesktopMusicServices closes
        // stems before playback. Losing this call would leave a module-suppressed audio session.
        assertEquals(listOf(false), bridge.rendererToggles)
        scope.cancel()
    }

    private fun coordinator(
        scope: CoroutineScope,
        current: PlaybackItem,
        stems: Path,
    ) = DesktopStemCoordinator(
        playback = FakeBridge(snapshot(current)),
        applicationScope = scope,
        modelsDirectory = temporaryFolder.newFolder("models").toPath(),
        stemsDirectory = stems,
        modelManifestUrl = "https://example.invalid/models/manifest.json",
    )

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun item(id: String) = PlaybackItem(
        PlaybackMetadata(id = id, title = id, durationHintMs = 180_000L),
        PlaybackSource.Deferred(id),
    )

    private fun snapshot(current: PlaybackItem) = PlaybackSnapshot(
        queue = listOf(current),
        currentIndex = 0,
        status = PlaybackStatus.READY,
    )

    private fun writeCompleteCache(root: Path, item: PlaybackItem): Path {
        val identity = buildString {
            append("htdemucs-6s-1s-dml-v1").append('\u0000')
            append(item.metadata.id).append('\u0000')
            append(item.metadata.durationHintMs ?: -1L)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        val key = digest.take(16).joinToString("") { "%02x".format(it) }
        val directory = root.resolve(key)
        Files.createDirectories(directory)
        listOf("drums", "bass", "other", "vocals", "guitar", "piano").forEach { stem ->
            Files.write(directory.resolve("$stem.opus"), ByteArray(2_048) { 1 })
        }
        val manifest = Properties().apply {
            setProperty("format", "ogg-opus-6x-stereo")
            setProperty("model", "htdemucs-6s-1s-dml-v1")
            setProperty("trackId", item.metadata.id)
            setProperty("title", item.metadata.title)
            setProperty("stems", "drums,bass,other,vocals,guitar,piano")
        }
        Files.newOutputStream(directory.resolve("manifest.properties")).use { output ->
            manifest.store(output, null)
        }
        return directory
    }

    private class FakeBridge(initial: PlaybackSnapshot) : StemPlaybackBridge {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<PlaybackSnapshot> = mutableState.asStateFlow()
        val rendererToggles = CopyOnWriteArrayList<Boolean>()

        override suspend fun decodeStemInput(item: PlaybackItem, output: Path): Path = output
        override suspend fun compressGeneratedStemSetForCache(directory: Path): List<Path> = emptyList()
        override suspend fun setExternalAudioRendererActive(active: Boolean) {
            rendererToggles += active
        }
    }
}

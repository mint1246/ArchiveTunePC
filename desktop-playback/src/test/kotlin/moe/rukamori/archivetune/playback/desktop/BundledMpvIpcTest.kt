/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue

class BundledMpvIpcTest {
    @Test
    fun `bundled Windows mpv accepts IPC commands`() = runBlocking {
        val executable = bundledMpv()
        if (!MpvExecutableLocator.isWindows() || !Files.isRegularFile(executable)) return@runBlocking
        val host = MpvProcessHost(
            configuration = MpvConfiguration(executable = executable.toAbsolutePath().toString(), startupTimeoutMs = 5_000),
            onMessage = {},
            onExit = { _, _ -> },
        )
        try {
            withTimeout(10_000) { host.start() }
            assertTrue(host.isRunning)
            host.eventCommand("observe_property", 1, "idle-active")
            host.startEventLoop()
            host.command("get_property", "idle-active")
        } finally {
            host.close()
        }
    }

    @Test
    fun `controller loads a local audio file through bundled Windows mpv`() = runBlocking {
        val executable = bundledMpv()
        if (!MpvExecutableLocator.isWindows() || !Files.isRegularFile(executable)) return@runBlocking
        val wave = Files.createTempFile("archivetune-mpv-ipc-", ".wav")
        val pcm = ByteArray(8_000 / 4 * 2)
        AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(8_000f, 16, 1, true, false), pcm.size / 2L).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, wave.toFile())
        }
        val controller = MpvPlaybackController(
            sourceResolver = DesktopConcretePlaybackSourceResolver,
            configuration = MpvConfiguration(executable = executable.toAbsolutePath().toString(), startupTimeoutMs = 5_000),
        )
        try {
            controller.replaceQueue(
                items = listOf(PlaybackItem(PlaybackMetadata("ipc-test", "IPC test"), PlaybackSource.LocalFile(wave.toString()))),
                playWhenReady = false,
            )
            val ready = withTimeout(10_000) { controller.state.first { it.status == PlaybackStatus.READY } }
            assertTrue(ready.currentItem != null)
        } finally {
            controller.close()
            Files.deleteIfExists(wave)
        }
    }

    private fun bundledMpv(): Path =
        Path.of("..", "packaging", "mpv", "common", "native", "windows-x64", "mpv.exe").normalize()
}

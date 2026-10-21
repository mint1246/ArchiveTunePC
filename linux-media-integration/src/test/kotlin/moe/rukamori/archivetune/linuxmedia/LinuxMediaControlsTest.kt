package moe.rukamori.archivetune.linuxmedia

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.*
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class LinuxMediaControlsTest {
    private fun snapshot() = PlaybackSnapshot(
        queue = listOf(PlaybackItem(PlaybackMetadata("synthetic", "Synthetic song", artists = listOf(PlaybackArtist(name="Artist"))))),
        currentIndex = 0, status = PlaybackStatus.READY, playWhenReady = true,
        positionMs = 1234L, durationMs = 8000L, volume = 0.7f,
    )

    @Test fun `metadata uses MPRIS types and microseconds`() {
        val props = LinuxMediaControls.playerProperties(snapshot())
        assertEquals("Playing", props.getValue("PlaybackStatus").value)
        assertEquals(1_234_000L, props.getValue("Position").value)
        @Suppress("UNCHECKED_CAST") val metadata = props.getValue("Metadata").value as Map<String, Variant<*>>
        assertEquals(8_000_000L, metadata.getValue("mpris:length").value)
        assertEquals("o", metadata.getValue("mpris:trackid").sig)
        assertEquals("as", metadata.getValue("xesam:artist").sig)
        assertEquals(Long.MAX_VALUE / 1000 * 1000, LinuxMediaControls.micros(Long.MAX_VALUE))
    }

    @Test fun `unknown and readonly properties cannot change player state`() {
        LinuxMediaControls().use { controls ->
            assertFailsWith<PropertyReadOnly> {
                controls.endpoint.Set(LinuxMediaControls.PLAYER_INTERFACE, "Position", Variant(0L, "x"))
            }
        }
    }

    @Test fun `real session bus serves properties and receives desktop commands`() = runBlocking {
        assumeTrue(System.getenv("ARCHIVETUNE_MPRIS_SMOKE") == "1", "Opt-in D-Bus smoke")
        val name = "org.mpris.MediaPlayer2.ArchiveTuneTest.p${ProcessHandle.current().pid()}"
        val controls = LinuxMediaControls(name)
        try {
            controls.update(snapshot())
            controls.start()
            withTimeout(8000) { controls.availability.first { it != LinuxMediaAvailability.NOT_STARTED } }
            assertEquals(LinuxMediaAvailability.ACTIVE, controls.availability.value)
            fun call(vararg args: String): String {
                val process = ProcessBuilder(*args).redirectErrorStream(true).start()
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "D-Bus client timed out")
                val result = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), result)
                return result.trim()
            }
            assertEquals("x 1234000", call("busctl", "--user", "get-property", name, LinuxMediaControls.OBJECT_PATH, LinuxMediaControls.PLAYER_INTERFACE, "Position"))
            assertEquals("s \"Playing\"", call("busctl", "--user", "get-property", name, LinuxMediaControls.OBJECT_PATH, LinuxMediaControls.PLAYER_INTERFACE, "PlaybackStatus"))
            val metadata = call("busctl", "--user", "get-property", name, LinuxMediaControls.OBJECT_PATH, LinuxMediaControls.PLAYER_INTERFACE, "Metadata")
            assertTrue(metadata.startsWith("a{sv}"), metadata)
            assertTrue(metadata.contains("Synthetic song"), metadata)
            val playPause = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { controls.commands.first() } }
            call("gdbus", "call", "--session", "--dest", name, "--object-path", LinuxMediaControls.OBJECT_PATH, "--method", LinuxMediaControls.PLAYER_INTERFACE + ".PlayPause")
            assertEquals(LinuxMediaCommand.Transport.PLAY_PAUSE, playPause.await())
            val volume = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { controls.commands.first() } }
            call("busctl", "--user", "set-property", name, LinuxMediaControls.OBJECT_PATH, LinuxMediaControls.PLAYER_INTERFACE, "Volume", "d", "0.35")
            assertEquals(LinuxMediaCommand.Volume(0.35f), volume.await())
            val seek = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3000) { controls.commands.first() } }
            call("gdbus", "call", "--session", "--dest", name, "--object-path", LinuxMediaControls.OBJECT_PATH, "--method", LinuxMediaControls.PLAYER_INTERFACE + ".Seek", "5000000")
            assertEquals(LinuxMediaCommand.Seek(5000), seek.await())
        } finally { controls.close() }
        assertEquals(LinuxMediaAvailability.CLOSED, controls.availability.value)
    }
}

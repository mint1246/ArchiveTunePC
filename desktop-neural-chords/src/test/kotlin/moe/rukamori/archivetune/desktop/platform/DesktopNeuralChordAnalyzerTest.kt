package moe.rukamori.archivetune.desktop.platform

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.chords.ChordRequest
import moe.rukamori.archivetune.chords.ChordTrackSource
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopNeuralChordAnalyzerTest {
    @Test
    fun `cached model results are reused without decoding or loading a runner`() = runBlocking {
        val root = Files.createTempDirectory("neural-chord-cache")
        try {
            val item = PlaybackItem(PlaybackMetadata(id = "song-id", title = "Song", durationHintMs = 5_000))
            val identity = DesktopModels.CHORDS.version + "\u0000song-id\u00005000"
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
            val key = digest.take(16).joinToString("") { "%02x".format(it) }
            val directory = root.resolve("chords")
            Files.createDirectories(directory)
            val result = JSONObject().put("duration_ms", 5000).put("events", JSONArray().put(
                JSONObject().put("start_ms", 0).put("end_ms", 1000).put("chord", "C").put("confidence", 0.8),
            ))
            Files.writeString(directory.resolve("$key.json"), JSONObject().put("modelVersion", DesktopModels.CHORDS.version).put("result", result).toString())
            var decoded = false
            val analyzer = DesktopNeuralChordAnalyzer(root, root.resolve("models"), "https://unused.invalid/manifest.json", { _, _, _, _ -> decoded = true })
            val track = analyzer.analyze(item, ChordRequest("Song", "Artist", 5_000)).getOrThrow()
            assertFalse(decoded)
            assertEquals(ChordTrackSource.LOCAL_NEURAL, track.source)
            assertEquals("C", track.events.single().name)
            assertEquals(5_000L, track.tabDurationMs)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

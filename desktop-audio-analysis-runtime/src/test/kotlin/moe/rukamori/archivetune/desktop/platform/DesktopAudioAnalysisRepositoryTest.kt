package moe.rukamori.archivetune.desktop.platform

import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata

class DesktopAudioAnalysisRepositoryTest {
    @Test
    fun `existing v1 cache is reused without decoding or loading native analysis`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("audio-analysis-sdk-test")
        val item = PlaybackItem(PlaybackMetadata(id = "track", title = "Track"))
        val identity = DesktopAudioAnalysisRepository.analysisIdentity(item)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cacheDirectory = Files.createDirectories(cacheRoot.resolve("audio-analysis/v1"))
        DataOutputStream(Files.newOutputStream(cacheDirectory.resolve("$digest.bin"))).use { output ->
            output.writeInt(0x41544241)
            output.writeInt(1)
            output.writeDouble(120.0)
            output.writeUTF("C")
            output.writeUTF("major")
            output.writeFloat(0.9f)
            output.writeInt(2)
            output.writeFloat(0.5f)
            output.writeFloat(1.0f)
        }
        val repository = DesktopAudioAnalysisRepository(
            cacheRoot = cacheRoot,
            decodeMono44k = { _, _ -> error("Cached data must not trigger decoding") },
            logger = Logger.getLogger("audio-analysis-test"),
        )
        val result = assertNotNull(repository.beatKeyAnalysis(item))
        assertEquals(120.0, result.bpm)
        assertEquals("C", result.key)
        assertContentEquals(floatArrayOf(0.5f, 1.0f), result.beatsSeconds)
    }
}

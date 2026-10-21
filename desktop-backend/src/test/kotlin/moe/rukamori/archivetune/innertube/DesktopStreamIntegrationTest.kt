package moe.rukamori.archivetune.innertube

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public-video smoke test. Signed URLs and response bodies never appear in assertions. */
class DesktopStreamIntegrationTest {
    @Test(timeout = 120_000)
    fun `resolves and reads a public YouTube audio stream`() = runBlocking {
        val videoId = System.getenv("ARCHIVETUNE_TEST_VIDEO_ID")
        assumeTrue(!videoId.isNullOrBlank())
        val cache = Files.createTempDirectory("archivetune-stream-smoke-")
        try {
            val backend = ArchiveTuneJvmClient(cache.toFile())
            val resolved = backend.resolveAudioStream(requireNotNull(videoId))
            val failure = resolved.exceptionOrNull()
            val failureKind = failure?.let(::classifyStreamResolutionFailure)
            val categories = failure?.let {
                (listOf(it) + it.suppressed).map { error ->
                    val status = (error as? StreamPlaybackUnavailableException)?.status
                        ?.takeIf { value -> value.matches(Regex("[A-Z_]{1,40}")) }
                    error.javaClass.simpleName + status?.let { value -> ":$value" }.orEmpty()
                }.distinct().joinToString()
            }
            assertTrue("Public stream resolution failed: $failureKind ($categories)", resolved.isSuccess)
            val probe = probeResolvedAudioStream(requireNotNull(resolved.getOrNull()))
            assertTrue("Resolved public stream could not be read", probe.isSuccess)
            assertTrue("No audio bytes received", requireNotNull(probe.getOrNull()).bytesRead > 0)
        } finally {
            NetworkGatekeeper.setConnectionBlocked(true)
            cache.toFile().deleteRecursively()
        }
    }
}

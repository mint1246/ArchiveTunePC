package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opt-in evidence for the real cold-mint budget.
 *
 * Run with `ARCHIVETUNE_BOTGUARD_SMOKE=1 ./gradlew :desktop-auth:test --tests '*DesktopBotGuardSmokeTest*'`
 * (or `-Darchivetune.botguard.smoke=true`). It starts a throwaway Chromium profile, mints an
 * anonymous token pair for a public video and the synthetic identifier below, then prints only the
 * elapsed time. Token values are never printed or asserted against YouTube.
 */
class DesktopBotGuardSmokeTest {
    @Test
    fun `anonymous cold mint fits the playback preparation budget`() {
        assumeTrue(
            "Set ARCHIVETUNE_BOTGUARD_SMOKE=1 to run the live BotGuard smoke test",
            System.getenv("ARCHIVETUNE_BOTGUARD_SMOKE") == "1" ||
                System.getProperty("archivetune.botguard.smoke") == "true",
        )
        val browser = findSupportedChromiumBrowser()
        assumeTrue("No supported Chromium browser is installed", browser != null)

        val tempRoot = Files.createTempDirectory("archivetune-botguard-smoke-")
        try {
            val provider =
                ChromiumBotGuardPoTokenProvider(
                    browserFinder = { browser },
                    tempRoot = tempRoot,
                )

            val startedNanos = System.nanoTime()
            val result = runBlocking { provider.mint(SMOKE_VIDEO_ID, SMOKE_SESSION_ID) }
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L

            assertNotNull("Anonymous BotGuard mint returned no tokens after ${elapsedMillis}ms", result)
            val tokens = requireNotNull(result)
            assertTrue("Player token was empty", tokens.playerToken.isNotBlank())
            assertTrue("Session token was empty", tokens.sessionToken.isNotBlank())

            // Never print token values. Timing is the evidence the parent needs for the budget.
            println("BotGuard anonymous cold mint succeeded in ${elapsedMillis}ms")
            assertTrue(
                "Cold mint took ${elapsedMillis}ms and no longer fits the 15s playback auth window",
                elapsedMillis < 15_000L,
            )
        } finally {
            deleteRecursively(tempRoot)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private companion object {
        /** Big Buck Bunny, a long-lived public upload. */
        private const val SMOKE_VIDEO_ID = "aqz-KE-bpKQ"

        /** Any stable identifier is valid for BotGuard; this one is intentionally not a real visitor. */
        private const val SMOKE_SESSION_ID = "archivetune-botguard-smoke-session"
    }
}

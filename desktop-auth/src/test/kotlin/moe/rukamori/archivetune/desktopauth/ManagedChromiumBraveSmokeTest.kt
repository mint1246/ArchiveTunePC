/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Opt-in real-browser smoke test. It never touches the user's browser profile or Google account:
 * a throwaway Chromium profile is launched, synthetic cookies are written through CDP, the browser
 * is closed through Chromium's normal shutdown path (`Browser.close`, the same flush path a user
 * window close takes), and a second headless launch reads the same cookies back.
 *
 * Run explicitly after the unit suite:
 *
 * ```
 * ARCHIVETUNE_BRAVE_SMOKE=1 ./gradlew :desktop-auth:test \
 *   --tests "moe.rukamori.archivetune.desktopauth.ManagedChromiumBraveSmokeTest"
 * ```
 *
 * Without the environment variable (or `-Darchivetune.brave.smoke=1`) every test in this class is
 * skipped, so normal CI never depends on an installed GUI browser.
 */
class ManagedChromiumBraveSmokeTest {
    @Test
    fun `real chromium persists synthetic cookies across normal close and reopen`() = runBlocking {
        assumeTrue("Set ARCHIVETUNE_BRAVE_SMOKE=1 to run the real-browser smoke test", smokeEnabled())
        assumeTrue("The managed-browser smoke test is Linux only", isLinuxOperatingSystem())

        val browser = requireNotNull(findLinuxChromiumBrowser()) { "No Linux Chromium browser is installed" }
        println("Managed browser smoke using $browser")

        val cacheRoot =
            System.getenv("XDG_CACHE_HOME")
                ?.takeIf(String::isNotBlank)
                ?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".cache")
        val profile = Files.createTempDirectory(Files.createDirectories(cacheRoot.resolve("archivetune-smoke")), "profile-")
        var firstProcess: Process? = null
        var secondProcess: Process? = null
        try {
            Files.setPosixFilePermissions(profile, PosixFilePermissions.fromString("rwx------"))

            val firstBrowserProcess = launch(browser, profile)
            firstProcess = firstBrowserProcess
            val first = connect(profile, firstBrowserProcess)
            try {
                first.page.call("Network.enable")
                setSyntheticCookie(first.page, "SAPISID", "synthetic-smoke-value")
                setSyntheticCookie(first.page, "PREF", "synthetic-smoke-pref")

                val beforeRestart = sessionFromYouTubeCookies(musicCookies(first.page))
                assertNotNull("Synthetic cookies were not visible before shutdown", beforeRestart)
                assertTrue(beforeRestart!!.cookieHeader.contains("SAPISID=synthetic-smoke-value"))

                // Chromium's normal shutdown path is the same flush path production relies on
                // when the user closes the dedicated Linux login window. The completion wait must
                // observe that root exit instead of treating it as an early close.
                val normalExit =
                    async {
                        awaitManagedLoginCompletion(
                            completion = CompletableDeferred(),
                            managedProcess = firstBrowserProcess,
                            loginTimeout = Duration.ofSeconds(20),
                            pollDelayMillis = 50L,
                            isBrowserRunning = { firstBrowserProcess.isAlive },
                            browserCloseGrace = Duration.ZERO,
                            completeOnBrowserExit = true,
                        )
                    }
                runCatching { first.browser.call("Browser.close") }
                normalExit.await()
            } finally {
                first.page.close()
                first.browser.close()
            }

            assertTrue(
                "Brave did not exit after Browser.close within 20s",
                firstBrowserProcess.waitFor(20, TimeUnit.SECONDS),
            )
            awaitNoManagedProfileProcesses(profile, Duration.ofSeconds(10))
            assertFalse("Normal shutdown left SingletonLock behind", Files.exists(profile.resolve("SingletonLock")))
            clearStaleManagedProfileLockFiles(profile)

            val secondBrowserProcess = launch(browser, profile)
            secondProcess = secondBrowserProcess
            val second = connect(profile, secondBrowserProcess)
            try {
                val afterRestart = sessionFromYouTubeCookies(musicCookies(second.page))
                assertNotNull("Cookies were not flushed to the profile", afterRestart)
                assertTrue(afterRestart!!.cookieHeader.contains("SAPISID=synthetic-smoke-value"))
                assertTrue(afterRestart.cookieHeader.contains("PREF=synthetic-smoke-pref"))

                runCatching { second.browser.call("Browser.close") }
            } finally {
                second.page.close()
                second.browser.close()
            }
            assertTrue(
                "Brave did not exit after Browser.close within 20s",
                secondBrowserProcess.waitFor(20, TimeUnit.SECONDS),
            )
            awaitNoManagedProfileProcesses(profile, Duration.ofSeconds(10))
        } finally {
            firstProcess?.destroyForcibly()
            secondProcess?.destroyForcibly()
            findManagedBrowserProcesses(profile).forEach { handle -> runCatching { handle.destroyForcibly() } }
            awaitNoManagedProfileProcesses(profile, Duration.ofSeconds(5), failOnLeak = false)
            Files.walk(profile).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private suspend fun connect(profile: Path, process: Process): Clients {
        val endpoint =
            awaitManagedDevToolsEndpoint(
                profile = profile,
                timeout = Duration.ofSeconds(30),
                pollDelayMillis = 100L,
                isBrowserRunning = { process.isAlive },
            )
        val browser = CdpClient.connect(endpoint.browserWebSocketUrl)
        try {
            val target =
                awaitManagedPageTarget(
                    port = endpoint.port,
                    timeout = Duration.ofSeconds(30),
                    pollDelayMillis = 100L,
                    isBrowserRunning = { process.isAlive },
                )
            return Clients(browser = browser, page = CdpClient.connect(target.webSocketDebuggerUrl))
        } catch (failure: Throwable) {
            browser.close()
            throw failure
        }
    }

    private fun setSyntheticCookie(page: CdpClient, name: String, value: String) {
        val result =
            page.call(
                "Network.setCookie",
                JSONObject()
                    .put("name", name)
                    .put("value", value)
                    .put("url", "https://music.youtube.com/")
                    .put("domain", ".youtube.com")
                    .put("path", "/")
                    .put("secure", true)
                    .put("httpOnly", true)
                    .put("expires", System.currentTimeMillis() / 1_000L + 3_600L),
            )
        assertTrue("Chromium rejected synthetic cookie $name: $result", !result.has("success") || result.optBoolean("success"))
    }

    private fun musicCookies(page: CdpClient): JSONArray =
        page
            .call(
                "Network.getCookies",
                JSONObject().put("urls", JSONArray().put("https://music.youtube.com/")),
            ).getJSONArray("cookies")

    private fun launch(browser: Path, profile: Path): Process =
        ProcessBuilder(managedExtractionBrowserCommand(browser, profile))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            // Keep the smoke test's browser stderr off disk as well: it can contain URLs.
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .also { it.outputStream.close() }

    private suspend fun awaitNoManagedProfileProcesses(
        profile: Path,
        timeout: Duration,
        failOnLeak: Boolean = true,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        var remaining = findManagedBrowserProcesses(profile)
        while (remaining.isNotEmpty() && System.nanoTime() < deadline) {
            delay(50L)
            remaining = findManagedBrowserProcesses(profile)
        }
        if (remaining.isNotEmpty() && failOnLeak) {
            fail("Browser processes still own the synthetic profile: ${remaining.map { it.pid() }}")
        }
    }

    private data class Clients(
        val browser: CdpClient,
        val page: CdpClient,
    )

    private companion object {
        fun smokeEnabled(): Boolean {
            val values = setOf("1", "true", "yes", "on")
            return System.getenv("ARCHIVETUNE_BRAVE_SMOKE")?.lowercase()?.let { it in values } == true ||
                System.getProperty("archivetune.brave.smoke")?.lowercase()?.let { it in values } == true
        }
    }
}

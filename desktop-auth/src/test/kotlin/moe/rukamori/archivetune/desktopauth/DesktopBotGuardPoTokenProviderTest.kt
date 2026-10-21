package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

class DesktopBotGuardPoTokenProviderTest {
    @Test
    fun `blank identifiers never start a browser`() =
        runBlocking {
            var browserLookups = 0
            val root = Files.createTempDirectory("archivetune-botguard-blank-")
            try {
                val provider =
                    ChromiumBotGuardPoTokenProvider(
                        browserFinder = {
                            browserLookups++
                            null
                        },
                        tempRoot = root,
                    )

                assertNull(provider.mint("", "session"))
                assertNull(provider.mint("video", " "))
                assertEquals(0, browserLookups)
            } finally {
                deleteRecursively(root)
            }
        }

    @Test
    fun `linux browser command is headless, private and container safe`() {
        val browser = Paths.get("/usr/bin/brave")
        val profile = Paths.get("/tmp/archivetune-botguard-profile")
        val command = botGuardBrowserCommand(browser, profile, isLinux = true, runningAsRoot = false)

        assertEquals(browser.toString(), command.first())
        assertEquals("about:blank", command.last())
        assertTrue(command.contains("--headless=new"))
        assertTrue(command.contains("--user-data-dir=$profile"))
        assertTrue(command.contains("--remote-debugging-port=0"))
        assertTrue(command.contains("--remote-debugging-address=127.0.0.1"))
        assertTrue(command.contains("--disable-dev-shm-usage"))
        assertTrue(command.contains("--disable-gpu"))
        assertTrue(command.contains("--password-store=basic"))
        assertFalse(command.contains("--no-sandbox"))
    }

    @Test
    fun `root linux browser command opts out of the Chromium sandbox`() {
        val command =
            botGuardBrowserCommand(
                Paths.get("/usr/bin/chromium"),
                Paths.get("/tmp/profile"),
                isLinux = true,
                runningAsRoot = true,
            )

        assertTrue(command.contains("--no-sandbox"))
    }

    @Test
    fun `windows browser command does not receive linux-only flags`() {
        val command =
            botGuardBrowserCommand(
                Paths.get("C:\\Browser\\chrome.exe"),
                Paths.get("C:\\Temp\\archivetune-botguard-profile"),
                isLinux = false,
                runningAsRoot = false,
            )

        assertTrue(command.contains("--headless=new"))
        assertFalse(command.contains("--disable-dev-shm-usage"))
        assertFalse(command.contains("--disable-gpu"))
        assertFalse(command.contains("--password-store=basic"))
        assertFalse(command.contains("--no-sandbox"))
    }

    @Test
    fun `plain challenge response is parsed into the BotGuard payload`() {
        val challenge = challengeArray(program = "plain-program", globalName = "plainGlobal")
        val parsed = JSONObject(parseCreateChallenge(JSONArray().put(challenge).toString()))

        assertEquals("plain-program", parsed.getString("program"))
        assertEquals("plainGlobal", parsed.getString("globalName"))
        val interpreter = parsed.getJSONObject("interpreterJavascript")
        assertEquals("var interpreter = true;", interpreter.getString("privateDoNotAccessOrElseSafeScriptWrappedValue"))
        assertEquals(
            "https://www.youtube.com/interpreter.js",
            interpreter.getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"),
        )
    }

    @Test
    fun `scrambled challenge response is descrambled before parsing`() {
        val challenge = challengeArray(program = "scrambled-program", globalName = "scrambledGlobal")
        val outer =
            JSONArray()
                .put(JSONObject.NULL)
                .put(scramble(challenge.toString()))

        val parsed = JSONObject(parseCreateChallenge(outer.toString()))

        assertEquals("scrambled-program", parsed.getString("program"))
        assertEquals("scrambledGlobal", parsed.getString("globalName"))
    }

    @Test
    fun `integrity token response is decoded with the YouTube alphabet`() {
        val tokenBytes = byteArrayOf(0, 1, 2, 3, 0x7F, 0xFB.toByte(), 0xFF.toByte())
        val encoded =
            Base64
                .getEncoder()
                .encodeToString(tokenBytes)
                .replace('+', '-')
                .replace('/', '_')
        val raw = JSONArray().put(encoded).put(3_600L)

        val (decoded, lifetimeSeconds) = parseIntegrityToken(raw.toString())

        assertArrayEquals(tokenBytes, decoded)
        assertEquals(3_600L, lifetimeSeconds)
    }

    @Test
    fun `po token encoding matches Android's URL safe base64`() {
        assertEquals("-_8=", encodePoToken("251,255"))
        assertEquals("AQID", encodePoToken("1,2,3"))
        assertFalse(encodePoToken("251,255").contains('+'))
        assertFalse(encodePoToken("251,255").contains('/'))
    }

    @Test
    fun `cold start budget returns null instead of throwing`() =
        runBlocking {
            assumeTrue(isLinuxOperatingSystem())
            val fixture = fakeBrowser()
            try {
                val provider =
                    ChromiumBotGuardPoTokenProvider(
                        browserFinder = { fixture.browser },
                        tempRoot = fixture.root,
                        coldStartBudgetMillis = 300L,
                    )

                val startedNanos = System.nanoTime()
                val result = provider.mint("video-id", "session-id")
                val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L

                assertNull(result)
                assertTrue("Mint ignored its 300ms budget and took ${elapsedMillis}ms", elapsedMillis < 8_000L)
            } finally {
                deleteRecursively(fixture.root)
            }
        }

    @Test
    fun `caller cancellation propagates instead of becoming a token miss`() =
        runBlocking {
            assumeTrue(isLinuxOperatingSystem())
            val fixture = fakeBrowser()
            try {
                val provider =
                    ChromiumBotGuardPoTokenProvider(
                        browserFinder = { fixture.browser },
                        tempRoot = fixture.root,
                        coldStartBudgetMillis = 10_000L,
                    )

                val mint = async { provider.mint("video-id", "session-id") }
                delay(500L)
                mint.cancel()

                var cancelled = false
                try {
                    mint.await()
                } catch (_: CancellationException) {
                    cancelled = true
                } catch (error: Throwable) {
                    throw AssertionError("Caller cancellation surfaced as ${error.javaClass.simpleName}", error)
                }

                assertTrue("Mint swallowed caller cancellation and reported a token miss", cancelled)
            } finally {
                deleteRecursively(fixture.root)
            }
        }

    private fun challengeArray(
        program: String,
        globalName: String,
    ): JSONArray =
        JSONArray()
            .put(JSONArray())
            .put(JSONArray().put("var interpreter = true;"))
            .put(JSONArray().put("https://www.youtube.com/interpreter.js"))
            .put(JSONObject.NULL)
            .put(program)
            .put(globalName)

    /** Applies the inverse of the provider's `+97` descramble step. */
    private fun scramble(value: String): String {
        val shifted =
            value
                .toByteArray(StandardCharsets.UTF_8)
                .map { (it.toInt() - 97).toByte() }
                .toByteArray()
        return Base64
            .getEncoder()
            .encodeToString(shifted)
            .replace('+', '-')
            .replace('/', '_')
    }

    private fun fakeBrowser(): FakeBrowser {
        val root = Files.createTempDirectory("archivetune-botguard-fake-browser-")
        val browser = root.resolve("fake-browser")
        // Never writes DevToolsActivePort, so the provider stays in its startup wait until the
        // provider budget (or the caller) cancels it. No network or real Chromium is involved.
        Files.writeString(browser, "#!/bin/sh\nsleep 30\n")
        check(browser.toFile().setExecutable(true)) { "Could not make the fake browser executable" }
        return FakeBrowser(root = root, browser = browser)
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private data class FakeBrowser(
        val root: Path,
        val browser: Path,
    )
}

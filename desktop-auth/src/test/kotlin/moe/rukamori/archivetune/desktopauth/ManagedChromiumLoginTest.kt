package moe.rukamori.archivetune.desktopauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class ManagedChromiumLoginTest {
    @Test
    fun `cdp cookie extraction keeps only YouTube applicable cookies`() {
        val cookies =
            JSONArray(
                """[
                  {"name":"SAPISID","value":"google-value","domain":".google.com","path":"/","expires":1900000000},
                  {"name":"SAPISID","value":"youtube-value","domain":".youtube.com","path":"/","expires":1900000000},
                  {"name":"PREF","value":"pref","domain":"music.youtube.com","path":"/","expires":1900000100},
                  {"name":"OTHER","value":"blocked","domain":"example.com","path":"/","expires":1900000200}
                ]""",
            )

        val session = sessionFromYouTubeCookies(cookies)
        assertNotNull(session)

        assertEquals("SAPISID=youtube-value; PREF=pref", session!!.cookieHeader)
        assertEquals(1_900_000_000_000L, session.expiresAtEpochMillis)
        assertFalse(session.cookieHeader.contains("google-value"))
        assertFalse(session.cookieHeader.contains("blocked"))
    }

    @Test
    fun `login browser disables sync without remote debugging`() {
        val browser = Paths.get("C:\\Browser\\msedge.exe")
        val profile = Paths.get("C:\\Temp\\archivetune-login-test")
        val command = managedLoginBrowserCommand(browser, profile)

        assertEquals(browser.toString(), command.first())
        assertTrue(command.contains("--user-data-dir=$profile"))
        assertTrue(command.contains("--disable-sync"))
        assertFalse(command.contains("--disable-features=EnableBoundSessionCredentials"))
        assertFalse(command.any { it.startsWith("--remote-debugging") })
        assertFalse(command.any { it.contains("Microsoft\\Edge\\User Data") })
    }

    @Test
    fun `cookie reader reuses isolated profile with local debugging only after login`() {
        val browser = Paths.get("C:\\Browser\\msedge.exe")
        val profile = Paths.get("C:\\Temp\\archivetune-login-test")
        val command = managedExtractionBrowserCommand(browser, profile)

        assertEquals(browser.toString(), command.first())
        assertTrue(command.contains("--user-data-dir=$profile"))
        assertTrue(command.contains("--disable-sync"))
        assertTrue(command.contains("--headless=new"))
        assertTrue(command.contains("--remote-debugging-port=0"))
        assertTrue(command.contains("--remote-debugging-address=127.0.0.1"))
        assertFalse(command.contains("--disable-features=EnableBoundSessionCredentials"))
        assertEquals("about:blank", command.last())
    }

    @Test
    fun `managed login fails promptly when its browser process exits early`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = false)

        val failure = try {
            awaitManagedLoginCompletion(completion, process, Duration.ofMinutes(10), pollDelayMillis = 5L)
            null
        } catch (expected: IllegalStateException) {
            expected
        }

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("sign-in browser was closed"))
        assertTrue(process.aliveChecks.get() <= 2)
    }

    @Test
    fun `successful confirmation wins when it happens before managed browser exit`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        completion.complete(Unit)
        process.alive = false

        awaitManagedLoginCompletion(completion, process, Duration.ofMinutes(10), pollDelayMillis = 5L)

        assertEquals(0, process.aliveChecks.get())
    }

    @Test
    fun `cancelling login wait stops process polling`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        val waiter = launch {
            awaitManagedLoginCompletion(completion, process, Duration.ofMinutes(10), pollDelayMillis = 5L)
        }
        while (process.aliveChecks.get() == 0) delay(1L)

        waiter.cancelAndJoin()
        val checksAfterCancellation = process.aliveChecks.get()
        delay(20L)

        assertEquals(checksAfterCancellation, process.aliveChecks.get())
    }

    @Test
    fun `unrelated browser exit cannot complete or fail managed login`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val managed = FakeProcess(alive = true)
        val unrelated = FakeProcess(alive = false)
        val waiter = async {
            awaitManagedLoginCompletion(completion, managed, Duration.ofSeconds(2), pollDelayMillis = 5L)
        }
        delay(20L)

        assertFalse(waiter.isCompleted)
        assertEquals(0, unrelated.aliveChecks.get())
        completion.complete(Unit)
        waiter.await()
    }

    @Test
    fun `managed process exit wins if confirmation has not happened yet`() = runBlocking {
        val completion = CompletableDeferred<Unit>()
        val process = FakeProcess(alive = true)
        process.alive = false

        val failure = try {
            awaitManagedLoginCompletion(completion, process, Duration.ofMinutes(10), pollDelayMillis = 5L)
            null
        } catch (expected: IllegalStateException) {
            expected
        }

        assertNotNull(failure)
        assertTrue(completion.complete(Unit))
    }

    private class FakeProcess(alive: Boolean) : Process() {
        @Volatile var alive: Boolean = alive
        val aliveChecks = AtomicInteger(0)
        private val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int {
            while (alive) Thread.sleep(1L)
            return 0
        }
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 0
        }
        override fun destroy() { alive = false }
        override fun isAlive(): Boolean {
            aliveChecks.incrementAndGet()
            return alive
        }
        override fun destroyForcibly(): Process = apply { alive = false }
    }
}

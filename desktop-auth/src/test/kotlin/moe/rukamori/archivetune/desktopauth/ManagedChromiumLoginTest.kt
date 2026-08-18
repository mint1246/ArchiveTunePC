package moe.rukamori.archivetune.desktopauth

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

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
    fun `managed browser command is isolated and disables bound Google sessions`() {
        val browser = Paths.get("C:\\Browser\\msedge.exe")
        val profile = Paths.get("C:\\Temp\\archivetune-login-test")
        val command = managedBrowserCommand(browser, profile)

        assertEquals(browser.toString(), command.first())
        assertTrue(command.contains("--user-data-dir=$profile"))
        assertTrue(command.contains("--remote-debugging-port=0"))
        assertTrue(command.contains("--remote-debugging-address=127.0.0.1"))
        assertTrue(command.contains("--disable-features=EnableBoundSessionCredentials"))
        assertFalse(command.any { it.contains("Microsoft\\Edge\\User Data") })
    }
}

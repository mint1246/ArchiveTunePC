package moe.rukamori.archivetune.desktopauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCookieJsonImporterTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `array export filters domains and deterministically keeps most specific live cookie`() {
        val result =
            YouTubeCookieJsonImporter.parse(
                """[
                  {"name":"SAPISID","value":"google-value","domain":".google.com","path":"/"},
                  {"name":"SAPISID","value":"youtube-value","domain":".youtube.com","path":"/","expirationDate":1900000000},
                  {"name":"PREF","value":"pref","domain":"music.youtube.com","path":"/"},
                  {"name":"tracking","value":"blocked","domain":"example.com","path":"/"}
                ]""",
                now,
            )

        val parsed = (result as YouTubeCookieJsonImportResult.Ready).parsed
        assertEquals("PREF=pref; SAPISID=youtube-value", parsed.cookieHeader)
        assertEquals(1_900_000_000_000L, parsed.expiresAtEpochMillis)
        assertEquals(2, parsed.summary.acceptedCookieCount)
        assertEquals(listOf(".music.youtube.com", ".youtube.com"), parsed.summary.domains)
    }

    @Test
    fun `wrapper and simple name value exports are both supported`() {
        val wrapped =
            YouTubeCookieJsonImporter.inspect(
                """{"cookies":[{"name":"SAPISID","value":"value","domain":"youtube.com","path":"/"}]}""",
                now,
            )
        val simple = YouTubeCookieJsonImporter.inspect("""{"SAPISID":"value","PREF":"x"}""", now)

        assertTrue(wrapped is YouTubeCookieJsonInspection.Ready)
        assertTrue(simple is YouTubeCookieJsonInspection.Ready)
        assertEquals(listOf(".music.youtube.com"), (simple as YouTubeCookieJsonInspection.Ready).summary.domains)
    }

    @Test
    fun `google domain login cookies are not sent to YouTube`() {
        val result =
            YouTubeCookieJsonImporter.parse(
                """[{"name":"SAPISID","value":"google-only","domain":".google.com","path":"/","expirationDate":1900000000}]""",
                now,
            )

        assertEquals(YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.NO_SUPPORTED_COOKIES), result)
    }

    @Test
    fun `unrelated YouTube hosts and paths cannot replace Music login cookies`() {
        val result = YouTubeCookieJsonImporter.parse(
            """[
              {"name":"SAPISID","value":"music-session","domain":"music.youtube.com","hostOnly":true,"path":"/"},
              {"name":"SAPISID","value":"parent-domain-session","domain":".youtube.com","path":"/"},
              {"name":"SAPISID","value":"www-session","domain":"www.youtube.com","path":"/"},
              {"name":"SAPISID","value":"other-host","domain":"studio.youtube.com","path":"/"},
              {"name":"SAPISID","value":"other-path","domain":"music.youtube.com","path":"/account"},
              {"name":"SID","value":"youtube-host-only","domain":"youtube.com","hostOnly":true,"path":"/"},
              {"name":"SSID","value":"shared-session","domain":".youtube.com","hostOnly":false,"path":"/"}
            ]""",
            now,
        )

        val parsed = (result as YouTubeCookieJsonImportResult.Ready).parsed
        assertEquals("SAPISID=music-session; SSID=shared-session", parsed.cookieHeader)
    }

    @Test
    fun `host-only root domain cookies cannot authenticate Music`() {
        val result = YouTubeCookieJsonImporter.parse(
            """[{"name":"SAPISID","value":"root-host-only","domain":"youtube.com","hostOnly":true,"path":"/"}]""",
            now,
        )

        assertEquals(YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.NO_SUPPORTED_COOKIES), result)
    }

    @Test
    fun `partitioned cookies cannot replace the top-level session`() {
        val result = YouTubeCookieJsonImporter.parse(
            """[
              {"name":"SAPISID","value":"top-level","domain":".youtube.com","path":"/"},
              {"name":"SAPISID","value":"partitioned","domain":"music.youtube.com","path":"/","partitionKey":{"topLevelSite":"https://example.com"}}
            ]""",
            now,
        )

        assertEquals("SAPISID=top-level", (result as YouTubeCookieJsonImportResult.Ready).parsed.cookieHeader)
    }

    @Test
    fun `expired and unrelated cookies cannot create a session`() {
        val result =
            YouTubeCookieJsonImporter.parse(
                """[
                  {"name":"SAPISID","value":"expired","domain":"youtube.com","expirationDate":1000},
                  {"name":"SAPISID","value":"other","domain":"evil.example"}
                ]""",
                now,
            )

        assertEquals(YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.NO_SUPPORTED_COOKIES), result)
    }

    @Test
    fun `header injection and malformed or oversized JSON are rejected`() {
        val injected = YouTubeCookieJsonImporter.parse("""[{"name":"SAPISID","value":"good\r\nInjected: yes","domain":"youtube.com"}]""", now)
        val malformed = YouTubeCookieJsonImporter.parse("{" , now)
        val oversized = YouTubeCookieJsonImporter.parse(" ".repeat(1_000_001), now)

        assertEquals(YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.UNSAFE_COOKIE_VALUE), injected)
        assertEquals(YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.MALFORMED_JSON), malformed)
        assertEquals(YouTubeCookieJsonImportResult.Rejected(YouTubeCookieJsonFailure.TOO_LARGE), oversized)
    }

    @Test
    fun `safe inspection and parsed representation never disclose cookie values`() {
        val result = YouTubeCookieJsonImporter.parse("""[{"name":"SAPISID","value":"very-secret-value","domain":"youtube.com"}]""", now)
        val parsed = (result as YouTubeCookieJsonImportResult.Ready).parsed

        assertFalse(parsed.toString().contains("very-secret-value"))
        assertFalse(parsed.summary.toString().contains("SAPISID"))
    }
}

package moe.rukamori.archivetune.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JvmLyricsClientTest {
    @Test
    fun fallsBackToNextProviderAndDetectsSyncedLyrics() =
        runBlocking {
            val client =
                JvmLyricsClient(
                    listOf(
                        provider("first") { throw IllegalStateException("offline") },
                        provider("second") { "[00:01.00]Hello" },
                    ),
                )

            val document = client.getLyrics(LyricsRequest("Song", "Artist")).getOrThrow()

            assertEquals("second", document.providerId)
            assertEquals(LyricsFormat.LRC, document.format)
            assertTrue(document.synchronized)
        }

    @Test
    fun allLyricsDeduplicatesEquivalentText() =
        runBlocking {
            val client =
                JvmLyricsClient(
                    listOf(
                        provider("first") { "Line one\nLine two" },
                        provider("second") { " Line one \n\n Line two " },
                    ),
                )

            assertEquals(1, client.getAllLyrics(LyricsRequest("Song", "Artist")).size)
        }

    @Test
    fun detectsTtml() {
        assertEquals(LyricsFormat.TTML, detectLyricsFormat("<?xml version=\"1.0\"?><tt></tt>"))
        assertEquals(LyricsFormat.PLAIN, detectLyricsFormat("Just a line"))
    }

    @Test
    fun normalizesFeatureAndOfficialReleaseSuffixesBeforeFallingBackToDisplayTitle() =
        runBlocking {
            val requestedTitles = mutableListOf<String>()
            val client = JvmLyricsClient(listOf(provider("first") { request ->
                requestedTitles += request.title
                if (request.title == "Lost In The World") "Lyrics" else ""
            }))

            client.getLyrics(LyricsRequest("Lost In The World (feat. Bon Iver) [Official Video]", "Kanye West")).getOrThrow()

            assertEquals(listOf("Lost In The World"), requestedTitles)
        }

    @Test
    fun distinguishesProviderFailureFromAnHonestNoResult() = runBlocking {
        val noResult = JvmLyricsClient(listOf(provider("empty") { "" }))
            .getLyrics(LyricsRequest("Song", "Artist")).exceptionOrNull() as LyricsUnavailableException
        assertTrue(!noResult.hasProviderFailures)

        val failed = JvmLyricsClient(listOf(provider("offline") { throw IllegalStateException("offline") }))
            .getLyrics(LyricsRequest("Song", "Artist")).exceptionOrNull() as LyricsUnavailableException
        assertTrue(failed.hasProviderFailures)
    }

    @Test
    fun hangingProviderTimesOutAndIsReportedAsAProviderFailure() = runBlocking {
        val client = JvmLyricsClient(
            providers = listOf(provider("hanging") { delay(Long.MAX_VALUE); "unreachable" }),
            providerTimeoutMillis = 100,
            overallTimeoutMillis = 500,
        )

        val failure = client.getLyrics(LyricsRequest("Song", "Artist")).exceptionOrNull() as LyricsUnavailableException

        assertTrue(failure.hasProviderFailures)
        assertTrue(failure.failures.single().cause is LyricsProviderTimeoutException)
    }

    private fun provider(
        id: String,
        block: suspend (LyricsRequest) -> String,
    ) = NamedLyricsProvider(id, id, JvmLyricsProvider(block))
}

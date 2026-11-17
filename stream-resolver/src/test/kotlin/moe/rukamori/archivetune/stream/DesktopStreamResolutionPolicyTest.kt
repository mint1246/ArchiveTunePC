/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class DesktopStreamResolutionPolicyTest {
    private val directFormat =
        PlayerResponse.StreamingData.Format(
            itag = 251,
            url = "https://media.example/audio?expire=9999999999",
            mimeType = "audio/webm",
            bitrate = 128000,
            width = null,
            height = null,
            contentLength = 8192,
            quality = "audio",
            fps = null,
            qualityLabel = null,
            averageBitrate = 128000,
            audioQuality = null,
            approxDurationMs = "10000",
            audioSampleRate = 48000,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )

    @Test
    fun `shipped policy is hybrid with explicit upstream and legacy controls`() {
        assertEquals(DesktopStreamResolutionMode.HYBRID, configuredDesktopStreamResolutionMode(null))
        assertEquals(DesktopStreamResolutionMode.HYBRID, configuredDesktopStreamResolutionMode(""))
        assertEquals(DesktopStreamResolutionMode.HYBRID, configuredDesktopStreamResolutionMode("  hybrid "))
        assertEquals(DesktopStreamResolutionMode.YT_DLP, configuredDesktopStreamResolutionMode("ytdlp"))
        assertEquals(DesktopStreamResolutionMode.LEGACY, configuredDesktopStreamResolutionMode("legacy"))
        assertFailsWith<IllegalArgumentException> {
            configuredDesktopStreamResolutionMode("unknown")
        }
    }

    @Test
    fun `signed in highest quality goes upstream to retain account formats`() {
        val auth = PlaybackAuthState(cookie = "SAPISID=fixture").normalized()
        assertFalse(allowsDirectAudioFastPath(StreamAudioQuality.HIGHEST, auth))
        assertTrue(allowsDirectAudioFastPath(StreamAudioQuality.HIGH, auth))
        assertTrue(allowsDirectAudioFastPath(StreamAudioQuality.LOW, auth))
        assertTrue(allowsDirectAudioFastPath(StreamAudioQuality.AUTO, auth))
        assertTrue(allowsDirectAudioFastPath(StreamAudioQuality.HIGHEST, PlaybackAuthState()))
    }

    @Test
    fun `direct fast path never deciphers signatures or throttling challenges`() {
        assertEquals(directFormat.url, directAudioFastPathUrl(directFormat))
        for (url in listOf(
            "https://media.example/audio?n=challenge",
            "https://media.example/audio?n=",
            "https://media.example/audio?%6e=challenge",
            "https://media.example/audio?expire=1&n=challenge",
            "file:///tmp/audio",
            "https://user:secret@media.example/audio",
        )) {
            assertNull(directAudioFastPathUrl(directFormat.copy(url = url)), "Expected null for $url")
        }
        assertNull(directAudioFastPathUrl(directFormat.copy(signatureCipher = "s=challenge")))
        assertNull(directAudioFastPathUrl(directFormat.copy(cipher = "s=challenge")))
        assertNull(directAudioFastPathUrl(directFormat.copy(mimeType = "video/mp4", width = 320)))
        assertNull(directAudioFastPathUrl(directFormat.copy(url = null)))
    }

    @Test
    fun `explicit modes invoke only their selected implementation`() =
        runBlocking {
            assertEquals(
                "native",
                resolveDesktopStream(
                    DesktopStreamResolutionMode.LEGACY,
                    native = { "native" },
                    upstream = { error("Unexpected subprocess") },
                ),
            )
            assertEquals(
                "upstream",
                resolveDesktopStream(
                    DesktopStreamResolutionMode.YT_DLP,
                    native = { error("Unexpected native call") },
                    upstream = { "upstream" },
                ),
            )
        }

    @Test
    fun `successful fast path starts no fallback work`() =
        runBlocking {
            assertEquals(
                "native URL and headers",
                resolveDesktopStream(
                    DesktopStreamResolutionMode.HYBRID,
                    native = { "native URL and headers" },
                    upstream = { error("Unexpected subprocess") },
                ),
            )
        }

    @Test
    fun `hybrid falls back to upstream once on native failure`() =
        runBlocking {
            var calls = 0
            val result =
                resolveDesktopStream(
                    DesktopStreamResolutionMode.HYBRID,
                    native = { throw IOException("native failure") },
                    upstream = {
                        calls++
                        "upstream URL and headers"
                    },
                )
            assertEquals("upstream URL and headers", result)
            assertEquals(1, calls)
        }

    @Test
    fun `timeout finishes native cleanup before starting the fallback`() =
        runBlocking {
            var nativeStopped = false
            val result =
                resolveDesktopStream(
                    DesktopStreamResolutionMode.HYBRID,
                    nativeTimeoutMillis = 20,
                    native = {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                delay(10)
                                nativeStopped = true
                            }
                        }
                    },
                    upstream = {
                        assertTrue(nativeStopped)
                        "upstream"
                    },
                )
            assertEquals("upstream", result)
        }

    @Test
    fun `caller cancellation never starts an upstream extraction`() =
        runBlocking {
            var upstreamStarted = false
            try {
                withTimeout(20) {
                    resolveDesktopStream(
                        DesktopStreamResolutionMode.HYBRID,
                        native = { awaitCancellation() },
                        upstream = {
                            upstreamStarted = true
                            "unexpected"
                        },
                    )
                }
                fail("Expected caller cancellation")
            } catch (_: CancellationException) {
                // expected
            }
            assertFalse(upstreamStarted)
        }

    @Test
    fun `upstream failure does not retain native secrets`() =
        runBlocking {
            val upstreamFailure = YtDlpException(YtDlpFailure.AUTH_REQUIRED)
            try {
                resolveDesktopStream(
                    DesktopStreamResolutionMode.HYBRID,
                    native = { throw IOException("signed-url-cookie-marker") },
                    upstream = { throw upstreamFailure },
                )
                fail("Expected upstream failure")
            } catch (failure: YtDlpException) {
                assertSame(upstreamFailure, failure)
                assertEquals(0, failure.suppressedExceptions.size)
                assertFalse(failure.stackTraceToString().contains("signed-url-cookie-marker"))
            }
        }
}

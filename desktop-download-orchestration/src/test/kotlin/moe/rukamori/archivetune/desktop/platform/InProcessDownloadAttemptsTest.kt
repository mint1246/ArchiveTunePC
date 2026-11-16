package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.downloads.DownloadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InProcessDownloadAttemptsTest {
    @Test
    fun `retry re-resolves credentials after an expired stream attempt`() = runBlocking {
        val attempts = InProcessDownloadAttempts()
        val spec = retryableSpec()
        val enqueued = mutableListOf<DownloadRequest>()
        var resolves = 0

        suspend fun resolve(ignored: RetryableDownloadSpec): DownloadRequest {
            require(ignored == spec)
            resolves++
            return if (resolves == 1) {
                request(
                    spec = spec,
                    url = "https://media.invalid/audio?expire=100",
                    token = "expired-token",
                )
            } else {
                request(
                    spec = spec,
                    url = "https://media.invalid/audio?expire=200",
                    token = "fresh-token",
                )
            }
        }

        val firstId = attempts.enqueueFresh(spec, ::resolve) { request ->
            enqueued += request
            "attempt-${enqueued.size}"
        }.getOrThrow()

        // Model the first signed request later receiving a 403 because its lease expired. A user
        // retry must not replay that request; it must ask the resolver for new credentials.
        val secondId = attempts.retry(firstId, ::resolve) { request ->
            enqueued += request
            "attempt-${enqueued.size}"
        }?.getOrThrow()

        assertEquals(2, resolves)
        assertEquals(2, enqueued.size)
        assertNotEquals(enqueued[0].url, enqueued[1].url)
        assertNotEquals(enqueued[0].headers, enqueued[1].headers)
        assertEquals("https://media.invalid/audio?expire=200", enqueued[1].url)
        assertEquals("fresh-token", enqueued[1].headers["Authorization"])
        assertEquals("attempt-2", secondId)
    }

    @Test
    fun `successful retry retargets state and stale failed id cannot retry again`() = runBlocking {
        val attempts = InProcessDownloadAttempts()
        val spec = retryableSpec()
        var resolves = 0
        var enqueues = 0

        suspend fun resolve(value: RetryableDownloadSpec): DownloadRequest {
            resolves++
            return request(value, "https://media.invalid/$resolves", "token-$resolves")
        }
        fun enqueue(request: DownloadRequest): String {
            require(request.mediaId == spec.videoId)
            enqueues++
            return "attempt-$enqueues"
        }

        val firstId = attempts.enqueueFresh(spec, ::resolve, ::enqueue).getOrThrow()
        val secondId = attempts.retry(firstId, ::resolve, ::enqueue)?.getOrThrow() ?: error("retry state missing")

        assertEquals("attempt-2", secondId)
        assertNull(attempts.retry(firstId, ::resolve, ::enqueue))
        assertEquals(2, resolves)
        assertEquals(2, enqueues)

        val thirdId = attempts.retry(secondId, ::resolve, ::enqueue)?.getOrThrow()
        assertEquals("attempt-3", thirdId)
        assertNull(attempts.retry(secondId, ::resolve, ::enqueue))
        assertEquals(3, resolves)
        assertEquals(3, enqueues)
    }

    @Test
    fun `failed retry keeps original id retryable`() = runBlocking {
        val attempts = InProcessDownloadAttempts()
        val spec = retryableSpec()
        var enqueues = 0

        val firstId = attempts.enqueueFresh(
            spec,
            resolve = {
                request(it, "https://media.invalid/fresh", "fresh-token")
            },
            enqueue = {
                enqueues++
                "attempt-$enqueues"
            },
        ).getOrThrow()

        assertTrue(attempts.retry(firstId, { error("temporary resolver failure") }, { "unexpected" })?.isFailure == true)

        val recovered = attempts.retry(
            firstId,
            { request(it, "https://media.invalid/recovered", "recovered-token") },
            {
                enqueues++
                "attempt-$enqueues"
            },
        )?.getOrThrow()
        assertEquals("attempt-2", recovered)
    }

    @Test
    fun `restored download has no in-memory retry spec`() = runBlocking {
        val attempts = InProcessDownloadAttempts()
        var resolves = 0

        val retried = attempts.retry(
            id = "journal-only-id",
            resolve = {
                resolves++
                request(it, "https://media.invalid/fresh", "fresh-token")
            },
            enqueue = { "should-not-enqueue" },
        )

        assertNull(retried)
        assertEquals(0, resolves)
    }

    @Test
    fun `failed fresh resolution never enqueues or creates a retry attempt`() = runBlocking {
        val attempts = InProcessDownloadAttempts()
        val spec = retryableSpec()
        var enqueues = 0

        val failed = attempts.enqueueFresh(
            spec = spec,
            resolve = { error("resolver unavailable") },
            enqueue = {
                enqueues++
                "unexpected"
            },
        )

        assertTrue(failed.isFailure)
        assertEquals(0, enqueues)
        assertNull(
            attempts.retry(
                id = "unexpected",
                resolve = { request(it, "https://media.invalid/fresh", "fresh-token") },
                enqueue = { "unexpected-2" },
            ),
        )
    }

    private fun retryableSpec() = RetryableDownloadSpec(
        videoId = "video-id",
        title = "Song",
        artistNames = listOf("Artist"),
        albumTitle = "Album",
        albumId = "album-id",
        artworkUrl = "https://images.invalid/art.jpg",
        durationMs = 123_000L,
        explicit = true,
    )

    private fun request(spec: RetryableDownloadSpec, url: String, token: String) = DownloadRequest(
        url = url,
        headers = mapOf("Authorization" to token),
        title = spec.title,
        artist = spec.artistNames.joinToString(),
        mediaId = spec.videoId,
        albumTitle = spec.albumTitle,
        albumId = spec.albumId,
        artworkUrl = spec.artworkUrl,
        durationMs = spec.durationMs,
        explicit = spec.explicit,
    )
}
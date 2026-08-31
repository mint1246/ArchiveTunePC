package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackQueueEntry
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NextTrackPrefetcherTest {
    @Test
    fun `playing queue resolves next exactly once across position ticks`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 1_000_000L)
        resolver.enqueueSuccess(freshSource("next", resolver.nowMs))
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)
        val base = snapshot(positionMs = 10_000L, durationMs = 100_000L)

        repeat(200) { tick ->
            prefetcher.onSnapshot(base.copy(positionMs = 10_000L + tick * 50L))
            runCurrent()
        }

        assertEquals(1, resolver.backendCalls)
        prefetcher.close()
    }

    @Test
    fun `next song is prefetched immediately instead of waiting for end of track`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 2_000_000L)
        resolver.enqueueSuccess(freshSource("next", resolver.nowMs))
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)
        val far = snapshot(positionMs = 50_000L, durationMs = 100_000L)

        prefetcher.onSnapshot(far)
        runCurrent()
        assertEquals(1, resolver.backendCalls)
        prefetcher.close()
    }

    @Test
    fun `paused queue waits until playback starts before prefetching`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 2_500_000L)
        resolver.enqueueSuccess(freshSource("next", resolver.nowMs))
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)
        val paused = snapshot(positionMs = 20_000L, durationMs = 100_000L).copy(playWhenReady = false)

        prefetcher.onSnapshot(paused)
        runCurrent()
        assertEquals(0, resolver.backendCalls)

        prefetcher.onSnapshot(paused.copy(playWhenReady = true))
        runCurrent()
        assertEquals(1, resolver.backendCalls)
        prefetcher.close()
    }

    @Test
    fun `only the immediate next occurrence is prefetched`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 2_750_000L)
        resolver.enqueueSuccess(freshSource("next", resolver.nowMs))
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)
        val current = deferredItem("current")
        val next = deferredItem("next")
        val later = deferredItem("later")
        val snapshot = PlaybackSnapshot(
            queue = listOf(current, next, later),
            queueEntries = listOf(
                PlaybackQueueEntry(id = 1L, item = current, logicalIndex = 0, isCurrent = true),
                PlaybackQueueEntry(id = 2L, item = next, logicalIndex = 1, isCurrent = false),
                PlaybackQueueEntry(id = 3L, item = later, logicalIndex = 2, isCurrent = false),
            ),
            currentIndex = 0,
            status = PlaybackStatus.READY,
            playWhenReady = true,
            positionMs = 1_000L,
            durationMs = 100_000L,
            hasNextItem = true,
        )

        prefetcher.onSnapshot(snapshot)
        runCurrent()

        assertEquals(1, resolver.backendCalls)
        assertTrue("next" in resolver.cachedKeys())
        assertFalse("later" in resolver.cachedKeys())
        prefetcher.close()
    }

    @Test
    fun `next local file never invokes remote resolver`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 3_000_000L)
        val current = deferredItem("current")
        val localNext = PlaybackItem(
            metadata = PlaybackMetadata("local", "local"),
            source = PlaybackSource.LocalFile("C:/music/local.m4a"),
        )
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)

        prefetcher.onSnapshot(snapshot(current = current, next = localNext, positionMs = 90_000L, durationMs = 100_000L))
        runCurrent()

        assertEquals(0, resolver.backendCalls)
        prefetcher.close()
    }

    @Test
    fun `queue target change invalidates stale in flight cache write`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val cached = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        val prefetcher = NextTrackPrefetcher(this) { item, cacheAllowed ->
            calls += item.metadata.id
            if (item.metadata.id == "next-a") {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
            }
            if (cacheAllowed()) cached += item.metadata.id
            Result.success(ResolvedPlaybackSource("https://example.invalid/${item.metadata.id}", expiresAtEpochMs = Long.MAX_VALUE))
        }

        prefetcher.onSnapshot(snapshot(next = deferredItem("next-a"), positionMs = 90_000L, durationMs = 100_000L))
        runCurrent()
        assertTrue(firstStarted.isCompleted)

        prefetcher.onSnapshot(snapshot(next = deferredItem("next-b"), nextOccurrenceId = 3L, positionMs = 90_100L, durationMs = 100_000L))
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("next-a", "next-b"), calls)
        assertFalse("next-a" in cached)
        assertTrue("next-b" in cached)
        prefetcher.close()
    }

    @Test
    fun `failed prefetch stays silent and normal transition retries`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 4_000_000L)
        resolver.enqueueFailure(IllegalStateException("prefetch failed"))
        resolver.enqueueSuccess(freshSource("next", resolver.nowMs))
        val next = deferredItem("next")
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)

        prefetcher.onSnapshot(snapshot(next = next, positionMs = 90_000L, durationMs = 100_000L))
        runCurrent()
        assertEquals(1, resolver.backendCalls)

        val transitioned = resolver.resolveNormally(next).getOrThrow()
        assertEquals("https://example.invalid/next", transitioned.uri)
        assertEquals(2, resolver.backendCalls)
        prefetcher.close()
    }

    @Test
    fun `fresh prefetched source is reused by normal resolution`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 5_000_000L)
        resolver.enqueueSuccess(freshSource("next", resolver.nowMs))
        val next = deferredItem("next")
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)

        prefetcher.onSnapshot(snapshot(next = next, positionMs = 90_000L, durationMs = 100_000L))
        runCurrent()
        val transitioned = resolver.resolveNormally(next).getOrThrow()

        assertEquals("https://example.invalid/next", transitioned.uri)
        assertEquals(1, resolver.backendCalls)
        prefetcher.close()
    }

    @Test
    fun `prefetched source inside expiry safety lead is not reused`() = runTest {
        val resolver = FakeCachingResolver(nowMs = 6_000_000L, expiryLeadMs = 60_000L)
        resolver.enqueueSuccess(
            ResolvedPlaybackSource(
                uri = "https://example.invalid/next-too-close",
                expiresAtEpochMs = resolver.nowMs + 30_000L,
            ),
        )
        resolver.enqueueSuccess(freshSource("next", resolver.nowMs))
        val next = deferredItem("next")
        val prefetcher = NextTrackPrefetcher(this, resolve = resolver::prefetch)

        prefetcher.onSnapshot(snapshot(next = next, positionMs = 90_000L, durationMs = 100_000L))
        runCurrent()
        val transitioned = resolver.resolveNormally(next).getOrThrow()

        assertEquals("https://example.invalid/next", transitioned.uri)
        assertEquals(2, resolver.backendCalls)
        prefetcher.close()
    }

    private fun snapshot(
        current: PlaybackItem = deferredItem("current"),
        next: PlaybackItem = deferredItem("next"),
        nextOccurrenceId: Long = 2L,
        positionMs: Long,
        durationMs: Long,
    ): PlaybackSnapshot =
        PlaybackSnapshot(
            queue = listOf(current, next),
            queueEntries = listOf(
                PlaybackQueueEntry(id = 1L, item = current, logicalIndex = 0, isCurrent = true),
                PlaybackQueueEntry(id = nextOccurrenceId, item = next, logicalIndex = 1, isCurrent = false),
            ),
            currentIndex = 0,
            status = PlaybackStatus.READY,
            playWhenReady = true,
            positionMs = positionMs,
            durationMs = durationMs,
            hasNextItem = true,
        )

    private fun deferredItem(id: String): PlaybackItem =
        PlaybackItem(
            metadata = PlaybackMetadata(id = id, title = id),
            source = PlaybackSource.Deferred(id),
        )

    private fun freshSource(id: String, nowMs: Long): ResolvedPlaybackSource =
        ResolvedPlaybackSource(
            uri = "https://example.invalid/$id",
            expiresAtEpochMs = nowMs + 5 * 60_000L,
        )

    private class FakeCachingResolver(
        var nowMs: Long,
        private val expiryLeadMs: Long = 60_000L,
    ) {
        private val queuedBackendResults = ArrayDeque<Result<ResolvedPlaybackSource>>()
        private val cache = mutableMapOf<String, ResolvedPlaybackSource>()
        var backendCalls = 0
            private set

        fun enqueueSuccess(source: ResolvedPlaybackSource) {
            queuedBackendResults += Result.success(source)
        }

        fun enqueueFailure(error: Throwable) {
            queuedBackendResults += Result.failure(error)
        }

        suspend fun prefetch(
            item: PlaybackItem,
            cacheAllowed: () -> Boolean,
        ): Result<ResolvedPlaybackSource> = resolve(item, cacheAllowed)

        suspend fun resolveNormally(item: PlaybackItem): Result<ResolvedPlaybackSource> = resolve(item) { true }

        private suspend fun resolve(
            item: PlaybackItem,
            cacheAllowed: () -> Boolean,
        ): Result<ResolvedPlaybackSource> {
            val key = (item.source as PlaybackSource.Deferred).key
            cache[key]?.takeIf(::isFresh)?.let { return Result.success(it) }
            cache.remove(key)
            backendCalls++
            val result = queuedBackendResults.removeFirstOrNull()
                ?: error("No fake backend result queued for $key")
            result.getOrNull()?.let { if (cacheAllowed()) cache[key] = it }
            return result
        }

        private fun isFresh(source: ResolvedPlaybackSource): Boolean =
            source.expiresAtEpochMs?.let { it > nowMs + expiryLeadMs } == true

        fun cachedKeys(): Set<String> = cache.keys.toSet()
    }
}

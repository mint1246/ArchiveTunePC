package moe.rukamori.archivetune.playback.desktop.stream

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.desktop.PlaybackQueueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompletedDownloadPlaybackResolutionTest {
    @Test
    fun `valid completed download wins without remote resolution`() = runBlocking {
        val completed = Files.createTempFile("archivetune-completed-", ".m4a")
        val item = deferredItem("song-id")
        var remoteCalls = 0

        val resolved = resolveDeferredPreferCompletedDownload(item, { completed }) {
            remoteCalls++
            Result.success(ResolvedPlaybackSource("https://example.invalid/remote"))
        }.getOrThrow()

        assertEquals(completed.toUri().toASCIIString(), resolved.uri)
        assertEquals(0, remoteCalls)
    }

    @Test
    fun `stale completed path falls back to remote exactly once`() = runBlocking {
        val missing = Files.createTempDirectory("archivetune-stale-").resolve("missing.m4a")
        val item = deferredItem("song-id")
        var remoteCalls = 0

        val resolved = resolveDeferredPreferCompletedDownload(item, { missing }) {
            remoteCalls++
            Result.success(ResolvedPlaybackSource("https://example.invalid/fresh"))
        }.getOrThrow()

        assertEquals("https://example.invalid/fresh", resolved.uri)
        assertEquals(1, remoteCalls)
    }

    @Test
    fun `persisted deferred queue item still uses completed download lookup`() = runBlocking {
        val directory = Files.createTempDirectory("archivetune-queue-")
        val completed = directory.resolve("downloaded.m4a").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val item = deferredItem("restored-id")
        val store = PlaybackQueueStore(directory.resolve("queue.json"))
        store.save(
            PlaybackSnapshot(
                queue = listOf(item),
                currentIndex = 0,
                positionMs = 12_345L,
            ),
        )
        val restored = store.load()!!.items.single()
        assertIs<PlaybackSource.Deferred>(restored.source)
        var remoteCalls = 0

        val resolved = resolveDeferredPreferCompletedDownload(restored, { mediaId ->
            completed.takeIf { mediaId == "restored-id" }
        }) {
            remoteCalls++
            Result.success(ResolvedPlaybackSource("https://example.invalid/remote"))
        }.getOrThrow()

        assertEquals(completed.toUri().toASCIIString(), resolved.uri)
        assertEquals(0, remoteCalls)
    }

    @Test
    fun `remote source can be freshly resolved again after http recovery`() = runBlocking {
        val item = deferredItem("remote-id")
        var remoteCalls = 0
        suspend fun resolveRemote(): Result<ResolvedPlaybackSource> {
            remoteCalls++
            return Result.success(
                ResolvedPlaybackSource(
                    uri = "https://example.invalid/stream-$remoteCalls",
                    headers = mapOf("X-Generation" to remoteCalls.toString()),
                ),
            )
        }

        val first = resolveDeferredPreferCompletedDownload(item, completedDownloadLookup = { null }, fallback = ::resolveRemote)
            .getOrThrow()
        // VlcjPlaybackController invokes the same deferred resolver again after the existing HTTP
        // recovery callback returns true. The local-first branch must not consume or memoize that retry.
        val retried = resolveDeferredPreferCompletedDownload(item, completedDownloadLookup = { null }, fallback = ::resolveRemote)
            .getOrThrow()

        assertEquals("https://example.invalid/stream-1", first.uri)
        assertEquals("https://example.invalid/stream-2", retried.uri)
        assertEquals("2", retried.headers["X-Generation"])
        assertEquals(2, remoteCalls)
    }

    private fun deferredItem(id: String) = PlaybackItem(
        metadata = PlaybackMetadata(id = id, title = "Test $id"),
        source = PlaybackSource.Deferred(id),
    )
}

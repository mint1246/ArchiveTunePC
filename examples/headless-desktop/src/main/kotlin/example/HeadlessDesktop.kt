package example

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.desktop.createDesktopPlaybackSession

fun main(args: Array<String>) = runBlocking {
    require(args.size == 1) { "Pass an absolute path to a local audio file" }
    val path = Path.of(args.single()).toAbsolutePath().normalize()
    val resolver = PlaybackSourceResolver { item ->
        val file = item.source as? PlaybackSource.LocalFile
            ?: return@PlaybackSourceResolver Result.failure(IllegalArgumentException("Expected a local file"))
        Result.success(ResolvedPlaybackSource(uri = Path.of(file.path).toUri().toString()))
    }
    val item = PlaybackItem(
        metadata = PlaybackMetadata(id = path.toString(), title = path.fileName.toString()),
        source = PlaybackSource.LocalFile(path.toString()),
    )
    val tempDir = Files.createTempDirectory("archivetune-headless-desktop")
    val queueFile = tempDir.resolve("persistent-queue.json")
    try {
        createDesktopPlaybackSession(
            sourceResolver = resolver,
            queueFile = queueFile,
        ).use { session ->
            session.replaceQueue(listOf(item))
            val state = withTimeout(20_000) {
                session.state.first {
                    (it.isPlaying && it.positionMs > 100L) || it.status == PlaybackStatus.ERROR
                }
            }
            check(state.isPlaying) { "Playback failed: ${state.error}" }
            println("Playing ${state.currentItem?.metadata?.title}")
        }

        createDesktopPlaybackSession(
            sourceResolver = resolver,
            queueFile = queueFile,
        ).use { restoredSession ->
            val restored = restoredSession.restorePersistedQueue(playWhenReady = false)
            check(restored) { "Expected persisted queue to restore from $queueFile" }
            val state = restoredSession.state.value
            check(state.queue.size == 1 && state.currentItem?.metadata?.id == item.metadata.id) {
                "Unexpected restored queue state: $state"
            }
            println("Restored ${state.currentItem?.metadata?.title} at ${state.positionMs}ms")
        }
    } finally {
        tempDir.toFile().deleteRecursively()
    }
}

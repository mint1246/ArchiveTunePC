package example

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
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController

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
    VlcjPlaybackController(resolver).use { player ->
        player.replaceQueue(listOf(item))
        val state = withTimeout(20_000) {
            player.state.first {
                (it.isPlaying && it.positionMs > 100L) || it.status == PlaybackStatus.ERROR
            }
        }
        check(state.isPlaying) { "Playback failed: ${state.error}" }
        println("Playing ${state.currentItem?.metadata?.title}")
    }
}

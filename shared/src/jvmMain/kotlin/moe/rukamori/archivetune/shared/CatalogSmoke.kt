package moe.rukamori.archivetune.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking

/** Optional live transport probe, never run by deterministic tests or normal application builds. */
fun main(args: Array<String>) = runBlocking {
    val http = HttpClient(CIO)
    val catalog = InnerTubeCatalog(http)
    try {
        val shelves = catalog.home()
        println("Catalog home: ${shelves.size} shelves, ${shelves.sumOf { it.tracks.size }} songs")
        shelves.forEach { println("Home shelf: ${it.title.take(80)}, ${it.items.size} items, ${it.tracks.size} songs, ${it.featuredCards.size} featured cards") }
        val query = args.getOrNull(0) ?: "music"
        val tracks = if (query.isBlank()) emptyList() else catalog.search(query)
        println("Catalog search: ${tracks.size} songs")
        val explicitId = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        val track = explicitId?.let { MusicTrack(it, "Explicit smoke-test video") }
            ?: tracks.firstOrNull() ?: error("Search returned no playable songs")
        val queue = catalog.radioQueue(track)
        check(queue.tracks.any { it.id == track.id }) { "Radio omitted the clicked track" }
        println("Catalog radio: ${queue.tracks.size} songs, clicked identity preserved=true")
        val audio = catalog.resolve(track)
        println("Catalog resolution: ${audio.mimeType}, clicked identity preserved=${audio.trackId == track.id}")
        http.prepareGet(audio.url) { header("Range", "bytes=0-4095") }.execute { response ->
            val bytes = ByteArray(4096)
            val count = response.bodyAsChannel().readAvailable(bytes, 0, bytes.size)
            check(response.status.value in 200..299 && count > 0) { "Media transport rejected the resolved source" }
            println("Media transport: HTTP ${response.status.value}, read $count bytes")
        }
    } catch (failure: CatalogException) {
        System.err.println("Catalog smoke failed: ${failure.reason}: ${failure.message}")
        throw IllegalStateException("Live catalog smoke did not pass (${failure.reason})")
    } catch (_: Exception) {
        // Ktor's raw exceptions can include the signed URL. Report only the failed stage.
        throw IllegalStateException("Live catalog smoke did not pass; no private request details logged")
    } finally {
        catalog.close()
    }
}

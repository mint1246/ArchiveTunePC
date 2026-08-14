/*
 * ArchiveTune (2026)
 * GPL-3.0-or-later
 */
package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.downloads.DesktopDownloadManager
import moe.rukamori.archivetune.downloads.DownloadRequest
import moe.rukamori.archivetune.downloads.DownloadSnapshot
import moe.rukamori.archivetune.downloads.PartialCleanupPolicy
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.models.SongItem

/** Desktop-only coordinator: resolves fresh URLs just-in-time, never persists them. */
class DesktopDownloadServices(
    private val runtime: DesktopRuntime,
    private val backend: ArchiveTuneJvmClient,
) : AutoCloseable {
    private var directory: Path = configuredDirectory()
    private var manager = newManager(directory)
    private val retryRequests = ConcurrentHashMap<String, DownloadRequest>()

    val downloads: StateFlow<List<DownloadSnapshot>> get() = manager.downloads
    val downloadDirectory: Path get() = directory

    suspend fun enqueue(song: SongItem): Result<String> = backend.resolveAudioStream(song.id).map { stream ->
        val request = DownloadRequest(
            url = stream.url,
            headers = stream.requestHeaders,
            title = song.title,
            artist = song.artists.joinToString { it.name }.ifBlank { null },
            extension = stream.mimeType.substringAfter('/').substringBefore(';').ifBlank { "m4a" },
            mediaId = song.id,
            albumTitle = song.album?.name,
            albumId = song.album?.id,
            artworkUrl = song.thumbnail,
            durationMs = song.duration?.times(1000L),
            explicit = song.explicit,
        )
        manager.enqueue(request).also { retryRequests[it] = request }
    }

    /** Retry only within this process; after restart the user must resolve a new signed stream. */
    fun retry(id: String): String? = retryRequests[id]?.let { manager.enqueue(it).also { next -> retryRequests[next] = it } }
    fun cancel(id: String): Boolean = manager.cancel(id)

    /** Folder changes are immediate when no work is active, avoiding split partial downloads. */
    fun changeDirectory(newDirectory: Path): Result<Unit> = runCatching {
        val active = downloads.value.any { it.status.name in setOf("QUEUED", "DOWNLOADING") }
        check(!active) { "Cancel or finish active downloads before changing the download folder." }
        val normalized = newDirectory.toAbsolutePath().normalize()
        Files.createDirectories(normalized)
        manager.close()
        directory = normalized
        manager = newManager(normalized)
        runtime.settings.update { it.copy(downloadLocation = normalized) }
    }

    private fun configuredDirectory(): Path = runtime.settings.snapshot().downloadLocation ?: runtime.paths.downloads
    private fun newManager(path: Path) = DesktopDownloadManager(path, maxConcurrentDownloads = 2, partialCleanupPolicy = PartialCleanupPolicy.DELETE_ON_CANCEL)
    override fun close() = manager.close()
}

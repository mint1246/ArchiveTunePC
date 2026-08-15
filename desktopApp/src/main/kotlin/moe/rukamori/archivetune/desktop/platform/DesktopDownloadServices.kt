/*
 * ArchiveTune (2026)
 * GPL-3.0-or-later
 */
package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.downloads.DesktopDownloadManager
import moe.rukamori.archivetune.downloads.DownloadRequest
import moe.rukamori.archivetune.downloads.DownloadSnapshot
import moe.rukamori.archivetune.downloads.DownloadStatus
import moe.rukamori.archivetune.downloads.PartialCleanupPolicy
import moe.rukamori.archivetune.desktop.settings.DesktopPartialCleanupPolicy
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.models.SongItem

/** Desktop-only coordinator: resolves fresh URLs just-in-time, never persists them. */
class DesktopDownloadServices(
    private val runtime: DesktopRuntime,
    private val backend: ArchiveTuneJvmClient,
) : AutoCloseable {
    @Volatile private var directory: Path = configuredDirectory()
    @Volatile private var manager = newManager(directory, runtime.settings.snapshot())
    private val retryRequests = ConcurrentHashMap<String, DownloadRequest>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableDownloads = MutableStateFlow(manager.downloads.value)
    private var downloadsJob: Job = observe(manager)
    private val enqueueMutex = Mutex()
    private val operationLock = Any()
    private var inFlightEnqueues = 0

    /** Stable across manager reconfiguration so existing UI collectors never become stale. */
    val downloads: StateFlow<List<DownloadSnapshot>> = mutableDownloads.asStateFlow()
    val downloadDirectory: Path get() = directory

    /** A completed local file, if this media id has already been downloaded. */
    fun completedFileFor(mediaId: String): Path? = manager.completedFileFor(mediaId)
    fun completedFileForDownload(id: String): Path? = manager.completedFileForDownload(id)

    suspend fun enqueue(song: SongItem): Result<String> = enqueueMutex.withLock {
        synchronized(operationLock) { inFlightEnqueues++ }
        try {
            // Download actions are idempotent: reuse active work, or the exact journal entry
            // that owns the completed local file, without resolving another signed URL.
            manager.downloads.value.lastOrNull {
                it.mediaId == song.id && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
            }?.let { return@withLock Result.success(it.id) }
            manager.completedFileFor(song.id)?.let { completedFile ->
                manager.downloads.value.firstOrNull {
                    it.mediaId == song.id &&
                        it.status == DownloadStatus.COMPLETED &&
                        directory.resolve(it.fileName) == completedFile
                }?.let { return@withLock Result.success(it.id) }
            }
            backend.resolveAudioStream(song.id).map { stream ->
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
        } finally {
            synchronized(operationLock) { inFlightEnqueues-- }
        }
    }

    /**
     * Queue every distinct song in a playlist. Resolution is deliberately sequential so a
     * large playlist does not overwhelm the authenticated backend; actual file transfers remain
     * concurrent inside [DesktopDownloadManager].
     */
    suspend fun enqueueAll(songs: List<SongItem>): DownloadBatchResult {
        val queued = mutableListOf<String>()
        val failures = mutableListOf<String>()
        songs.distinctBy(SongItem::id).forEach { song ->
            enqueue(song).onSuccess(queued::add).onFailure { failures += "${song.title}: ${it.message ?: "Could not resolve a stream"}" }
        }
        return DownloadBatchResult(songs.distinctBy(SongItem::id).size, queued, failures)
    }

    /** Retry only within this process; after restart the user must resolve a new signed stream. */
    fun retry(id: String): String? = synchronized(operationLock) {
        retryRequests[id]?.let { manager.enqueue(it).also { next -> retryRequests[next] = it } }
    }
    fun cancel(id: String): Boolean = synchronized(operationLock) { manager.cancel(id) }

    /** Folder changes are immediate when no work is active, avoiding split partial downloads. */
    fun changeDirectory(newDirectory: Path): Result<Unit> = runCatching {
        synchronized(operationLock) {
            check(inFlightEnqueues == 0) { "Wait for stream resolution to finish before changing the download folder." }
            check(!manager.hasActiveWork()) { "Cancel or finish active downloads before changing the download folder." }
            val normalized = newDirectory.toAbsolutePath().normalize()
            Files.createDirectories(normalized)
            val desiredSettings = runtime.settings.snapshot().copy(downloadLocation = normalized)
            val replacement = newManager(normalized, desiredSettings)
            try {
                runtime.settings.update { it.copy(downloadLocation = normalized) }
                replaceManager(replacement, normalized)
            } catch (failure: Exception) {
                replacement.close()
                throw failure
            }
        }
    }

    /** Applies download limits without interrupting active work. */
    fun configure(maxConcurrentDownloads: Int, cleanupPolicy: DesktopPartialCleanupPolicy): Result<Unit> = runCatching {
        synchronized(operationLock) {
            check(inFlightEnqueues == 0) { "Wait for stream resolution to finish before changing download behavior." }
            check(!manager.hasActiveWork()) { "Cancel or finish active downloads before changing download behavior." }
            val desiredSettings = runtime.settings.snapshot().copy(
                downloadConcurrency = maxConcurrentDownloads.coerceIn(1, 8),
                partialCleanupPolicy = cleanupPolicy,
            )
            val replacement = newManager(directory, desiredSettings)
            try {
                runtime.settings.update {
                    it.copy(
                        downloadConcurrency = desiredSettings.downloadConcurrency,
                        partialCleanupPolicy = cleanupPolicy,
                    )
                }
                replaceManager(replacement, directory)
            } catch (failure: Exception) {
                replacement.close()
                throw failure
            }
        }
    }

    private fun configuredDirectory(): Path = runtime.settings.snapshot().downloadLocation ?: runtime.paths.downloads
    private fun newManager(path: Path, settings: moe.rukamori.archivetune.desktop.settings.DesktopSettings) =
        DesktopDownloadManager(
            path,
            maxConcurrentDownloads = settings.downloadConcurrency,
            partialCleanupPolicy = settings.partialCleanupPolicy.toDownloadPolicy(),
        )

    private fun observe(source: DesktopDownloadManager): Job = serviceScope.launch {
        source.downloads.collect { snapshots ->
            // Cancellation is cooperative: a collector that was already handling an emission
            // can run after replaceManager() switched to a new manager. Do not let that final
            // stale emission roll the stable UI flow back to the closed manager's state.
            if (manager === source) mutableDownloads.value = snapshots
        }
    }

    private fun replaceManager(replacement: DesktopDownloadManager, replacementDirectory: Path) {
        val previous = manager
        downloadsJob.cancel()
        directory = replacementDirectory
        manager = replacement
        mutableDownloads.value = replacement.downloads.value
        downloadsJob = observe(replacement)
        previous.close()
    }

    override fun close() = synchronized(operationLock) {
        downloadsJob.cancel()
        manager.close()
        serviceScope.cancel()
    }
}

private fun DesktopPartialCleanupPolicy.toDownloadPolicy(): PartialCleanupPolicy =
    PartialCleanupPolicy.valueOf(name)

data class DownloadBatchResult(
    val requested: Int,
    val queuedIds: List<String>,
    val failures: List<String>,
) {
    val succeeded: Int get() = queuedIds.size
}

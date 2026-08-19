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
import moe.rukamori.archivetune.innertube.AudioStreamProbeHttpException
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.ResolvedAudioStream
import moe.rukamori.archivetune.innertube.probeResolvedAudioStream
import moe.rukamori.archivetune.innertube.models.SongItem

internal data class RetryableDownloadSpec(
    val videoId: String,
    val title: String,
    val artistNames: List<String>,
    val albumTitle: String?,
    val albumId: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val explicit: Boolean,
)

/** Keeps only stable song metadata in memory; signed URLs and headers never enter this store. */
internal class InProcessDownloadAttempts {
    private val retryable = ConcurrentHashMap<String, RetryableDownloadSpec>()

    suspend fun enqueueFresh(
        spec: RetryableDownloadSpec,
        resolve: suspend (RetryableDownloadSpec) -> DownloadRequest,
        enqueue: (DownloadRequest) -> String,
    ): Result<String> = runCatching {
        val request = resolve(spec)
        enqueue(request).also { retryable[it] = spec }
    }

    suspend fun retry(
        id: String,
        resolve: suspend (RetryableDownloadSpec) -> DownloadRequest,
        enqueue: (DownloadRequest) -> String,
    ): Result<String>? {
        val spec = retryable[id] ?: return null
        val result = runCatching {
            val request = resolve(spec)
            enqueue(request)
        }
        result.onSuccess { nextId ->
            // Retarget retry ownership to the fresh row. The stale failed row must not remain a
            // second entry point for spawning duplicate retries later in the same process.
            if (nextId != id) {
                retryable[nextId] = spec
                retryable.remove(id, spec)
            }
        }
        return result
    }
}

private fun SongItem.toRetryableDownloadSpec() = RetryableDownloadSpec(
    videoId = id,
    title = title,
    artistNames = artists.map { it.name },
    albumTitle = album?.name,
    albumId = album?.id,
    artworkUrl = thumbnail,
    durationMs = duration?.times(1_000L),
    explicit = explicit,
)

/** Desktop-only coordinator: resolves fresh URLs just-in-time, never persists them. */
class DesktopDownloadServices(
    private val runtime: DesktopRuntime,
    private val backend: ArchiveTuneJvmClient,
) : AutoCloseable {
    @Volatile private var directory: Path = configuredDirectory()
    @Volatile private var manager = newManager(directory, runtime.settings.snapshot())
    private val retryAttempts = InProcessDownloadAttempts()
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
            retryAttempts.enqueueFresh(
                spec = song.toRetryableDownloadSpec(),
                resolve = ::resolveRequest,
                enqueue = { manager.enqueue(it) },
            )
        } finally {
            synchronized(operationLock) { inFlightEnqueues-- }
        }
    }

    private suspend fun resolveRequest(spec: RetryableDownloadSpec): DownloadRequest {
        val stream = resolveValidatedStream(spec)
        return DownloadRequest(
            url = stream.url,
            headers = stream.requestHeaders,
            title = spec.title,
            artist = spec.artistNames.joinToString().ifBlank { null },
            extension = stream.mimeType.substringAfter('/').substringBefore(';').ifBlank { "m4a" },
            mediaId = spec.videoId,
            albumTitle = spec.albumTitle,
            albumId = spec.albumId,
            artworkUrl = spec.artworkUrl,
            durationMs = spec.durationMs,
            explicit = spec.explicit,
        )
    }

    /**
     * Validate the exact signed URL before a large transfer starts. A freshly resolved URL can be
     * rejected by the media CDN even though the player response itself succeeded; on those concrete
     * 403/404/410/416 responses, back off that client/proof generation and resolve once more.
     */
    private suspend fun resolveValidatedStream(spec: RetryableDownloadSpec): ResolvedAudioStream {
        var stream =
            backend.resolveAudioStreamWithMetadataFallback(
                videoId = spec.videoId,
                title = spec.title,
                artistNames = spec.artistNames,
                albumTitle = spec.albumTitle,
                durationHintMs = spec.durationMs,
            ).getOrThrow()

        val initialProbeFailure = probeResolvedAudioStream(stream).exceptionOrNull()
        val httpFailure = initialProbeFailure as? AudioStreamProbeHttpException
        if (httpFailure == null || httpFailure.httpStatus !in RETRYABLE_STREAM_RESPONSE_CODES) {
            if (initialProbeFailure != null) throw initialProbeFailure
            return stream
        }

        backend.prepareStreamHttpRecovery(
            videoId = stream.videoId,
            clientKey = stream.clientKey,
            httpStatusCode = httpFailure.httpStatus,
            authFingerprint = stream.authFingerprint,
            failedUrlHadPoToken = stream.url.hasQueryParameter("pot"),
        )
        stream = backend.resolveAudioStream(stream.videoId).getOrThrow()
        probeResolvedAudioStream(stream).getOrThrow()
        return stream
    }

    private fun String.hasQueryParameter(name: String): Boolean =
        contains("?$name=") || contains("&$name=")

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

    /** Retry only within this process; every retry resolves a new signed stream first. */
    suspend fun retry(id: String): Result<String>? = enqueueMutex.withLock {
        synchronized(operationLock) { inFlightEnqueues++ }
        try {
            retryAttempts.retry(
                id = id,
                resolve = ::resolveRequest,
                enqueue = { manager.enqueue(it) },
            )
        } finally {
            synchronized(operationLock) { inFlightEnqueues-- }
        }
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

    private companion object {
        val RETRYABLE_STREAM_RESPONSE_CODES = setOf(403, 404, 410, 416)
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

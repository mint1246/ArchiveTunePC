/*
 * ArchiveTune (2026)
 * GPL-3.0-or-later
 */
package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.innertube.AudioStreamProbeHttpException
import moe.rukamori.archivetune.innertube.ArchiveTuneJvmClient
import moe.rukamori.archivetune.innertube.ResolvedAudioStream
import moe.rukamori.archivetune.innertube.probeResolvedAudioStreamCancellable
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
    private val settings: DesktopSettingsStore,
    private val defaultDownloadDirectory: Path,
    private val backend: ArchiveTuneJvmClient,
) : AutoCloseable {
    @Volatile private var directory: Path = configuredDirectory()
    @Volatile private var manager = newManager(directory, settings.snapshot())
    private val retryAttempts = InProcessDownloadAttempts()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableDownloads = MutableStateFlow(manager.downloads.value)
    private var downloadsJob: Job = observe(manager)
    private val enqueueMutex = Mutex()
    private val operationLock = Any()
    private val batchLock = Any()
    private val batchQueue = ArrayDeque<SongItem>()
    private val batchQueuedIds = linkedSetOf<String>()
    private var batchJob: Job? = null
    @Volatile private var activeResolutionMediaId: String? = null
    private val mutableResolutionQueue = MutableStateFlow(DownloadResolutionQueueState())
    private var inFlightEnqueues = 0

    /** Stable across manager reconfiguration so existing UI collectors never become stale. */
    val downloads: StateFlow<List<DownloadSnapshot>> = mutableDownloads.asStateFlow()
    val resolutionQueue: StateFlow<DownloadResolutionQueueState> = mutableResolutionQueue.asStateFlow()
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

        if (stream.clientName == "YT_DLP") return stream // Already range-validated by the extractor.
        val initialProbeFailure = probeResolvedAudioStreamCancellable(stream).exceptionOrNull()
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
        probeResolvedAudioStreamCancellable(stream).getOrThrow()
        return stream
    }

    private fun String.hasQueryParameter(name: String): Boolean =
        contains("?$name=") || contains("&$name=")

    /**
     * Queue every distinct song immediately. Signed stream resolution happens in the background,
     * one at a time, while [DesktopDownloadManager] independently runs the bounded transfer queue.
     * This keeps large album/playlist actions responsive without hammering the authenticated backend.
     */
    suspend fun enqueueAll(songs: List<SongItem>): DownloadBatchResult {
        val unique = songs.distinctBy(SongItem::id)
        var accepted = 0
        unique.forEach { song ->
            if (scheduleResolution(song)) accepted++
        }
        return DownloadBatchResult(
            requested = unique.size,
            queuedIds = emptyList(),
            failures = emptyList(),
            scheduledCount = accepted,
        )
    }

    private fun scheduleResolution(song: SongItem): Boolean = synchronized(batchLock) {
        val alreadyQueued = song.id in batchQueuedIds || activeResolutionMediaId == song.id
        val alreadyManaged = manager.downloads.value.any {
            it.mediaId == song.id && it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED)
        }
        if (alreadyQueued || alreadyManaged) return@synchronized true

        if (batchJob?.isActive != true && batchQueue.isEmpty() && activeResolutionMediaId == null) {
            mutableResolutionQueue.value = DownloadResolutionQueueState()
        }
        batchQueue.addLast(song)
        batchQueuedIds += song.id
        publishResolutionQueue()
        if (batchJob?.isActive != true) {
            val worker = serviceScope.launch(start = CoroutineStart.LAZY) {
                runResolutionQueue()
            }
            batchJob = worker
            worker.start()
        }
        true
    }

    private suspend fun runResolutionQueue() {
        while (true) {
            val song = synchronized(batchLock) {
                val next = batchQueue.pollFirst()
                if (next == null) {
                    activeResolutionMediaId = null
                    batchJob = null
                    publishResolutionQueue(currentTitle = null)
                    return
                }
                batchQueuedIds -= next.id
                activeResolutionMediaId = next.id
                publishResolutionQueue(currentTitle = next.title)
                next
            }

            val result = enqueue(song)
            synchronized(batchLock) {
                activeResolutionMediaId = null
                val previous = mutableResolutionQueue.value
                mutableResolutionQueue.value = if (result.isSuccess) {
                    previous.copy(
                        pending = batchQueue.size,
                        resolving = 0,
                        currentTitle = null,
                        prepared = previous.prepared + 1,
                    )
                } else {
                    previous.copy(
                        pending = batchQueue.size,
                        resolving = 0,
                        currentTitle = null,
                        failed = previous.failed + 1,
                        lastError = "${song.title}: ${result.exceptionOrNull()?.message ?: "Could not resolve a stream"}",
                    )
                }
            }
        }
    }

    private fun publishResolutionQueue(currentTitle: String? = mutableResolutionQueue.value.currentTitle) {
        val previous = mutableResolutionQueue.value
        mutableResolutionQueue.value = previous.copy(
            pending = batchQueue.size,
            resolving = if (activeResolutionMediaId != null) 1 else 0,
            currentTitle = currentTitle,
        )
    }

    private fun hasPendingResolutions(): Boolean = synchronized(batchLock) {
        batchQueue.isNotEmpty() || activeResolutionMediaId != null || batchJob?.isActive == true
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
            check(!hasPendingResolutions()) { "Wait for queued downloads to finish preparing before changing the download folder." }
            check(inFlightEnqueues == 0) { "Wait for stream resolution to finish before changing the download folder." }
            check(!manager.hasActiveWork()) { "Cancel or finish active downloads before changing the download folder." }
            val normalized = newDirectory.toAbsolutePath().normalize()
            Files.createDirectories(normalized)
            val desiredSettings = settings.snapshot().copy(downloadLocation = normalized)
            val replacement = newManager(normalized, desiredSettings)
            try {
                settings.update { it.copy(downloadLocation = normalized) }
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
            check(!hasPendingResolutions()) { "Wait for queued downloads to finish preparing before changing download behavior." }
            check(inFlightEnqueues == 0) { "Wait for stream resolution to finish before changing download behavior." }
            check(!manager.hasActiveWork()) { "Cancel or finish active downloads before changing download behavior." }
            val desiredSettings = settings.snapshot().copy(
                downloadConcurrency = maxConcurrentDownloads.coerceIn(1, 8),
                partialCleanupPolicy = cleanupPolicy,
            )
            val replacement = newManager(directory, desiredSettings)
            try {
                settings.update {
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

    private fun configuredDirectory(): Path = settings.snapshot().downloadLocation ?: defaultDownloadDirectory
    private fun newManager(path: Path, settings: moe.rukamori.archivetune.desktop.settings.DesktopSettings) =
        DesktopDownloadManager(
            downloadDirectory = path,
            client = moe.rukamori.archivetune.innertube.desktopStreamHttpClient(),
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
        synchronized(batchLock) {
            batchJob?.cancel()
            batchJob = null
            batchQueue.clear()
            batchQueuedIds.clear()
            activeResolutionMediaId = null
        }
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
    val scheduledCount: Int = 0,
) {
    val succeeded: Int get() = queuedIds.size + scheduledCount
}

data class DownloadResolutionQueueState(
    val pending: Int = 0,
    val resolving: Int = 0,
    val currentTitle: String? = null,
    val prepared: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
) {
    val active: Boolean get() = pending > 0 || resolving > 0
}

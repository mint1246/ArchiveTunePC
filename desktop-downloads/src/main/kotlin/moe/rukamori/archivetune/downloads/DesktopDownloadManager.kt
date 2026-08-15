/*
 * Copyright (C) 2026 ArchiveTune contributors
 *
 * This file is part of ArchiveTune.
 *
 * ArchiveTune is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 */
package moe.rukamori.archivetune.downloads

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** A resolved stream. Do not persist [url] or [headers]: both may be credentials. */
data class DownloadRequest(
    val url: String,
    val headers: Map<String, String>,
    val title: String,
    val artist: String? = null,
    val extension: String = "m4a",
    val mediaId: String? = null,
    val albumTitle: String? = null,
    val albumId: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean = false,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }
enum class PartialCleanupPolicy { DELETE_ON_CANCEL, KEEP_ON_CANCEL }

data class DownloadSnapshot(
    val id: String,
    val title: String,
    val artist: String?,
    val fileName: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
    val updatedAt: Instant = Instant.now(),
    val mediaId: String? = null,
    val albumTitle: String? = null,
    val albumId: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean = false,
)

/**
 * Concurrent, streaming desktop download service. It owns only files inside
 * [downloadDirectory]; callers should close it when the desktop application exits.
 */
class DesktopDownloadManager(
    private val downloadDirectory: Path,
    private val client: OkHttpClient = OkHttpClient(),
    maxConcurrentDownloads: Int = 2,
    private val retryCount: Int = 2,
    private val partialCleanupPolicy: PartialCleanupPolicy = PartialCleanupPolicy.DELETE_ON_CANCEL,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val permits = Semaphore(maxConcurrentDownloads.coerceAtLeast(1))
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Coroutine cancellation does not interrupt a blocking OkHttp read by itself. */
    private val activeCalls = ConcurrentHashMap<String, Call>()
    /** Serializes journal writes and read-modify-write state transitions. */
    private val stateLock = Any()
    private val journal = downloadDirectory.resolve("downloads.properties")
    private val mutableDownloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    val downloads: StateFlow<List<DownloadSnapshot>> = mutableDownloads.asStateFlow()

    init {
        Files.createDirectories(downloadDirectory)
        recoverJournal()
    }

    fun enqueue(request: DownloadRequest): String {
        require(request.url.isNotBlank()) { "A resolved stream URL is required" }
        val id = UUID.randomUUID().toString()
        synchronized(stateLock) {
            val fileName = uniqueFileName(request.title, request.artist, request.extension, id)
            // Reserve the job before publishing QUEUED. Otherwise a concurrent cancel() can
            // observe the queued snapshot while the job is not in the map yet and incorrectly
            // report that cancellation failed.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                permits.withPermit { download(id, request, fileName) }
            }
            jobs[id] = job
            update(
                DownloadSnapshot(
                    id = id,
                    title = request.title,
                    artist = request.artist,
                    fileName = fileName,
                    status = DownloadStatus.QUEUED,
                    mediaId = request.mediaId,
                    albumTitle = request.albumTitle,
                    albumId = request.albumId,
                    artworkUrl = request.artworkUrl,
                    durationMs = request.durationMs,
                    explicit = request.explicit,
                ),
            )
            job.start()
        }
        return id
    }

    fun hasActiveWork(): Boolean =
        jobs.isNotEmpty() || mutableDownloads.value.any {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }

    fun cancel(id: String): Boolean {
        synchronized(stateLock) {
            val previous = mutableDownloads.value.firstOrNull { it.id == id } ?: return false
            // A completion publishes the final file and COMPLETED status atomically below. Do
            // not let a late cancel() turn a genuinely finished download back into CANCELLED.
            if (previous.status == DownloadStatus.COMPLETED) return false
            activeCalls.remove(id)?.cancel()
            val cancelled = jobs.remove(id)?.cancel(CancellationException("Cancelled by user")) != null
            if (cancelled) {
                update(previous.copy(status = DownloadStatus.CANCELLED, error = null))
                if (partialCleanupPolicy == PartialCleanupPolicy.DELETE_ON_CANCEL) {
                    runCatching { Files.deleteIfExists(partPath(previous.fileName)) }
                }
            }
            return cancelled
        }
    }

    fun removeFromHistory(id: String): Boolean {
        synchronized(stateLock) {
            if (jobs.containsKey(id)) return false
            val current = mutableDownloads.value
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return false
            persist(next)
            mutableDownloads.value = next
            return true
        }
    }

    /**
     * Returns a completed, readable file for a media id when one is available.
     * The journal is intentionally only metadata, so stale entries are ignored.
     */
    fun completedFileFor(mediaId: String): Path? {
        if (mediaId.isBlank()) return null
        return mutableDownloads.value.asSequence()
            .filter { it.mediaId == mediaId && it.status == DownloadStatus.COMPLETED }
            .mapNotNull(::validatedCompletedPath)
            .firstOrNull()
    }

    fun completedFileForDownload(id: String): Path? =
        mutableDownloads.value.firstOrNull { it.id == id && it.status == DownloadStatus.COMPLETED }
            ?.let(::validatedCompletedPath)

    private fun validatedCompletedPath(snapshot: DownloadSnapshot): Path? {
        val root = downloadDirectory.toAbsolutePath().normalize()
        val candidate = root.resolve(snapshot.fileName).normalize()
        return candidate.takeIf { it.startsWith(root) && Files.isRegularFile(it) && Files.isReadable(it) }
    }

    private suspend fun download(id: String, request: DownloadRequest, fileName: String) {
        val initial = snapshot(id) ?: return
        val part = partPath(fileName)
        val final = downloadDirectory.resolve(fileName)
        try {
            repeat(retryCount + 1) { attempt ->
                try {
                    withContext(dispatcher) { streamOnce(id, request, part, final) }
                    jobs.remove(id)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    // OkHttp reports Call.cancel() as IOException. If the owning coroutine was
                    // cancelled, propagate cancellation instead of publishing a retry state.
                    currentCoroutineContext().ensureActive()
                    if (attempt == retryCount) throw failure
                    update((snapshot(id) ?: initial).copy(status = DownloadStatus.QUEUED, error = "Retrying (${attempt + 1}/$retryCount)"))
                    delay(250L * (attempt + 1))
                }
            }
        } catch (cancelled: CancellationException) {
            if (partialCleanupPolicy == PartialCleanupPolicy.DELETE_ON_CANCEL) Files.deleteIfExists(part)
            // cancel() has already published CANCELLED. A shutdown cancellation can arrive while
            // withContext is returning after the final file was committed, so never demote a
            // genuinely completed entry.
            synchronized(stateLock) {
                val current = snapshot(id) ?: initial
                if (current.status != DownloadStatus.COMPLETED) {
                    update(current.copy(status = DownloadStatus.CANCELLED, error = null))
                }
            }
        } catch (failure: Exception) {
            update((snapshot(id) ?: initial).copy(status = DownloadStatus.FAILED, error = publicError(failure)))
        } finally {
            jobs.remove(id)
        }
    }

    private suspend fun streamOnce(id: String, request: DownloadRequest, part: Path, final: Path) {
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())
        synchronized(stateLock) {
            coroutineContext.ensureActive()
            // Cancellation may win just before the request starts. Avoid publishing a call after
            // that transition without cancelling it immediately.
            if (snapshot(id)?.status == DownloadStatus.CANCELLED) {
                call.cancel()
                throw CancellationException("Cancelled by user")
            }
            activeCalls[id] = call
        }
        try {
            call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it >= 0 }
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                val current = snapshot(id) ?: return
                if (current.status == DownloadStatus.CANCELLED) {
                    throw CancellationException("Cancelled by user")
                }
                update(current.copy(status = DownloadStatus.DOWNLOADING, totalBytes = total, error = null))
            }
            Files.newOutputStream(part).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        downloaded += read
                        synchronized(stateLock) {
                            val current = snapshot(id) ?: return
                            // cancel() publishes CANCELLED under this same lock. Never let a
                            // late body read resurrect the transfer or make finalization win.
                            if (current.status == DownloadStatus.CANCELLED) {
                                throw CancellationException("Cancelled by user")
                            }
                            update(
                                current.copy(
                                    status = DownloadStatus.DOWNLOADING,
                                    bytesDownloaded = downloaded,
                                    totalBytes = total,
                                    error = null,
                                ),
                                persistState = false,
                            )
                        }
                    }
                }
            }
        }
        // Serialize final-file publication with cancel(). Whichever operation acquires the lock
        // first wins; cancellation cannot subsequently hide a completed file.
        currentCoroutineContext().ensureActive()
        synchronized(stateLock) {
            val current = snapshot(id) ?: return
            if (current.status == DownloadStatus.CANCELLED) {
                throw CancellationException("Cancelled by user")
            }
            moveAtomically(part, final)
            update(current.copy(status = DownloadStatus.COMPLETED, bytesDownloaded = Files.size(final), error = null))
            }
        } finally {
            activeCalls.remove(id, call)
        }
    }

    private fun update(snapshot: DownloadSnapshot, persistState: Boolean = true) {
        synchronized(stateLock) {
            val next = mutableDownloads.value.filterNot { it.id == snapshot.id } + snapshot.copy(updatedAt = Instant.now())
            if (persistState) persist(next)
            mutableDownloads.value = next
        }
    }

    private fun snapshot(id: String) = mutableDownloads.value.firstOrNull { it.id == id }
    private fun partPath(fileName: String) = downloadDirectory.resolve("$fileName.part")

    private fun uniqueFileName(title: String, artist: String?, extension: String, id: String): String {
        val candidate = safeFileName(title, artist, extension, id)
        synchronized(stateLock) {
            val alreadyUsed = mutableDownloads.value.any { it.fileName.equals(candidate, ignoreCase = true) }
            if (!alreadyUsed && !Files.exists(downloadDirectory.resolve(candidate))) return candidate
        }
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val suffix = if (dot > 0) candidate.substring(dot) else ""
        return "$stem-${id.take(8)}$suffix"
    }

    private fun persist(items: List<DownloadSnapshot> = mutableDownloads.value) {
        synchronized(stateLock) {
            val properties = Properties()
            items.forEachIndexed { index, item ->
                val prefix = "download.$index"
                properties["$prefix.id"] = item.id
                properties["$prefix.title"] = item.title
                properties["$prefix.artist"] = item.artist.orEmpty()
                properties["$prefix.fileName"] = item.fileName
                properties["$prefix.status"] = item.status.name
                properties["$prefix.bytes"] = item.bytesDownloaded.toString()
                properties["$prefix.total"] = item.totalBytes?.toString().orEmpty()
                properties["$prefix.error"] = item.error.orEmpty()
                properties["$prefix.updatedAt"] = item.updatedAt.toEpochMilli().toString()
                properties["$prefix.mediaId"] = item.mediaId.orEmpty()
                properties["$prefix.albumTitle"] = item.albumTitle.orEmpty()
                properties["$prefix.albumId"] = item.albumId.orEmpty()
                properties["$prefix.artworkUrl"] = item.artworkUrl.orEmpty()
                properties["$prefix.durationMs"] = item.durationMs?.toString().orEmpty()
                properties["$prefix.explicit"] = item.explicit.toString()
            }
            val temporary = Files.createTempFile(downloadDirectory, ".downloads.", ".tmp")
            try {
                Files.newOutputStream(temporary).use { properties.store(it, "ArchiveTune desktop download state (no URLs or headers)") }
                try {
                    Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, journal, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private fun recoverJournal() {
        if (!Files.exists(journal)) return
        val properties = Properties().also { Files.newInputStream(journal).use(it::load) }
        val indexes = properties.stringPropertyNames().mapNotNull { key ->
            Regex("download\\.(\\d+)\\.id").matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
        }.sorted()
        val recovered = indexes.mapNotNull { index ->
            val prefix = "download.$index"
            val id = properties.getProperty("$prefix.id") ?: return@mapNotNull null
            val prior = runCatching { DownloadStatus.valueOf(properties.getProperty("$prefix.status")) }.getOrDefault(DownloadStatus.FAILED)
            val status = if (prior == DownloadStatus.QUEUED || prior == DownloadStatus.DOWNLOADING) DownloadStatus.FAILED else prior
            DownloadSnapshot(
                id = id,
                title = properties.getProperty("$prefix.title", "Untitled"),
                artist = properties.getProperty("$prefix.artist").orEmpty().ifBlank { null },
                fileName = properties.getProperty("$prefix.fileName", "download-$id"),
                status = status,
                bytesDownloaded = properties.getProperty("$prefix.bytes", "0").toLongOrNull() ?: 0,
                totalBytes = properties.getProperty("$prefix.total").orEmpty().toLongOrNull(),
                error = if (status == DownloadStatus.FAILED && status != prior) "Interrupted by application restart; resolve a new stream to retry" else properties.getProperty("$prefix.error").orEmpty().ifBlank { null },
                updatedAt = Instant.ofEpochMilli(properties.getProperty("$prefix.updatedAt", "0").toLongOrNull() ?: 0),
                mediaId = properties.getProperty("$prefix.mediaId").orEmpty().ifBlank { null },
                albumTitle = properties.getProperty("$prefix.albumTitle").orEmpty().ifBlank { null },
                albumId = properties.getProperty("$prefix.albumId").orEmpty().ifBlank { null },
                artworkUrl = properties.getProperty("$prefix.artworkUrl").orEmpty().ifBlank { null },
                durationMs = properties.getProperty("$prefix.durationMs").orEmpty().toLongOrNull(),
                explicit = properties.getProperty("$prefix.explicit").orEmpty().toBoolean(),
            )
        }
        persist(recovered)
        mutableDownloads.value = recovered
    }

    override fun close() {
        // Mark every child cancelled before clearing calls. A transfer that was about to register
        // its call will then fail the context check under stateLock instead of escaping shutdown.
        scope.cancel()
        synchronized(stateLock) {
            activeCalls.values.forEach(Call::cancel)
            activeCalls.clear()
        }
    }

    private fun safeFileName(title: String, artist: String?, extension: String, id: String): String {
        val raw = listOfNotNull(artist?.trim()?.takeIf { it.isNotEmpty() }, title.trim().takeIf { it.isNotEmpty() }).joinToString(" - ")
        val base = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim().trim('.').take(120).ifBlank { "download-$id" }
        val safeBase = if (base.substringBefore('.').uppercase() in WINDOWS_RESERVED_NAMES) "_$base" else base
        val suffix = extension.lowercase().replace(Regex("[^a-z0-9]"), "").take(10).ifBlank { "bin" }
        return "$safeBase.$suffix"
    }

    private fun moveAtomically(source: Path, destination: Path) {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun publicError(error: Throwable) = error.message?.replace(Regex("https?://\\S+"), "remote stream")?.take(180) ?: "Download failed"

    private companion object {
        val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach { index ->
                add("COM$index")
                add("LPT$index")
            }
        }
    }
}

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
        val fileName = safeFileName(request.title, request.artist, request.extension, id)
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
        jobs[id] = scope.launch { permits.withPermit { download(id, request, fileName) } }
        return id
    }

    fun cancel(id: String): Boolean {
        val cancelled = jobs.remove(id)?.cancel(CancellationException("Cancelled by user")) != null
        if (cancelled) {
            val previous = mutableDownloads.value.firstOrNull { it.id == id } ?: return true
            update(previous.copy(status = DownloadStatus.CANCELLED, error = null))
            if (partialCleanupPolicy == PartialCleanupPolicy.DELETE_ON_CANCEL) {
                runCatching { Files.deleteIfExists(partPath(previous.fileName)) }
            }
        }
        return cancelled
    }

    fun removeFromHistory(id: String): Boolean {
        if (jobs.containsKey(id)) return false
        val next = mutableDownloads.value.filterNot { it.id == id }
        if (next.size == mutableDownloads.value.size) return false
        persist(next)
        mutableDownloads.value = next
        return true
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
                    if (attempt == retryCount) throw failure
                    update((snapshot(id) ?: initial).copy(status = DownloadStatus.QUEUED, error = "Retrying (${attempt + 1}/$retryCount)"))
                    delay(250L * (attempt + 1))
                }
            }
        } catch (cancelled: CancellationException) {
            if (partialCleanupPolicy == PartialCleanupPolicy.DELETE_ON_CANCEL) Files.deleteIfExists(part)
            // cancel() has already published CANCELLED. Coroutine cancellation during close is identical.
            update((snapshot(id) ?: initial).copy(status = DownloadStatus.CANCELLED, error = null))
        } catch (failure: Exception) {
            update((snapshot(id) ?: initial).copy(status = DownloadStatus.FAILED, error = publicError(failure)))
        } finally {
            jobs.remove(id)
        }
    }

    private fun streamOnce(id: String, request: DownloadRequest, part: Path, final: Path) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it >= 0 }
            update((snapshot(id) ?: return).copy(status = DownloadStatus.DOWNLOADING, totalBytes = total, error = null))
            Files.newOutputStream(part).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        update((snapshot(id) ?: return).copy(status = DownloadStatus.DOWNLOADING, bytesDownloaded = downloaded, totalBytes = total, error = null))
                    }
                }
            }
        }
        moveAtomically(part, final)
        update((snapshot(id) ?: return).copy(status = DownloadStatus.COMPLETED, bytesDownloaded = Files.size(final), error = null))
    }

    private fun update(snapshot: DownloadSnapshot) {
        val next = mutableDownloads.value.filterNot { it.id == snapshot.id } + snapshot.copy(updatedAt = Instant.now())
        persist(next)
        mutableDownloads.value = next
    }

    private fun snapshot(id: String) = mutableDownloads.value.firstOrNull { it.id == id }
    private fun partPath(fileName: String) = downloadDirectory.resolve("$fileName.part")

    private fun persist(items: List<DownloadSnapshot> = mutableDownloads.value) {
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
        Files.newOutputStream(journal).use { properties.store(it, "ArchiveTune desktop download state (no URLs or headers)") }
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
                totalBytes = properties.getProperty("$prefix.total").toLongOrNull(),
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

    override fun close() { scope.cancel() }

    private fun safeFileName(title: String, artist: String?, extension: String, id: String): String {
        val raw = listOfNotNull(artist?.trim()?.takeIf { it.isNotEmpty() }, title.trim().takeIf { it.isNotEmpty() }).joinToString(" - ")
        val base = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim().trim('.').take(120).ifBlank { "download-$id" }
        val suffix = extension.lowercase().replace(Regex("[^a-z0-9]"), "").take(10).ifBlank { "bin" }
        return "$base.$suffix"
    }

    private fun moveAtomically(source: Path, destination: Path) {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun publicError(error: Throwable) = error.message?.replace(Regex("https?://\\S+"), "remote stream")?.take(180) ?: "Download failed"
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uk.co.caprica.vlcj.media.callback.seekable.SeekableCallbackMedia

/**
 * Application-provided ranged HTTP transport used by [VlcjPlaybackController] for signed
 * Googlevideo streams. OkHttp owns the request headers/range semantics while libVLC only receives
 * the resulting bytes through its callback-media API.
 */
internal class VlcjRangeCallbackMedia(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val onHttpFailure: (Int) -> Unit = {},
    httpClient: OkHttpClient,
    private val fallbackDirectory: Path? = null,
    private val maxFullObjectFallbackBytes: Long = MAX_FULL_OBJECT_FALLBACK_BYTES,
) : SeekableCallbackMedia(), AutoCloseable {
    private val source = RangeMediaSource(
        url = url,
        headers = headers,
        windowSize = windowSize,
        client = httpClient,
        fallbackDirectory = fallbackDirectory,
        maxFullObjectFallbackBytes = maxFullObjectFallbackBytes,
    )
    private val closed = AtomicBoolean(false)

    internal fun openSize(): Long =
        try {
            source.open() ?: 0L
        } catch (failure: RangeHttpException) {
            onHttpFailure(failure.httpStatus)
            0L
        }

    override fun onGetSize(): Long = openSize()

    override fun onOpen(): Boolean = source.isOpen

    override fun onRead(buffer: ByteArray, bufferSize: Int): Int =
        readInto(buffer, bufferSize)

    internal fun readInto(buffer: ByteArray, bufferSize: Int): Int =
        try {
            source.read(buffer, bufferSize)
        } catch (failure: RangeHttpException) {
            onHttpFailure(failure.httpStatus)
            throw failure
        }

    override fun onSeek(offset: Long): Boolean = seekTo(offset)

    internal fun seekTo(offset: Long): Boolean = source.seek(offset)

    override fun onClose() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) source.close()
    }

    private class RangeMediaSource(
        private val url: String,
        private val headers: Map<String, String>,
        private val windowSize: Int,
        private val client: OkHttpClient,
        private val fallbackDirectory: Path?,
        private val maxFullObjectFallbackBytes: Long,
    ) : AutoCloseable {
        private val lock = Any()
        private val closed = AtomicBoolean(false)
        private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        private var contentLength: Long? = null
        private var cursor = 0L
        private var windowStart = -1L
        private var window: ByteArray? = null
        private var fallbackPath: Path? = null
        private var fallbackChannel: FileChannel? = null
        private var opened = false

        val isOpen: Boolean
            get() = synchronized(lock) { opened }

        fun open(): Long? = synchronized(lock) {
            if (!opened) {
                try {
                    fetchWindow(0L)
                    opened = true
                } catch (failure: RangeHttpException) {
                    throw failure
                } catch (_: IOException) {
                    return null
                }
            }
            contentLength ?: window?.size?.toLong()
        }

        fun seek(offset: Long): Boolean = synchronized(lock) {
            if (offset < 0L) return false
            if (fallbackChannel != null) {
                cursor = contentLength?.let { minOf(offset, it) } ?: offset
                return true
            }
            val activeWindow = window
            if (
                activeWindow != null &&
                offset >= windowStart &&
                offset <= windowStart + activeWindow.size
            ) {
                // libVLC probes/rewinds container headers during startup. Do not discard bytes we
                // already own just because it moved the cursor inside the current HTTP range; doing
                // so creates a second Googlevideo request immediately after playback starts.
                cursor = offset
                return true
            }
            if (contentLength != null && offset >= contentLength!!) {
                cursor = contentLength!!
                window = null
            } else {
                cursor = offset
                window = null
            }
            true
        }

        @Throws(IOException::class)
        fun read(buffer: ByteArray, length: Int): Int = synchronized(lock) {
            if (contentLength != null && cursor >= contentLength!!) return -1
            fallbackChannel?.let { channel ->
                val remaining = contentLength?.minus(cursor)?.coerceAtLeast(0L) ?: Long.MAX_VALUE
                val requested = minOf(length.coerceAtLeast(0), buffer.size, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if (requested <= 0) return -1
                channel.position(cursor)
                val count = channel.read(ByteBuffer.wrap(buffer, 0, requested))
                if (count <= 0) return -1
                cursor += count
                return count
            }
            if (window == null || cursor < windowStart || cursor >= windowStart + window!!.size) {
                fetchWindow(cursor)
            }
            val activeWindow = window ?: throw IOException("HTTP media window is unavailable")
            val offset = (cursor - windowStart).toInt()
            val available = activeWindow.size - offset
            if (available <= 0) return -1
            val count = minOf(length.coerceAtLeast(0), buffer.size, available)
            if (count <= 0) return -1
            System.arraycopy(activeWindow, offset, buffer, 0, count)
            cursor += count
            count
        }

        @Throws(IOException::class)
        private fun fetchWindow(offset: Long) {
            if (closed.get()) throw IOException("HTTP media source is closed")
            val end = offset + windowSize - 1
            var lastFailure: IOException? = null
            repeat(MAX_FETCH_ATTEMPTS) { attempt ->
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$offset-$end")
                headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank() &&
                        !name.equals("Range", ignoreCase = true)
                    ) {
                        requestBuilder.header(name, value)
                    }
                }

                try {
                    executeRequest(requestBuilder.build()) { response ->
                        if (!response.isSuccessful) {
                            if (
                                response.code in FULL_OBJECT_FALLBACK_STATUS_CODES &&
                                fetchWholeObjectFallback(offset)
                            ) {
                                return
                            }
                            if (closed.get()) throw IOException("HTTP media source is closed")
                            throw RangeHttpException(response.code)
                        }
                        if (response.code != 206) {
                            if (installWholeObjectFallback(response, offset)) return
                            throw IOException("HTTP server ignored the media range and the full response was unusable")
                        }
                        val bytes = response.body.bytes()
                        if (bytes.isEmpty() && contentLength?.let { offset < it } != false) {
                            throw IOException("YouTube returned an empty media range")
                        }
                        contentLength = response.header("Content-Range")
                            ?.substringAfter('/')
                            ?.toLongOrNull()
                        windowStart = response.header("Content-Range")
                            ?.substringBefore('-')
                            ?.substringAfter("bytes ")
                            ?.toLongOrNull()
                            ?: offset
                        window = bytes
                        return
                    }
                } catch (failure: IOException) {
                    lastFailure = failure
                    if (closed.get()) throw IOException("HTTP media source is closed", failure)
                    if (attempt + 1 < MAX_FETCH_ATTEMPTS) {
                        try {
                            Thread.sleep(FETCH_RETRY_DELAY_MS * (attempt + 1L))
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("Interrupted while retrying YouTube media range", interrupted)
                        }
                    }
                }
            }
            throw lastFailure ?: IOException("Unable to fetch YouTube media range")
        }

        /**
         * Some signed Googlevideo URLs reject a follow-up Range request while the same URL still
         * works as a normal GET. For ordinary song-sized audio files, keep that already-resolved URL
         * alive instead of forcing a full YouTube player re-resolution and client backoff.
         */
        private fun fetchWholeObjectFallback(requiredOffset: Long): Boolean {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank() &&
                    !name.equals("Range", ignoreCase = true)
                ) {
                    requestBuilder.header(name, value)
                }
            }
            return try {
                executeRequest(requestBuilder.build()) { response ->
                    if (!response.isSuccessful) return false
                    installWholeObjectFallback(response, requiredOffset)
                }
            } catch (_: IOException) {
                false
            }
        }

        private fun installWholeObjectFallback(response: Response, requiredOffset: Long): Boolean {
            val declaredLength = response.body.contentLength()
            if (declaredLength > maxFullObjectFallbackBytes) return false
            if (declaredLength >= 0L && requiredOffset > declaredLength) return false

            val path = fallbackDirectory?.let {
                Files.createTempFile(it, "archivetune-vlcj-", ".media")
            } ?: Files.createTempFile("archivetune-vlcj-", ".media")
            var installed = false
            try {
                var total = 0L
                response.body.byteStream().use { input ->
                    Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                        val copyBuffer = ByteArray(FALLBACK_COPY_BUFFER_SIZE)
                        while (true) {
                            if (closed.get()) throw IOException("HTTP media source is closed")
                            val count = input.read(copyBuffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > maxFullObjectFallbackBytes) return false
                            output.write(copyBuffer, 0, count)
                        }
                    }
                }
                if (requiredOffset > total) return false
                val channel = FileChannel.open(path, StandardOpenOption.READ)
                fallbackChannel?.close()
                fallbackPath?.let { runCatching { Files.deleteIfExists(it) } }
                fallbackPath = path
                fallbackChannel = channel
                contentLength = total
                windowStart = -1L
                window = null
                installed = true
                return true
            } finally {
                if (!installed) runCatching { Files.deleteIfExists(path) }
            }
        }

        private inline fun <T> executeRequest(request: Request, block: (Response) -> T): T {
            if (closed.get()) throw IOException("HTTP media source is closed")
            val call = client.newCall(request)
            activeCalls += call
            if (closed.get()) {
                activeCalls.remove(call)
                call.cancel()
                throw IOException("HTTP media source is closed")
            }
            return try {
                call.execute().use(block)
            } finally {
                activeCalls.remove(call)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            activeCalls.toList().forEach(Call::cancel)
            synchronized(lock) {
                runCatching { fallbackChannel?.close() }
                fallbackChannel = null
                fallbackPath?.let { runCatching { Files.deleteIfExists(it) } }
                fallbackPath = null
                window = null
                opened = false
            }
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_SIZE = 512 * 1024
        const val MAX_FETCH_ATTEMPTS = 3
        const val FETCH_RETRY_DELAY_MS = 100L
        const val MAX_FULL_OBJECT_FALLBACK_BYTES = 32L * 1024L * 1024L
        const val FALLBACK_COPY_BUFFER_SIZE = 64 * 1024
        val FULL_OBJECT_FALLBACK_STATUS_CODES = setOf(403, 416)
    }
}

/** One reusable HTTP transport per playback controller; individual media never own its pool. */
internal class VlcjRangeHttpTransport(
    internal val client: OkHttpClient = newRangeHttpClient(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun media(
        url: String,
        headers: Map<String, String> = emptyMap(),
        onHttpFailure: (Int) -> Unit = {},
    ): VlcjRangeCallbackMedia {
        check(!closed.get()) { "HTTP transport is closed" }
        return VlcjRangeCallbackMedia(
            url = url,
            headers = headers,
            onHttpFailure = onHttpFailure,
            httpClient = client,
        )
    }

    /**
     * Materialize one signed media object to disk using the same HTTP stack/headers as callback
     * playback. libVLC's stream-output pipeline is not reliable when its input itself is callback
     * media, so stem generation first turns Googlevideo into an ordinary local encoded file and
     * then lets libVLC perform the fast local decode/transcode step.
     *
     * Use bounded range requests instead of one giant response: Googlevideo is explicitly a range
     * endpoint and some variants cap an open-ended range. The loop also lets us verify that the
     * complete object was received before handing it to a container demuxer.
     */
    fun materialize(
        url: String,
        headers: Map<String, String> = emptyMap(),
        destination: Path,
    ): Path {
        check(!closed.get()) { "HTTP transport is closed" }
        destination.parent?.let(Files::createDirectories)
        Files.deleteIfExists(destination)

        var offset = 0L
        var totalLength: Long? = null
        try {
            FileChannel.open(
                destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { output ->
                while (totalLength == null || offset < totalLength!!) {
                    if (closed.get()) throw IOException("HTTP transport is closed")
                    val end = offset + MATERIALIZE_RANGE_SIZE - 1L
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$offset-$end")
                    headers.forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank() && !name.equals("Range", ignoreCase = true)) {
                            requestBuilder.header(name, value)
                        }
                    }

                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) throw RangeHttpException(response.code)
                        val body = response.body
                        if (response.code == 200) {
                            if (offset != 0L) throw IOException("HTTP media source stopped honoring ranges")
                            val copied = copyResponseBody(body.byteStream(), output)
                            if (copied <= 0L) throw IOException("YouTube returned an empty media object")
                            offset = copied
                            totalLength = copied
                            return@use
                        }
                        if (response.code != 206) {
                            throw IOException("Unexpected HTTP ${response.code} while materializing media")
                        }

                        val contentRange = response.header("Content-Range")
                            ?: throw IOException("Ranged media response is missing Content-Range")
                        val returnedStart = contentRange
                            .substringBefore('-')
                            .substringAfter("bytes ")
                            .trim()
                            .toLongOrNull()
                            ?: throw IOException("Invalid Content-Range: $contentRange")
                        if (returnedStart != offset) {
                            throw IOException("Media range started at $returnedStart instead of $offset")
                        }
                        val responseTotal = contentRange.substringAfter('/').trim().toLongOrNull()
                        if (responseTotal != null) {
                            totalLength?.let { previous ->
                                if (previous != responseTotal) throw IOException("Media size changed while downloading")
                            }
                            totalLength = responseTotal
                        }

                        output.position(offset)
                        val copied = copyResponseBody(body.byteStream(), output)
                        if (copied <= 0L) throw IOException("YouTube returned an empty media range")
                        offset += copied
                        if (totalLength != null && offset > totalLength!!) {
                            throw IOException("Downloaded media exceeded declared content length")
                        }
                    }
                }
            }

            val size = Files.size(destination)
            if (size <= 0L || totalLength?.let { it != size } == true) {
                throw IOException("Incomplete media download: received $size of ${totalLength ?: "unknown"} bytes")
            }
            return destination
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(destination) }
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        runCatching { client.cache?.close() }
    }

    private fun copyResponseBody(input: java.io.InputStream, output: FileChannel): Long {
        val buffer = ByteArray(MATERIALIZE_COPY_BUFFER_SIZE)
        var copied = 0L
        input.use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                var bytes = ByteBuffer.wrap(buffer, 0, count)
                while (bytes.hasRemaining()) output.write(bytes)
                copied += count
            }
        }
        return copied
    }

    private companion object {
        const val MATERIALIZE_RANGE_SIZE = 4L * 1024L * 1024L
        const val MATERIALIZE_COPY_BUFFER_SIZE = 64 * 1024
    }
}

private fun newRangeHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

private class RangeHttpException(
    val httpStatus: Int,
) : IOException("YouTube media range request failed with HTTP $httpStatus")

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.IOException
import java.io.InputStream
import java.net.URI
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
        private var effectiveUrl: String = url
        private var contentLength: Long? = null
        private var cursor = 0L
        private var activeCall: Call? = null
        private var activeResponse: Response? = null
        private var activeStream: InputStream? = null
        private var streamPos = 0L
        private var streamEndExclusive = 0L
        private var headCache: ByteArray? = null
        private var headCacheLen = 0
        private var tailCacheStart = -1L
        private var tailCache: ByteArray? = null
        private var tailCacheLen = 0
        private var fallbackPath: Path? = null
        private var fallbackChannel: FileChannel? = null
        private var opened = false

        val isOpen: Boolean
            get() = synchronized(lock) { opened }

        fun open(): Long? = synchronized(lock) {
            if (!opened) {
                try {
                    openStreamLocked(0L)
                    opened = true
                } catch (failure: RangeHttpException) {
                    throw failure
                } catch (_: IOException) {
                    return null
                }
            }
            contentLength ?: streamEndExclusive.takeIf { it > 0L && it < Long.MAX_VALUE }
        }

        fun seek(offset: Long): Boolean = synchronized(lock) {
            if (offset < 0L) return false
            cursor = contentLength?.let { minOf(offset, it) } ?: offset
            // Keep any open HTTP stream alive across seeks: libVLC frequently probes/rewinds
            // container headers near offset 0, and headCache/tailCache let it jump back to
            // streamPos without tearing down the HTTP/2 stream or paying another RTT.
            true
        }

        @Throws(IOException::class)
        fun read(buffer: ByteArray, length: Int): Int = synchronized(lock) {
            if (closed.get()) throw IOException("HTTP media source is closed")
            if (contentLength != null && cursor >= contentLength!!) return -1
            val requested = minOf(length.coerceAtLeast(0), buffer.size)
            if (requested <= 0) return -1

            fallbackChannel?.let { channel ->
                val remaining = contentLength?.minus(cursor)?.coerceAtLeast(0L) ?: Long.MAX_VALUE
                val toRead = minOf(requested.toLong(), remaining).toInt()
                if (toRead <= 0) return -1
                channel.position(cursor)
                val count = channel.read(ByteBuffer.wrap(buffer, 0, toRead))
                if (count <= 0) return -1
                cursor += count
                return count
            }

            // 1. Serve from cached head prefix (offset 0..headCacheLen) in 0ms RAM hit.
            val cachedHead = headCache
            if (cachedHead != null && cursor < headCacheLen.toLong()) {
                val offsetInCache = cursor.toInt()
                val available = headCacheLen - offsetInCache
                val count = minOf(requested, available)
                System.arraycopy(cachedHead, offsetInCache, buffer, 0, count)
                cursor += count
                return count
            }

            // 2. Serve from cached tail metadata region (e.g. WebM Cues / MP4 moov) in 0ms.
            val cachedTail = tailCache
            if (cachedTail != null && tailCacheStart >= 0L &&
                cursor >= tailCacheStart && cursor < tailCacheStart + tailCacheLen.toLong()
            ) {
                val offsetInTail = (cursor - tailCacheStart).toInt()
                val available = tailCacheLen - offsetInTail
                val count = minOf(requested, available)
                System.arraycopy(cachedTail, offsetInTail, buffer, 0, count)
                cursor += count
                return count
            }

            // 3. Stream progressively from the active HTTP 206 range, re-opening on demand or on transient error.
            var lastFailure: IOException? = null
            repeat(MAX_FETCH_ATTEMPTS) { attempt ->
                if (closed.get()) throw IOException("HTTP media source is closed")
                if (contentLength != null && cursor >= contentLength!!) return -1

                try {
                    ensureStreamPositionedLocked(cursor)
                    // openStreamLocked may have installed the file-backed whole-object fallback.
                    fallbackChannel?.let { channel ->
                        val remaining = contentLength?.minus(cursor)?.coerceAtLeast(0L) ?: Long.MAX_VALUE
                        val toRead = minOf(requested.toLong(), remaining).toInt()
                        if (toRead <= 0) return -1
                        channel.position(cursor)
                        val count = channel.read(ByteBuffer.wrap(buffer, 0, toRead))
                        if (count <= 0) return -1
                        cursor += count
                        return count
                    }

                    val stream = activeStream ?: throw IOException("HTTP media stream is unavailable")
                    val remainingInWindow = (streamEndExclusive - streamPos).coerceAtLeast(0L)
                    if (remainingInWindow <= 0L) {
                        closeActiveStreamLocked()
                        openStreamLocked(cursor)
                        return@repeat
                    }
                    val toRead = minOf(requested.toLong(), remainingInWindow).toInt()
                    val count = stream.read(buffer, 0, toRead)
                    if (count < 0) {
                        closeActiveStreamLocked()
                        val total = contentLength
                        if (total != null && cursor >= total) return -1
                        if (total == null) {
                            contentLength = cursor
                            return -1
                        }
                        throw IOException("YouTube media range ended early at offset $cursor of $total")
                    }
                    if (count == 0) return@repeat

                    recordReadBytesLocked(cursor, buffer, count)
                    streamPos += count
                    cursor += count
                    if (streamPos >= streamEndExclusive) {
                        closeActiveStreamLocked()
                    }
                    return count
                } catch (failure: RangeHttpException) {
                    closeActiveStreamLocked()
                    throw failure
                } catch (failure: IOException) {
                    closeActiveStreamLocked()
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
            throw lastFailure ?: IOException("Unable to read YouTube media range")
        }

        private fun ensureStreamPositionedLocked(targetOffset: Long) {
            val stream = activeStream
            if (stream != null && streamPos <= targetOffset && targetOffset < streamEndExclusive) {
                val gap = targetOffset - streamPos
                if (gap == 0L) return
                if (gap <= MAX_FORWARD_SKIP_BYTES) {
                    val skipBuffer = ByteArray(minOf(gap, 8192L).toInt())
                    var remaining = gap
                    while (remaining > 0L) {
                        if (closed.get()) throw IOException("HTTP media source is closed")
                        val count = stream.read(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt())
                        if (count < 0) {
                            closeActiveStreamLocked()
                            openStreamLocked(targetOffset)
                            return
                        }
                        if (count == 0) continue
                        recordReadBytesLocked(streamPos, skipBuffer, count)
                        streamPos += count
                        remaining -= count
                    }
                    if (streamPos >= streamEndExclusive) {
                        closeActiveStreamLocked()
                        openStreamLocked(targetOffset)
                    }
                    return
                }
            }
            closeActiveStreamLocked()
            openStreamLocked(targetOffset)
        }

        private fun recordReadBytesLocked(offset: Long, data: ByteArray, count: Int) {
            // Retain the initial header/audio prefix so libVLC rewinds and post-Cue seeks back to 0 hit RAM.
            if (offset == headCacheLen.toLong() && headCacheLen < HEAD_CACHE_MAX_BYTES) {
                val cache = headCache ?: ByteArray(HEAD_CACHE_MAX_BYTES).also { headCache = it }
                val toCopy = minOf(count, HEAD_CACHE_MAX_BYTES - headCacheLen)
                System.arraycopy(data, 0, cache, headCacheLen, toCopy)
                headCacheLen += toCopy
            }
            // Retain tail bytes (Cues / moov) when libVLC probes near EOF.
            val total = contentLength
            if (total != null && total > HEAD_CACHE_MAX_BYTES.toLong()) {
                val tailRegionStart = (total - TAIL_CACHE_MAX_BYTES.toLong()).coerceAtLeast(HEAD_CACHE_MAX_BYTES.toLong())
                if (offset >= tailRegionStart) {
                    if (tailCache == null || tailCacheStart < 0L) {
                        tailCacheStart = offset
                        tailCache = ByteArray((total - offset).coerceAtMost(TAIL_CACHE_MAX_BYTES.toLong()).toInt())
                        tailCacheLen = 0
                    }
                    val cache = tailCache
                    if (cache != null && offset == tailCacheStart + tailCacheLen.toLong() && tailCacheLen < cache.size) {
                        val toCopy = minOf(count, cache.size - tailCacheLen)
                        System.arraycopy(data, 0, cache, tailCacheLen, toCopy)
                        tailCacheLen += toCopy
                    }
                }
            }
        }

        @Throws(IOException::class)
        private fun openStreamLocked(offset: Long) {
            if (closed.get()) throw IOException("HTTP media source is closed")
            closeActiveStreamLocked()
            val requestWindowSize =
                when {
                    offset == 0L -> minOf(windowSize, INITIAL_WINDOW_SIZE)
                    offset <= INITIAL_WINDOW_SIZE.toLong() -> minOf(windowSize, RAMP_WINDOW_SIZE)
                    else -> windowSize
                }
            val end = offset + requestWindowSize - 1L
            var lastFailure: IOException? = null
            var retriedOriginalUrl = false
            var attempt = 0
            while (attempt < MAX_FETCH_ATTEMPTS) {
                val requestUrl = effectiveUrl
                val requestBuilder = Request.Builder()
                    .url(requestUrl)
                    .header("Range", "bytes=$offset-$end")
                headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank() &&
                        !name.equals("Range", ignoreCase = true)
                    ) {
                        requestBuilder.header(name, value)
                    }
                }

                try {
                    val call = createTrackedCall(requestBuilder.build())
                    var keepOpen = false
                    var response: Response? = null
                    try {
                        response = call.execute()
                        if (!response.isSuccessful) {
                            val code = response.code
                            response.close()
                            activeCalls.remove(call)
                            if (code in REDIRECT_FALLBACK_STATUS_CODES && requestUrl != url && !retriedOriginalUrl) {
                                // A sticky redirected edge rejected this range; fall back to the original signed URL once.
                                effectiveUrl = url
                                retriedOriginalUrl = true
                                continue
                            }
                            if (
                                code in FULL_OBJECT_FALLBACK_STATUS_CODES &&
                                fetchWholeObjectFallback(offset)
                            ) {
                                return
                            }
                            if (closed.get()) throw IOException("HTTP media source is closed")
                            throw RangeHttpException(code)
                        }
                        if (response.code != 206) {
                            val installed = response.use { installWholeObjectFallback(it, offset) }
                            activeCalls.remove(call)
                            if (installed) return
                            throw IOException("HTTP server ignored the media range and the full response was unusable")
                        }

                        val contentRange = response.header("Content-Range")
                        val parsedTotal = contentRange
                            ?.substringAfter('/', missingDelimiterValue = "")
                            ?.trim()
                            ?.takeUnless { it == "*" }
                            ?.toLongOrNull()
                        if (parsedTotal != null) {
                            contentLength = parsedTotal
                        }
                        val returnedStart = contentRange
                            ?.substringBefore('-')
                            ?.substringAfter("bytes ")
                            ?.trim()
                            ?.toLongOrNull()
                            ?: offset
                        val returnedEnd = contentRange
                            ?.substringAfter('-', missingDelimiterValue = "")
                            ?.substringBefore('/')
                            ?.trim()
                            ?.toLongOrNull()
                        val declaredBodyLen = response.body.contentLength().takeIf { it > 0L }
                        val resolvedEndExclusive = when {
                            returnedEnd != null -> returnedEnd + 1L
                            declaredBodyLen != null -> returnedStart + declaredBodyLen
                            parsedTotal != null -> minOf(returnedStart + requestWindowSize.toLong(), parsedTotal)
                            else -> returnedStart + requestWindowSize.toLong()
                        }

                        val finalUrl = response.request.url.toString()
                        if (isSafeRedirectTarget(url, finalUrl)) {
                            effectiveUrl = finalUrl
                        }

                        activeCall = call
                        activeResponse = response
                        activeStream = response.body.byteStream()
                        streamPos = returnedStart
                        streamEndExclusive = resolvedEndExclusive
                        keepOpen = true
                        return
                    } finally {
                        if (!keepOpen) {
                            runCatching { response?.close() }
                            activeCalls.remove(call)
                        }
                    }
                } catch (failure: RangeHttpException) {
                    throw failure
                } catch (failure: IOException) {
                    lastFailure = failure
                    if (closed.get()) throw IOException("HTTP media source is closed", failure)
                    attempt++
                    if (attempt < MAX_FETCH_ATTEMPTS) {
                        try {
                            Thread.sleep(FETCH_RETRY_DELAY_MS * attempt.toLong())
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("Interrupted while retrying YouTube media range", interrupted)
                        }
                    }
                }
            }
            throw lastFailure ?: IOException("Unable to fetch YouTube media range")
        }

        private fun createTrackedCall(request: Request): Call {
            if (closed.get()) throw IOException("HTTP media source is closed")
            val call = client.newCall(request)
            activeCalls += call
            if (closed.get()) {
                activeCalls.remove(call)
                call.cancel()
                throw IOException("HTTP media source is closed")
            }
            return call
        }

        private fun closeActiveStreamLocked() {
            val call = activeCall
            val response = activeResponse
            activeCall = null
            activeResponse = null
            activeStream = null
            streamPos = 0L
            streamEndExclusive = 0L
            if (call != null) {
                activeCalls.remove(call)
            }
            runCatching { response?.close() }
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
                closeActiveStreamLocked()
                installed = true
                return true
            } finally {
                if (!installed) runCatching { Files.deleteIfExists(path) }
            }
        }

        private inline fun <T> executeRequest(request: Request, block: (Response) -> T): T {
            val call = createTrackedCall(request)
            return try {
                call.execute().use(block)
            } finally {
                activeCalls.remove(call)
            }
        }

        private fun isSafeRedirectTarget(originalUrl: String, finalUrl: String): Boolean {
            if (originalUrl == finalUrl) return false
            val originalUri = runCatching { URI(originalUrl) }.getOrNull() ?: return false
            val finalUri = runCatching { URI(finalUrl) }.getOrNull() ?: return false
            val originalScheme = originalUri.scheme?.lowercase() ?: return false
            val finalScheme = finalUri.scheme?.lowercase() ?: return false
            if (originalScheme == "https" && finalScheme != "https") return false
            if (finalScheme !in setOf("http", "https")) return false
            val originalHost = originalUri.host?.lowercase().orEmpty()
            val finalHost = finalUri.host?.lowercase().orEmpty()
            if (finalHost.isBlank()) return false
            val originalIsGoogleVideo = originalHost == "googlevideo.com" || originalHost.endsWith(".googlevideo.com")
            val finalIsGoogleVideo = finalHost == "googlevideo.com" || finalHost.endsWith(".googlevideo.com")
            return !originalIsGoogleVideo || finalIsGoogleVideo
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            activeCalls.toList().forEach(Call::cancel)
            synchronized(lock) {
                closeActiveStreamLocked()
                runCatching { fallbackChannel?.close() }
                fallbackChannel = null
                fallbackPath?.let { runCatching { Files.deleteIfExists(it) } }
                fallbackPath = null
                headCache = null
                headCacheLen = 0
                tailCache = null
                tailCacheLen = 0
                tailCacheStart = -1L
                opened = false
            }
        }
    }

    private companion object {
        const val INITIAL_WINDOW_SIZE = 64 * 1024
        const val RAMP_WINDOW_SIZE = 256 * 1024
        const val DEFAULT_WINDOW_SIZE = 512 * 1024
        const val HEAD_CACHE_MAX_BYTES = 128 * 1024
        const val TAIL_CACHE_MAX_BYTES = 64 * 1024
        const val MAX_FORWARD_SKIP_BYTES = 16 * 1024L
        const val MAX_FETCH_ATTEMPTS = 3
        const val FETCH_RETRY_DELAY_MS = 100L
        const val MAX_FULL_OBJECT_FALLBACK_BYTES = 32L * 1024L * 1024L
        const val FALLBACK_COPY_BUFFER_SIZE = 64 * 1024
        val FULL_OBJECT_FALLBACK_STATUS_CODES = setOf(403, 416)
        val REDIRECT_FALLBACK_STATUS_CODES = setOf(403, 404, 410, 416)
    }
}

/** One reusable HTTP transport per playback controller; individual media never own its pool. */
internal class VlcjRangeHttpTransport(
    internal val client: OkHttpClient = newRangeHttpClient(),
    private val ownsClient: Boolean = true,
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
                while (totalLength == null || offset < totalLength) {
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
                        if (totalLength != null && offset > totalLength) {
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
        if (ownsClient) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            runCatching { client.cache?.close() }
        }
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

internal fun newRangeHttpClient(base: OkHttpClient = OkHttpClient()): OkHttpClient =
    base.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

private class RangeHttpException(
    val httpStatus: Int,
) : IOException("YouTube media range request failed with HTTP $httpStatus")

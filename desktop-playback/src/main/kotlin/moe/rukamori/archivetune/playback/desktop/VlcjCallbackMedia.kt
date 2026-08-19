/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
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
) : SeekableCallbackMedia(), AutoCloseable {
    private val source = RangeMediaSource(url, headers, windowSize, httpClient)
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
        try {
            source.read(buffer, bufferSize)
        } catch (failure: RangeHttpException) {
            onHttpFailure(failure.httpStatus)
            throw failure
        }

    override fun onSeek(offset: Long): Boolean = source.seek(offset)

    override fun onClose() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) source.close()
    }

    private class RangeMediaSource(
        private val url: String,
        private val headers: Map<String, String>,
        private val windowSize: Int,
        private val client: OkHttpClient,
    ) : AutoCloseable {
        private val lock = Any()
        private var contentLength: Long? = null
        private var cursor = 0L
        private var windowStart = -1L
        private var window: ByteArray? = null
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
            if (window == null || cursor < windowStart || cursor >= windowStart + window!!.size) {
                fetchWindow(cursor)
            }
            val activeWindow = window ?: throw IOException("HTTP media window is unavailable")
            val offset = (cursor - windowStart).toInt()
            val available = activeWindow.size - offset
            if (available <= 0) return -1
            val count = minOf(length, available)
            System.arraycopy(activeWindow, offset, buffer, 0, count)
            cursor += count
            count
        }

        @Throws(IOException::class)
        private fun fetchWindow(offset: Long) {
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
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (
                                response.code in FULL_OBJECT_FALLBACK_STATUS_CODES &&
                                fetchWholeObjectFallback(offset)
                            ) {
                                return
                            }
                            throw RangeHttpException(response.code)
                        }
                        val bytes = response.body.bytes()
                        if (bytes.isEmpty() && contentLength?.let { offset < it } != false) {
                            throw IOException("YouTube returned an empty media range")
                        }
                        if (response.code == 206) {
                            contentLength = response.header("Content-Range")
                                ?.substringAfter('/')
                                ?.toLongOrNull()
                            windowStart = response.header("Content-Range")
                                ?.substringBefore('-')
                                ?.substringAfter("bytes ")
                                ?.toLongOrNull()
                                ?: offset
                        } else {
                            contentLength = bytes.size.toLong()
                            windowStart = 0L
                        }
                        window = bytes
                        return
                    }
                } catch (failure: IOException) {
                    lastFailure = failure
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
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return false
                    val declaredLength = response.body.contentLength()
                    if (declaredLength < 0L || declaredLength > MAX_FULL_OBJECT_FALLBACK_BYTES) return false
                    val bytes = response.body.bytes()
                    if (bytes.size.toLong() > MAX_FULL_OBJECT_FALLBACK_BYTES) return false
                    if (requiredOffset > bytes.size.toLong()) return false
                    contentLength = bytes.size.toLong()
                    windowStart = 0L
                    window = bytes
                    true
                }
            } catch (_: IOException) {
                false
            }
        }

        // The controller-scoped transport owns the shared OkHttpClient lifecycle.
        override fun close() = Unit
    }

    private companion object {
        const val DEFAULT_WINDOW_SIZE = 512 * 1024
        const val MAX_FETCH_ATTEMPTS = 3
        const val FETCH_RETRY_DELAY_MS = 100L
        const val MAX_FULL_OBJECT_FALLBACK_BYTES = 32L * 1024L * 1024L
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        runCatching { client.cache?.close() }
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

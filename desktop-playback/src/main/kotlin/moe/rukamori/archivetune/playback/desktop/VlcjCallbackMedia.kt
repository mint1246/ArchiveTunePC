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
class VlcjRangeCallbackMedia(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val onHttpFailure: (Int) -> Unit = {},
) : SeekableCallbackMedia(), AutoCloseable {
    private val source = RangeMediaSource(url, headers, windowSize)
    private val closed = AtomicBoolean(false)

    override fun onGetSize(): Long = source.open() ?: 0L

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
    ) : AutoCloseable {
        private val lock = Any()
        private val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
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
                } catch (_: IOException) {
                    return null
                }
            }
            contentLength ?: window?.size?.toLong()
        }

        fun seek(offset: Long): Boolean = synchronized(lock) {
            if (offset < 0L) return false
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

        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_SIZE = 512 * 1024
        const val MAX_FETCH_ATTEMPTS = 3
        const val FETCH_RETRY_DELAY_MS = 100L
    }
}

private class RangeHttpException(
    val httpStatus: Int,
) : IOException("YouTube media range request failed with HTTP $httpStatus")

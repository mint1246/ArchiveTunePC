/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.co.caprica.vlcj.binding.internal.libvlc_media_close_cb
import uk.co.caprica.vlcj.binding.internal.libvlc_media_open_cb
import uk.co.caprica.vlcj.binding.internal.libvlc_media_read_cb
import uk.co.caprica.vlcj.binding.internal.libvlc_media_seek_cb
import uk.co.caprica.vlcj.binding.support.types.size_t
import uk.co.caprica.vlcj.media.callback.CallbackMedia

/**
 * Application-provided ranged HTTP transport used by [VlcjPlaybackController] for signed
 * Googlevideo streams. OkHttp owns the request headers/range semantics while libVLC only receives
 * the resulting bytes through its callback-media API.
 */
class VlcjRangeCallbackMedia(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) : CallbackMedia, AutoCloseable {
    private val source = RangeMediaSource(url, headers, windowSize)
    private val closed = AtomicBoolean(false)

    private val openCallback = object : libvlc_media_open_cb {
        override fun `open`(
            opaque: Pointer?,
            datap: PointerByReference?,
            sizep: LongByReference?,
        ): Int {
            val contentLength = source.open() ?: return -1
            sizep?.value = contentLength
            return 0
        }
    }

    private val readCallback = object : libvlc_media_read_cb {
        override fun read(opaque: Pointer?, buf: Pointer?, len: size_t): size_t {
            if (buf == null) return size_t(0L)
            val requested = len.toString().toLongOrNull()?.toInt()?.coerceIn(0, MAX_READ) ?: return size_t(0L)
            val bytes = ByteArray(requested)
            val count = source.read(bytes, requested)
            if (count > 0) buf.write(0L, bytes, 0, count)
            return size_t(count.toLong())
        }
    }

    private val seekCallback = object : libvlc_media_seek_cb {
        override fun seek(opaque: Pointer?, offset: Long): Int =
            if (source.seek(offset)) 0 else -1
    }

    private val closeCallback = object : libvlc_media_close_cb {
        override fun close(opaque: Pointer?) {
            close()
        }
    }

    override fun getOpen(): libvlc_media_open_cb = openCallback
    override fun getRead(): libvlc_media_read_cb = readCallback
    override fun getSeek(): libvlc_media_seek_cb = seekCallback
    override fun getClose(): libvlc_media_close_cb = closeCallback
    override fun getOpaque(): Pointer? = Pointer.NULL

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

        fun open(): Long? = synchronized(lock) {
            if (window == null) fetchWindow(0L)
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

        fun read(buffer: ByteArray, length: Int): Int = synchronized(lock) {
            if (contentLength != null && cursor >= contentLength!!) return 0
            if (window == null || cursor < windowStart || cursor >= windowStart + window!!.size) {
                if (!fetchWindow(cursor)) return 0
            }
            val activeWindow = window ?: return 0
            val offset = (cursor - windowStart).toInt()
            val available = activeWindow.size - offset
            if (available <= 0) return 0
            val count = minOf(length, available)
            System.arraycopy(activeWindow, offset, buffer, 0, count)
            cursor += count
            count
        }

        private fun fetchWindow(offset: Long): Boolean {
            val end = offset + windowSize - 1
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
            return runCatching {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) return false
                    val bytes = response.body.bytes()
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
                    true
                }
            }.getOrDefault(false)
        }

        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_SIZE = 512 * 1024
        const val MAX_READ = 1 shl 20
    }
}

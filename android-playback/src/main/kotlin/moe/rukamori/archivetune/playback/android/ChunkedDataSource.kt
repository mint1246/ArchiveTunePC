/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.android

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException

/**
 * Splits large media reads into bounded HTTP range chunks with adaptive startup ramping
 * (`128 KiB -> 512 KiB -> 2 MiB -> chunkLength`).
 *
 * Starting with a compact initial range prevents CDN burst traffic from flooding slow mobile
 * pipes (bufferbloat) when users skip, seek, or switch tracks while keeping steady-state
 * streaming at full [chunkLength] over the warm keep-alive socket and cached redirect host.
 */
@UnstableApi
internal class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkLength: Long,
    private val initialChunkLength: Long = DEFAULT_INITIAL_CHUNK_LENGTH,
) : DataSource {
    private var request: DataSpec? = null
    private var bytesRead = 0L
    private var chunkIndex = 0
    private var chunkBytesRemaining = C.LENGTH_UNSET.toLong()
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(request == null)
        request = dataSpec
        bytesRead = 0L
        chunkIndex = 0
        val resolvedLength = openNextChunk(dataSpec)
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) resolvedLength else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val dataSpec = checkNotNull(request)
        if (dataSpec.length != C.LENGTH_UNSET.toLong() && bytesRead == dataSpec.length) {
            return C.RESULT_END_OF_INPUT
        }
        if (chunkBytesRemaining == 0L) {
            closeUpstream()
            openNextChunk(dataSpec.subrange(bytesRead))
        }
        val readLength =
            if (chunkBytesRemaining == C.LENGTH_UNSET.toLong()) {
                length
            } else {
                minOf(length.toLong(), chunkBytesRemaining).toInt()
            }
        val count = upstream.read(buffer, offset, readLength)
        if (count == C.RESULT_END_OF_INPUT) {
            if (chunkBytesRemaining > 0L) {
                throw EOFException("Audio stream ended before the requested range was complete")
            }
            return count
        }
        bytesRead += count
        if (chunkBytesRemaining != C.LENGTH_UNSET.toLong()) {
            chunkBytesRemaining -= count
        }
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        request = null
        bytesRead = 0L
        chunkIndex = 0
        chunkBytesRemaining = C.LENGTH_UNSET.toLong()
        closeUpstream()
    }

    private fun openNextChunk(dataSpec: DataSpec): Long {
        val targetChunkSize = nextChunkLimit(chunkIndex++, chunkLength, initialChunkLength)
        val chunkSpec = if (dataSpec.length > 0L && targetChunkSize > 0L && dataSpec.position >= 0L) {
            dataSpec.subrange(0L, minOf(dataSpec.length, targetChunkSize))
        } else {
            dataSpec
        }
        chunkBytesRemaining = chunkSpec.length
        upstreamOpen = true
        return upstream.open(chunkSpec)
    }

    private fun closeUpstream() {
        if (!upstreamOpen) return
        upstreamOpen = false
        upstream.close()
    }

    companion object {
        const val DEFAULT_INITIAL_CHUNK_LENGTH: Long = 128L * 1024L

        internal fun nextChunkLimit(
            chunkIndex: Int,
            maxChunkLength: Long,
            initialChunkLength: Long = DEFAULT_INITIAL_CHUNK_LENGTH,
        ): Long {
            if (maxChunkLength <= 0L) return maxChunkLength
            val base = initialChunkLength.coerceIn(1L, maxChunkLength)
            val multiplier =
                when (chunkIndex) {
                    0 -> 1L
                    1 -> 4L
                    2 -> 16L
                    else -> Long.MAX_VALUE
                }
            if (multiplier == Long.MAX_VALUE) return maxChunkLength
            val scaled =
                if (base > maxChunkLength / multiplier) {
                    maxChunkLength
                } else {
                    base * multiplier
                }
            return minOf(scaled, maxChunkLength)
        }
    }
}

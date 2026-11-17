/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.datasource.cache.Cache
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.sdk.stems.floatToPcm16
import moe.rukamori.archivetune.sdk.stems.resampleStereoPcm16ToWav
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

data class AndroidStemInput(
    val uri: Uri,
    val requestHeaders: Map<String, String> = emptyMap(),
    val cache: Cache? = null,
    val cacheKey: String? = null,
    val cachedContentLength: Long? = null,
)

internal data class AndroidCanonicalPcm(
    val file: File,
    val frames: Int,
)

/** Android MediaExtractor/MediaCodec decode followed by deterministic stereo 44.1 kHz resampling. */
internal class AndroidStemPcmDecoder(private val context: Context) {
    private val streamHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamOkHttpProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun decode(
        input: AndroidStemInput,
        outputWav: File,
        onStatus: (String) -> Unit = {},
    ): AndroidCanonicalPcm {
        outputWav.parentFile?.mkdirs()
        val raw = File(outputWav.parentFile, ".${outputWav.name}.source-pcm.part")
        val materializedSource = File(outputWav.parentFile, ".${outputWav.name}.source-media.part")
        raw.delete()
        materializedSource.delete()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val decodeStarted = System.nanoTime()
            val decoderInput = materializeInput(input, materializedSource, onStatus)
            onStatus("Decoding source audio")
            setExtractorDataSource(extractor, decoderInput)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("Input contains no decodable audio track")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = checkNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            var wroteFrames = 0L
            FileOutputStream(raw).buffered(256 * 1024).use { rawOutput ->
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(inputIndex))
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            check(wroteFrames == 0L || (newRate == sampleRate && newChannels == channelCount)) {
                                "Decoder changed PCM format mid-stream"
                            }
                            sampleRate = newRate
                            channelCount = newChannels
                            pcmEncoding =
                                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }
                        }

                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = checkNotNull(codec.getOutputBuffer(outputIndex)).duplicate().order(ByteOrder.nativeOrder())
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                wroteFrames += writeStereoPcm16(buffer.slice().order(ByteOrder.nativeOrder()), pcmEncoding, channelCount, rawOutput)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            check(wroteFrames > 0) { "Audio decoder produced no PCM" }
            val codecElapsedMs = elapsedMs(decodeStarted)
            Timber.tag(LOG_TAG).i(
                "stem decode produced %d source frames at %d Hz in %d ms",
                wroteFrames,
                sampleRate,
                codecElapsedMs,
            )
            onStatus("Resampling audio to 44.1 kHz")
            val resampleStarted = System.nanoTime()
            val targetFrames = resampleStereoPcm16ToWav(raw, sampleRate, wroteFrames, outputWav) { done, total ->
                onStatus("Resampling audio to 44.1 kHz · ${done / 44100}s / ${total / 44100}s")
            }
            Timber.tag(LOG_TAG).i(
                "stem resample produced %d frames in %d ms",
                targetFrames,
                elapsedMs(resampleStarted),
            )
            return AndroidCanonicalPcm(outputWav, targetFrames)
        } finally {
            raw.delete()
            materializedSource.delete()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private suspend fun materializeInput(
        input: AndroidStemInput,
        destination: File,
        onStatus: (String) -> Unit,
    ): AndroidStemInput {
        val cache = input.cache
        val cacheKey = input.cacheKey
        val cachedLength = input.cachedContentLength
        if (cache != null && !cacheKey.isNullOrBlank() && cachedLength != null && cachedLength > 0L && cache.isCached(cacheKey, 0L, cachedLength)) {
            return withContext(Dispatchers.IO) {
                onStatus("Reading downloaded audio")
                materializeCachedInput(cache, cacheKey, cachedLength, destination)
                Timber.tag(LOG_TAG).i("materialized downloaded stem source key=%s bytes=%d", cacheKey, cachedLength)
                AndroidStemInput(Uri.fromFile(destination))
            }
        }

        val scheme = input.uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return input

        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val requestBuilder = Request.Builder().url(input.uri.toString())
            input.requestHeaders.forEach { (name, value) ->
                runCatching { requestBuilder.header(name, value) }
            }
            val call = streamHttpClient.newCall(requestBuilder.build())
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Stem source request failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Stem source response body is empty")
                val totalBytes = body.contentLength().takeIf { it > 0L }
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).buffered(256 * 1024).use { output ->
                    body.byteStream().use { source ->
                        val buffer = ByteArray(256 * 1024)
                        var downloaded = 0L
                        var lastReportedMiB = -1L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val downloadedMiB = downloaded / (1024L * 1024L)
                            if (downloadedMiB != lastReportedMiB) {
                                lastReportedMiB = downloadedMiB
                                onStatus(
                                    if (totalBytes != null) {
                                        "Fetching source audio · ${formatMiB(downloaded)} / ${formatMiB(totalBytes)}"
                                    } else {
                                        "Fetching source audio · ${formatMiB(downloaded)}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
            check(destination.length() > 0L) { "Stem source download produced an empty file" }
            Timber.tag(LOG_TAG).i(
                "stem source materialized %.2f MiB in %d ms",
                destination.length() / (1024.0 * 1024.0),
                elapsedMs(started),
            )
            AndroidStemInput(Uri.fromFile(destination))
        }
    }

    private suspend fun materializeCachedInput(
        cache: Cache,
        key: String,
        contentLength: Long,
        destination: File,
    ) {
        val spans = cache.getCachedSpans(key).sortedBy { it.position }
        var cursor = 0L
        val copyBuffer = ByteArray(256 * 1024)
        FileOutputStream(destination).buffered(256 * 1024).use { output ->
            for (span in spans) {
                currentCoroutineContext().ensureActive()
                if (cursor >= contentLength) break
                val spanEnd = span.position + span.length
                if (spanEnd <= cursor) continue
                check(span.position <= cursor) { "Downloaded audio cache has a gap at byte $cursor" }
                val file = span.file ?: error("Downloaded audio cache span has no backing file")
                val sourceOffset = (cursor - span.position).coerceAtLeast(0L)
                var remaining = minOf(span.length - sourceOffset, contentLength - cursor)
                FileInputStream(file).use { input ->
                    input.channel.position(sourceOffset)
                    while (remaining > 0L) {
                        currentCoroutineContext().ensureActive()
                        val wanted = minOf(copyBuffer.size.toLong(), remaining).toInt()
                        val read = input.read(copyBuffer, 0, wanted)
                        check(read > 0) { "Downloaded audio cache span ended early" }
                        output.write(copyBuffer, 0, read)
                        cursor += read
                        remaining -= read
                    }
                }
            }
        }
        check(cursor == contentLength) { "Downloaded audio cache is incomplete ($cursor / $contentLength bytes)" }
    }

    private fun setExtractorDataSource(extractor: MediaExtractor, input: AndroidStemInput) {
        val scheme = input.uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file" || scheme == "android.resource") {
            extractor.setDataSource(context, input.uri, input.requestHeaders)
        } else {
            extractor.setDataSource(input.uri.toString(), input.requestHeaders)
        }
    }

    private companion object {
        const val LOG_TAG = "StemDecode"
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

private fun writeStereoPcm16(
    buffer: ByteBuffer,
    encoding: Int,
    channels: Int,
    output: java.io.OutputStream,
): Int {
    require(channels > 0)
    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
    val frameBytes = bytesPerSample * channels
    val frames = buffer.remaining() / frameBytes
    val encoded = ByteArray(frames * 4)
    var write = 0
    repeat(frames) {
        var left = 0f
        var right = 0f
        repeat(channels) { channel ->
            val sample =
                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    else -> error("Unsupported decoder PCM encoding $encoding")
                }
            if (channel == 0) left = sample
            if (channel == 1) right = sample
        }
        if (channels == 1) right = left
        val l = floatToPcm16(left)
        val r = floatToPcm16(right)
        encoded[write++] = l.toByte()
        encoded[write++] = (l.toInt() ushr 8).toByte()
        encoded[write++] = r.toByte()
        encoded[write++] = (r.toInt() ushr 8).toByte()
    }
    output.write(encoded)
    return frames
}

private fun elapsedMs(startedNanos: Long): Long =
    ((System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L)

private fun formatMiB(bytes: Long): String = "%.1f MB".format(bytes.coerceAtLeast(0L) / (1024.0 * 1024.0))

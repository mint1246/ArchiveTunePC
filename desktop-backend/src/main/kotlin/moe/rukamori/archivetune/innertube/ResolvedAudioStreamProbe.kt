/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Redaction-safe result of the ranged requests used to validate a freshly resolved stream. */
data class AudioStreamProbeResult(
    val httpStatus: Int,
    val bytesRead: Int,
    val rangedResponse: Boolean,
)

/** HTTP-only probe failure; deliberately carries no signed URL or request headers. */
class AudioStreamProbeHttpException(
    val httpStatus: Int,
) : IllegalStateException("Resolved audio stream probe returned HTTP $httpStatus")

/**
 * Verifies that the exact URL and headers handed to the desktop player are usable for more than the
 * first media range. libVLC seeks while opening WebM/MP4 streams, so a URL that accepts bytes at
 * offset zero but rejects a later range can otherwise begin playing and fail a moment later. Probe
 * the first bytes, then (when the total size is known) a tiny range at the end of the object before
 * returning the stream to the player.
 *
 * The probe follows no redirects and never includes the signed URL, headers, cookies, or tokens in
 * an exception message.
 */
fun probeResolvedAudioStream(stream: ResolvedAudioStream): Result<AudioStreamProbeResult> =
    runCatching {
        val first = probeRange(stream, 0L, MAX_PROBE_BYTES)
        val totalLength = first.contentRangeTotal
        if (totalLength != null && totalLength > first.bytesRead) {
            val tailStart = (totalLength - MAX_PROBE_BYTES).coerceAtLeast(first.bytesRead.toLong())
            probeRange(stream, tailStart, MAX_PROBE_BYTES)
        }
        AudioStreamProbeResult(
            httpStatus = first.httpStatus,
            bytesRead = first.bytesRead,
            rangedResponse = first.rangedResponse,
        )
    }

/** The process resolver's CDN validation must also stop promptly on queue changes/cancellation. */
suspend fun probeResolvedAudioStreamCancellable(stream: ResolvedAudioStream): Result<AudioStreamProbeResult> =
    try {
        val first = probeRangeCancellable(stream, 0L)
        first.contentRangeTotal?.takeIf { it > first.bytesRead }?.let { length ->
            probeRangeCancellable(stream, (length - MAX_PROBE_BYTES).coerceAtLeast(first.bytesRead.toLong()))
        }
        Result.success(AudioStreamProbeResult(first.httpStatus, first.bytesRead, first.rangedResponse))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: AudioStreamProbeHttpException) {
        Result.failure(failure)
    } catch (_: Exception) {
        Result.failure(IOException("Resolved audio stream probe failed"))
    }

private suspend fun probeRangeCancellable(stream: ResolvedAudioStream, offset: Long): RangeProbeResult {
    val request = Request.Builder().url(stream.url)
        .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
        .header("Range", "bytes=$offset-${offset + MAX_PROBE_BYTES - 1}")
        .build()
    val result = suspendCancellableCoroutine<Result<RangeProbeResult>> { continuation ->
        val call = PROBE_CLIENT.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(Result.failure(IOException("Resolved audio stream probe failed")))
            }

            override fun onResponse(call: Call, response: Response) {
                val probed = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw AudioStreamProbeHttpException(it.code)
                        val bytesRead = it.body.byteStream().readNBytes(MAX_PROBE_BYTES).size
                        check(bytesRead > 0) { "Resolved audio stream probe returned no audio bytes" }
                        val range = it.header("Content-Range")
                        RangeProbeResult(it.code, bytesRead, it.code == 206 || range != null,
                            range?.substringAfterLast('/')?.toLongOrNull())
                    }
                }
                continuation.resume(probed)
            }
        })
    }
    return result.getOrThrow()
}

private data class RangeProbeResult(
    val httpStatus: Int,
    val bytesRead: Int,
    val rangedResponse: Boolean,
    val contentRangeTotal: Long?,
)

private fun probeRange(
    stream: ResolvedAudioStream,
    offset: Long,
    maxBytes: Int,
): RangeProbeResult {
    val endInclusive = offset + maxBytes - 1L
    val request =
        Request.Builder()
            .url(stream.url)
            .header("Range", "bytes=$offset-$endInclusive")
            .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
    PROBE_CLIENT.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw AudioStreamProbeHttpException(response.code)
        val bytesRead = response.body.byteStream().readNBytes(maxBytes).size
        check(bytesRead > 0) { "Resolved audio stream probe returned no audio bytes" }
        val contentRange = response.header("Content-Range")
        return RangeProbeResult(
            httpStatus = response.code,
            bytesRead = bytesRead,
            rangedResponse = response.code == 206 || contentRange != null,
            contentRangeTotal = contentRange
                ?.substringAfterLast('/', missingDelimiterValue = "")
                ?.takeUnless { it == "*" }
                ?.toLongOrNull(),
        )
    }
}

private const val MAX_PROBE_BYTES = 1_024

private val PROBE_CLIENT: OkHttpClient =
    desktopStreamHttpClient().newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // This probe runs inside the per-client resolver race. A CDN that cannot serve the first
        // kilobyte promptly should lose to another YouTube client instead of stalling the wave.
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

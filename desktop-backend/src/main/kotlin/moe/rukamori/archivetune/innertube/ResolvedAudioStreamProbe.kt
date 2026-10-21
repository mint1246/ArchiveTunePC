/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.innertube

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
 * The probe follows the same HTTP redirects as libVLC's range transport, and never includes the
 * signed URL, headers, cookies, or tokens in an exception message.
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
    OkHttpClient.Builder()
        // VLC's VlcjCallbackMedia enables both redirect modes for the same media URL. Rejecting a
        // playable 301/302 here would discard a stream the player can actually open.
        .followRedirects(true)
        .followSslRedirects(true)
        // This probe runs inside the per-client resolver race. A CDN that cannot serve the first
        // kilobyte promptly should lose to another YouTube client instead of stalling the wave.
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

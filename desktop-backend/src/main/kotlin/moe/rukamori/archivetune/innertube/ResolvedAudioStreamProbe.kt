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

/** Redaction-safe result of a tiny ranged request against a freshly resolved stream. */
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
 * Verifies that the exact URL and headers handed to the desktop player are usable. It requests at
 * most [MAX_PROBE_BYTES] bytes, follows no redirects, and never includes the signed URL, headers,
 * cookies, or tokens in an exception message.
 */
fun probeResolvedAudioStream(stream: ResolvedAudioStream): Result<AudioStreamProbeResult> =
    runCatching {
        val request =
            Request.Builder()
                .url(stream.url)
                .header("Range", "bytes=0-${MAX_PROBE_BYTES - 1}")
                .apply { stream.requestHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
        PROBE_CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AudioStreamProbeHttpException(response.code)
            val bytesRead = response.body.byteStream().readNBytes(MAX_PROBE_BYTES).size
            check(bytesRead > 0) { "Resolved audio stream probe returned no audio bytes" }
            AudioStreamProbeResult(
                httpStatus = response.code,
                bytesRead = bytesRead,
                rangedResponse = response.code == 206 || response.header("Content-Range") != null,
            )
        }
    }

private const val MAX_PROBE_BYTES = 1_024

private val PROBE_CLIENT: OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

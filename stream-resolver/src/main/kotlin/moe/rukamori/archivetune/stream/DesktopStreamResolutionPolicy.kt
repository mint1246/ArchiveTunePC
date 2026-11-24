/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Developer/benchmark selection; playback and download interfaces remain the same. */
enum class DesktopStreamResolutionMode { LEGACY, YT_DLP, HYBRID }

fun configuredDesktopStreamResolutionMode(
    value: String? = System.getProperty("archivetune.streamResolver") ?: System.getenv("ARCHIVETUNE_STREAM_RESOLVER"),
): DesktopStreamResolutionMode =
    when (value?.trim()?.lowercase(Locale.ROOT)) {
        null, "", "hybrid" -> DesktopStreamResolutionMode.HYBRID
        "ytdlp" -> DesktopStreamResolutionMode.YT_DLP
        "legacy" -> DesktopStreamResolutionMode.LEGACY
        else -> throw IllegalArgumentException("Unknown desktop stream resolver mode")
    }

/**
 * The upstream extractor owns compatibility recovery. The optional fast path uses the
 * existing native player response, accepting only an already usable audio URL. Signature,
 * throttling and proof challenges always go to upstream instead of native deciphering.
 * Its local timeout must leave the caller's overall budget available for upstream.
 */
suspend fun <T> resolveDesktopStream(
    mode: DesktopStreamResolutionMode,
    nativeTimeoutMillis: Long = 6_000,
    native: suspend () -> T,
    upstream: suspend () -> T,
): T = when (mode) {
    DesktopStreamResolutionMode.LEGACY -> native()
    DesktopStreamResolutionMode.YT_DLP -> upstream()
    DesktopStreamResolutionMode.HYBRID -> {
        require(nativeTimeoutMillis > 0)
        val fast = try {
            withTimeoutOrNull(nativeTimeoutMillis) { CompletedNativeStream(native()) }
        } catch (cancellation: CancellationException) {
            // User/preload cancellation and the enclosing deadline never trigger new work.
            throw cancellation
        } catch (_: Exception) {
            null
        }
        // Structured cancellation has finished native cleanup before fallback starts.
        // Surface the upstream error if both fail; retaining native URL-bearing errors
        // as suppressed exceptions would break the subprocess's redaction contract.
        if (fast != null) fast.value else upstream()
    }
}

private data class CompletedNativeStream<T>(val value: T)

/** The non-cookie fast client must not hide premium formats requested by a signed-in user. */
fun allowsDirectAudioFastPath(quality: StreamAudioQuality, auth: PlaybackAuthState): Boolean =
    quality != StreamAudioQuality.HIGHEST || !auth.hasLoginCookie

/** No URL transformation, JS, player-script download or proof-token minting in the fast path. */
fun directAudioFastPathUrl(format: PlayerResponse.StreamingData.Format): String? {
    if (!format.isAudio || !format.mimeType.startsWith("audio/") ||
        format.signatureCipher != null || format.cipher != null) return null
    val raw = format.url ?: return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) return null
    if (uri.rawUserInfo != null || uri.userInfo != null) return null
    val rawQuery = uri.rawQuery
    if (!rawQuery.isNullOrEmpty()) {
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eqIndex = pair.indexOf('=')
            val rawName = if (eqIndex >= 0) pair.substring(0, eqIndex) else pair
            val decodedName = runCatching { URLDecoder.decode(rawName, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            if (decodedName == "n") return null
        }
    }
    return raw
}

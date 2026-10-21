/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Developer/benchmark selection; playback and download interfaces remain the same. */
enum class DesktopStreamResolutionMode { LEGACY, YT_DLP, HYBRID }

internal fun configuredDesktopStreamResolutionMode(): DesktopStreamResolutionMode =
    when ((System.getProperty("archivetune.streamResolver")
        ?: System.getenv("ARCHIVETUNE_STREAM_RESOLVER"))?.trim()?.lowercase(Locale.ROOT)) {
        null, "", "ytdlp" -> DesktopStreamResolutionMode.YT_DLP
        "legacy" -> DesktopStreamResolutionMode.LEGACY
        "hybrid" -> DesktopStreamResolutionMode.HYBRID
        else -> throw IllegalArgumentException("Unknown desktop stream resolver mode")
    }

/**
 * The upstream extractor owns compatibility recovery. The optional fast path uses the
 * existing native player response, accepting only an already usable audio URL. Signature,
 * throttling and proof challenges always go to upstream instead of native deciphering.
 * Its local timeout must leave the caller's overall budget available for upstream.
 */
internal suspend fun <T> resolveDesktopStream(
    mode: DesktopStreamResolutionMode,
    nativeTimeoutMillis: Long = 2_000,
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
internal fun allowsDirectAudioFastPath(quality: JvmAudioQuality, auth: PlaybackAuthState): Boolean =
    quality != JvmAudioQuality.HIGHEST || !auth.hasLoginCookie

/** No URL transformation, JS, player-script download or proof-token minting in the fast path. */
internal fun directAudioFastPathUrl(format: PlayerResponse.StreamingData.Format): String? {
    if (!format.isAudio || !format.mimeType.startsWith("audio/") ||
        format.signatureCipher != null || format.cipher != null) return null
    val raw = format.url ?: return null
    val url = raw.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || "n" in url.queryParameterNames) return null
    return raw
}

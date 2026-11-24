/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.innertube

import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.stream.StreamAudioQuality

/** Developer/benchmark selection; playback and download interfaces remain the same. */
typealias DesktopStreamResolutionMode = moe.rukamori.archivetune.stream.DesktopStreamResolutionMode

internal fun configuredDesktopStreamResolutionMode(
    value: String? = System.getProperty("archivetune.streamResolver") ?: System.getenv("ARCHIVETUNE_STREAM_RESOLVER"),
): DesktopStreamResolutionMode =
    moe.rukamori.archivetune.stream.configuredDesktopStreamResolutionMode(value)

/**
 * The upstream extractor owns compatibility recovery. The optional fast path uses the
 * existing native player response, accepting only an already usable audio URL. Signature,
 * throttling and proof challenges always go to upstream instead of native deciphering.
 * Its local timeout must leave the caller's overall budget available for upstream.
 */
internal suspend fun <T> resolveDesktopStream(
    mode: DesktopStreamResolutionMode,
    nativeTimeoutMillis: Long = 6_000,
    native: suspend () -> T,
    upstream: suspend () -> T,
): T =
    moe.rukamori.archivetune.stream.resolveDesktopStream(
        mode = mode,
        nativeTimeoutMillis = nativeTimeoutMillis,
        native = native,
        upstream = upstream,
    )

/** The non-cookie fast client must not hide premium formats requested by a signed-in user. */
internal fun allowsDirectAudioFastPath(quality: JvmAudioQuality, auth: PlaybackAuthState): Boolean =
    moe.rukamori.archivetune.stream.allowsDirectAudioFastPath(quality.toStreamAudioQuality(), auth)

/** No URL transformation, JS, player-script download or proof-token minting in the fast path. */
internal fun directAudioFastPathUrl(format: PlayerResponse.StreamingData.Format): String? =
    moe.rukamori.archivetune.stream.directAudioFastPathUrl(format)

private fun JvmAudioQuality.toStreamAudioQuality(): StreamAudioQuality =
    when (this) {
        JvmAudioQuality.LOW -> StreamAudioQuality.LOW
        JvmAudioQuality.HIGH -> StreamAudioQuality.HIGH
        JvmAudioQuality.HIGHEST -> StreamAudioQuality.HIGHEST
    }

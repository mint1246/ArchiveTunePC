/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.innertube

import moe.rukamori.archivetune.stream.StreamAudioQuality
import moe.rukamori.archivetune.stream.YtDlpStreamResolver
import moe.rukamori.archivetune.stream.YtDlpConfiguration
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import moe.rukamori.archivetune.stream.YtDlpException
import moe.rukamori.archivetune.stream.YtDlpFailure
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder

/** Null at the client constructor selects the legacy extractor for controlled comparisons only. */
fun interface DesktopAudioStreamExtractor {
    suspend fun resolve(videoId: String, quality: JvmAudioQuality, authState: PlaybackAuthState): ResolvedAudioStream
}

class YtDlpDesktopStreamExtractor(
    private val resolver: YtDlpStreamResolver,
    private val audioConfigProvider: suspend (String, PlaybackAuthState) -> PlayerResponse.PlayerConfig.AudioConfig? = ::loadAudioConfig,
) : DesktopAudioStreamExtractor {
    constructor(cacheDirectory: File? = null) : this(YtDlpStreamResolver(
        YtDlpConfiguration(cacheDirectory = cacheDirectory?.toPath()?.resolve("yt-dlp")),
    ))

    override suspend fun resolve(videoId: String, quality: JvmAudioQuality, authState: PlaybackAuthState): ResolvedAudioStream = coroutineScope {
        // This metadata request never extracts/deciphers a stream. Keep the existing gain controls
        // useful without delaying resolution when metadata is absent or its endpoint is unhealthy.
        val audioConfig = async {
            withTimeoutOrNull(2_000) {
                try { audioConfigProvider(videoId, authState) }
                catch (cancellation: CancellationException) { throw cancellation }
                catch (_: Exception) { null }
            }
        }
        val excluded = mutableSetOf<String>()
        var probeFailure: Throwable? = null
        repeat(2) { attempt ->
            val extracted = try {
                resolver.resolve(
                    videoId = videoId,
                    quality = when (quality) {
                        JvmAudioQuality.LOW -> StreamAudioQuality.LOW
                        JvmAudioQuality.HIGH -> StreamAudioQuality.HIGH
                        JvmAudioQuality.HIGHEST -> StreamAudioQuality.HIGHEST
                    },
                    cookie = authState.cookie, dataSyncId = authState.dataSyncId,
                    proxyUrl = streamProxyUrl(YouTube.streamProxy, YouTube.proxyUsername, YouTube.proxyPassword),
                    excludedFormatIds = excluded,
                )
            } catch (failure: YtDlpException) {
                if (failure.kind == YtDlpFailure.EXPIRED_STREAM && attempt == 0) return@repeat
                if (failure.kind == YtDlpFailure.NO_AUDIO && probeFailure != null) throw probeFailure!!
                throw failure
            }
            val stream = ResolvedAudioStream(
                videoId = extracted.videoId, url = extracted.url, format = extracted.format,
                videoDetails = extracted.videoDetails, playbackTracking = null, audioConfig = audioConfig.await(),
                clientName = "YT_DLP", clientVersion = resolver.verifyToolchain().ytDlpVersion,
                requestHeaders = extracted.headers, expiresAtEpochMillis = extracted.expiresAtEpochMillis,
                authFingerprint = authState.fingerprint,
            )
            val failure = probeResolvedAudioStreamCancellable(stream).exceptionOrNull()
            if (failure == null) return@coroutineScope stream
            val retryable = failure is IOException ||
                (failure is AudioStreamProbeHttpException && failure.httpStatus in setOf(403, 404, 410, 416, 429, 500, 502, 503))
            if (!retryable || attempt == 1) throw failure
            probeFailure = failure
            // A transient CDN error gets a fresh extraction; a rejected format gets an alternative.
            if (failure is AudioStreamProbeHttpException) excluded += extracted.formatId
        }
        error("No extraction attempt completed")
    }

}

/** An explicit empty proxy prevents ambient proxy variables from overriding the application's route. */
internal fun streamProxyUrl(proxy: Proxy?, username: String?, password: String?): String {
    if (proxy == null || proxy.type() == Proxy.Type.DIRECT) return ""
    val address = proxy.address() as? InetSocketAddress ?: return ""
    val scheme = if (proxy.type() == Proxy.Type.SOCKS) "socks5" else "http"
    val authority = URI(scheme, null, address.hostString, address.port, null, null, null).rawAuthority
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    val credentials = username?.takeIf { it.isNotBlank() }?.let { "${encode(it)}:${encode(password.orEmpty())}@" }.orEmpty()
    return "$scheme://$credentials$authority"
}

private suspend fun loadAudioConfig(videoId: String, authState: PlaybackAuthState): PlayerResponse.PlayerConfig.AudioConfig? {
    val clients = if (authState.hasLoginCookie) listOf(YouTubeClient.WEB_REMIX, YouTubeClient.VISIONOS)
        else listOf(YouTubeClient.VISIONOS, YouTubeClient.WEB_REMIX)
    for (client in clients) {
        val config = YouTube.player(videoId = videoId, client = client,
            setLogin = authState.hasLoginCookie && client.supportsCookieAuthentication, authState = authState)
            .getOrNull()?.playerConfig?.audioConfig
        if (config != null && (config.loudnessDb?.isFinite() == true || config.perceptualLoudnessDb?.isFinite() == true)) return config
    }
    return null
}

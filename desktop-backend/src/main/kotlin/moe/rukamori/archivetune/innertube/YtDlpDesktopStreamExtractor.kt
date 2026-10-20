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
) : DesktopAudioStreamExtractor {
    constructor(cacheDirectory: File? = null) : this(YtDlpStreamResolver(
        YtDlpConfiguration(cacheDirectory = cacheDirectory?.toPath()?.resolve("yt-dlp")),
    ))

    override suspend fun resolve(videoId: String, quality: JvmAudioQuality, authState: PlaybackAuthState): ResolvedAudioStream {
        val extracted = resolver.resolve(
            videoId = videoId,
            quality = StreamAudioQuality.valueOf(quality.name),
            cookie = authState.cookie,
            dataSyncId = authState.dataSyncId,
            proxyUrl = streamProxyUrl(YouTube.streamProxy, YouTube.proxyUsername, YouTube.proxyPassword),
        )
        val stream = ResolvedAudioStream(
            videoId = extracted.videoId, url = extracted.url, format = extracted.format,
            videoDetails = extracted.videoDetails, playbackTracking = null, audioConfig = null,
            clientName = "YT_DLP", clientVersion = resolver.verifyToolchain().ytDlpVersion,
            requestHeaders = extracted.headers, expiresAtEpochMillis = extracted.expiresAtEpochMillis,
            authFingerprint = authState.fingerprint,
        )
        probeResolvedAudioStreamCancellable(stream).getOrThrow()
        return stream
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

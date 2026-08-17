/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stream

import moe.rukamori.archivetune.innertube.NewPipeUtils
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import java.net.URI
import java.util.Locale

enum class StreamAudioQuality {
    AUTO,
    LOW,
    HIGH,
    HIGHEST,
}

enum class StreamClientPreference {
    ANDROID_VR,
    WEB_REMIX,
    ARCHIVETUNE_EXTRACTOR,
    HI_RES_LOSSLESS,
    IOS,
    TVHTML5,
    ANDROID_MUSIC,
}

/**
 * Pure-JVM playback stream policy shared by Android and desktop.
 *
 * Platform layers still own lifecycle-specific behavior such as Android BotGuard recovery,
 * ConnectivityManager, DPAPI session refresh, Media3/VLC handoff, logging, and persistence.
 * Everything that decides which YouTube client/format/URL/header/expiry to use lives here.
 */
object PlaybackStreamResolver {
    const val STREAM_URL_EXPIRY_SAFETY_MS = 60_000L
    private const val DEFAULT_STREAM_EXPIRY_SECONDS = 300

    val fullFallbackClients: List<YouTubeClient> =
        listOf(
            YouTubeClient.WEB_REMIX,
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.IOS,
            YouTubeClient.MOBILE,
            YouTubeClient.ANDROID_MUSIC,
            YouTubeClient.IOS_MUSIC,
            YouTubeClient.ANDROID_CREATOR,
            YouTubeClient.ANDROID_TESTSUITE,
            YouTubeClient.ANDROID_UNPLUGGED,
            YouTubeClient.IPADOS,
            YouTubeClient.VISIONOS,
            YouTubeClient.TVHTML5,
            YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            YouTubeClient.WEB,
            YouTubeClient.WEB_CREATOR,
        )

    /** Smaller profile used by desktop to avoid needless slow fallbacks while keeping the same policy code. */
    val desktopFallbackClients: List<YouTubeClient> =
        listOf(
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.IOS,
            YouTubeClient.ANDROID_MUSIC,
            YouTubeClient.IOS_MUSIC,
            YouTubeClient.MOBILE,
            YouTubeClient.WEB_REMIX,
            YouTubeClient.IPADOS,
            YouTubeClient.VISIONOS,
            YouTubeClient.TVHTML5,
            YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            YouTubeClient.WEB,
        )

    fun hasCompleteWebPlaybackPoToken(authState: PlaybackAuthState): Boolean =
        authState.webClientPoTokenEnabled &&
            !authState.resolvePlayerPoToken(YouTubeClient.WEB_REMIX).isNullOrBlank() &&
            !authState.resolveGvsPoToken(YouTubeClient.WEB_REMIX).isNullOrBlank()

    fun resolvePreferredClient(
        preference: StreamClientPreference,
        authState: PlaybackAuthState,
    ): YouTubeClient =
        when (preference) {
            StreamClientPreference.ANDROID_VR -> {
                if (authState.hasPlaybackLoginContext && !hasCompleteWebPlaybackPoToken(authState)) {
                    YouTubeClient.WEB_PRIMARY
                } else {
                    YouTubeClient.ANDROID_VR_1_65_10
                }
            }

            StreamClientPreference.WEB_REMIX -> {
                if (authState.hasPlaybackLoginContext && !hasCompleteWebPlaybackPoToken(authState)) {
                    YouTubeClient.WEB_PRIMARY
                } else {
                    YouTubeClient.WEB_REMIX
                }
            }

            StreamClientPreference.ARCHIVETUNE_EXTRACTOR -> {
                if (authState.hasPlaybackLoginContext) YouTubeClient.ANDROID_MUSIC else YouTubeClient.WEB_REMIX
            }

            StreamClientPreference.HI_RES_LOSSLESS -> YouTubeClient.WEB_REMIX
            StreamClientPreference.IOS -> YouTubeClient.IOS
            StreamClientPreference.TVHTML5 -> YouTubeClient.TVHTML5
            StreamClientPreference.ANDROID_MUSIC -> YouTubeClient.ANDROID_MUSIC
        }

    fun orderClients(
        preference: StreamClientPreference,
        authState: PlaybackAuthState,
        fallbackClients: List<YouTubeClient> = fullFallbackClients,
        lastSuccessfulClientKey: String? = null,
        preferCookieCapableWhenLoggedIn: Boolean = false,
    ): List<YouTubeClient> {
        val preferred = resolvePreferredClient(preference, authState)
        val completeWebPoTokens = hasCompleteWebPlaybackPoToken(authState)
        val lastSuccessful =
            lastSuccessfulClientKey
                ?.let(::normalizeClientKey)
                ?.takeIf(String::isNotEmpty)
                ?.let { key -> fallbackClients.firstOrNull { clientKey(it) == key } }

        val orderedFallbacks =
            if (
                authState.hasPlaybackLoginContext &&
                (completeWebPoTokens || preferCookieCapableWhenLoggedIn)
            ) {
                fallbackClients.filter { it.supportsCookieAuthentication } +
                    fallbackClients.filterNot { it.supportsCookieAuthentication }
            } else {
                fallbackClients.filterNot { it.supportsCookieAuthentication } +
                    fallbackClients.filter { it.supportsCookieAuthentication }
            }

        return buildList {
            add(preferred)
            lastSuccessful?.takeIf { it != preferred }?.let(::add)
            if (authState.hasPlaybackLoginContext && completeWebPoTokens) add(YouTubeClient.WEB_REMIX)
            addAll(orderedFallbacks)
            if (preferred != YouTubeClient.WEB_REMIX) add(YouTubeClient.WEB_REMIX)
        }.distinctBy { it.clientName to it.clientVersion }
    }

    fun selectAudioFormatCandidates(
        playerResponse: PlayerResponse,
        quality: StreamAudioQuality,
        networkMetered: Boolean = false,
        includeMuxedFormats: Boolean = true,
    ): List<PlayerResponse.StreamingData.Format> {
        val streamingData = playerResponse.streamingData ?: return emptyList()
        val sourceFormats =
            if (includeMuxedFormats) {
                streamingData.adaptiveFormats + streamingData.formats.orEmpty()
            } else {
                streamingData.adaptiveFormats
            }
        val audioFormats =
            sourceFormats
                .asSequence()
                .filter { it.isAudio && it.bitrate > 0 }
                .filter { it.url != null || it.signatureCipher != null || it.cipher != null }
                .toList()

        if (audioFormats.isEmpty()) {
            if (!includeMuxedFormats) return emptyList()
            return streamingData.formats.orEmpty()
                .filter { it.url != null && !it.isAudio && it.mimeType.hasAudioCodec() }
                .sortedByDescending { it.bitrate }
        }

        val effectiveQuality =
            when (quality) {
                StreamAudioQuality.AUTO -> if (networkMetered) StreamAudioQuality.HIGH else StreamAudioQuality.HIGHEST
                else -> quality
            }
        val targetBitrate =
            when (effectiveQuality) {
                StreamAudioQuality.LOW -> 70_000
                StreamAudioQuality.HIGH -> 160_000
                StreamAudioQuality.HIGHEST -> null
                StreamAudioQuality.AUTO -> null
            }
        val preferHigher =
            compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                .thenByDescending { it.bitrate }
                .thenByDescending { codecRank(it.mimeType) }
                .thenByDescending { it.audioSampleRate ?: 0 }

        if (targetBitrate == null) return audioFormats.sortedWith(preferHigher)

        val preferred = audioFormats.filter { it.bitrate <= targetBitrate }.sortedWith(preferHigher)
        val fallback =
            audioFormats
                .filter { it.bitrate > targetBitrate }
                .sortedWith(
                    compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                        .thenBy { it.bitrate }
                        .thenByDescending { codecRank(it.mimeType) }
                        .thenByDescending { it.audioSampleRate ?: 0 },
                )
        return preferred + fallback
    }

    suspend fun signatureTimestamp(videoId: String): Result<Int?> =
        NewPipeUtils.getSignatureTimestamp(videoId).map { it }

    suspend fun resolveUrl(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient,
        authState: PlaybackAuthState,
    ): Result<String> =
        NewPipeUtils.getStreamUrl(format, videoId, client, authState).map { patchClientVersion(it, client.clientVersion) }

    fun requestHeaders(client: YouTubeClient): Map<String, String> =
        buildMap {
            put("User-Agent", client.userAgent)
            when {
                client.clientName.uppercase(Locale.US).startsWith("WEB") -> {
                    put("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
                    put("Referer", YouTubeClient.REFERER_YOUTUBE_MUSIC)
                }

                client.clientName.uppercase(Locale.US).startsWith("TVHTML5") -> {
                    put("Origin", YouTubeClient.ORIGIN_YOUTUBE)
                    put("Referer", YouTubeClient.REFERER_YOUTUBE_TV)
                }
            }
        }

    fun resolveExpirySeconds(
        apiExpirySeconds: Int?,
        streamUrl: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        apiExpirySeconds?.takeIf { it > 0 }?.let { return it }
        val expiresAtMs = streamUrl?.let(::extractExpiryTimestampMs)
        val remainingSeconds = expiresAtMs?.minus(nowMs)?.div(1_000L)
        return remainingSeconds?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            ?: DEFAULT_STREAM_EXPIRY_SECONDS
    }

    fun extractExpiryTimestampMs(url: String): Long? =
        runCatching {
            URI(url).rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("expire=") }
                ?.substringAfter('=')
                ?.toLongOrNull()
                ?.times(1_000L)
        }.getOrNull()

    fun isExpiredOrNearExpiredStreamUrl(
        url: String,
        nowMs: Long = System.currentTimeMillis(),
        safetyMs: Long = STREAM_URL_EXPIRY_SAFETY_MS,
    ): Boolean = extractExpiryTimestampMs(url)?.let { it <= nowMs + safetyMs } ?: false

    fun isLikelyPreview(
        format: PlayerResponse.StreamingData.Format,
        expectedDurationMs: Long,
    ): Boolean {
        val approx = format.approxDurationMs?.toLongOrNull() ?: return false
        if (expectedDurationMs < 90_000L) return false
        return approx in 1L..minOf(90_000L, (expectedDurationMs * 9L) / 10L)
    }

    fun shouldSkipCipheredWebCandidate(
        client: YouTubeClient,
        format: PlayerResponse.StreamingData.Format,
        authState: PlaybackAuthState,
    ): Boolean =
        authState.webClientPoTokenEnabled &&
            isWebLikeClient(client) &&
            format.url == null &&
            (format.signatureCipher != null || format.cipher != null) &&
            authState.resolveGvsPoToken(client).isNullOrBlank()

    fun clientKey(client: YouTubeClient): String = normalizeClientKey("${client.clientName}@${client.clientVersion}")

    fun normalizeClientKey(clientKey: String?): String =
        clientKey?.trim()?.takeIf(String::isNotBlank)?.uppercase(Locale.US).orEmpty()

    fun patchClientVersion(url: String, clientVersion: String): String =
        if (url.contains("cver=")) url.replace(Regex("cver=[^&]+"), "cver=$clientVersion") else url

    private fun codecRank(mimeType: String): Int =
        when {
            mimeType.contains("opus", ignoreCase = true) -> 3
            mimeType.contains("mp4a", ignoreCase = true) -> 2
            else -> 1
        }

    private fun String.hasAudioCodec(): Boolean =
        contains("mp4a", ignoreCase = true) ||
            contains("opus", ignoreCase = true) ||
            contains("vorbis", ignoreCase = true) ||
            contains("aac", ignoreCase = true)

    private fun isWebLikeClient(client: YouTubeClient): Boolean {
        val name = client.clientName.uppercase(Locale.US)
        return name.startsWith("WEB") || name.startsWith("TVHTML5") || name == "MWEB"
    }
}

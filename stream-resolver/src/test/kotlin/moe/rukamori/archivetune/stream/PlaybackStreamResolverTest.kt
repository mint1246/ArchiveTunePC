package moe.rukamori.archivetune.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.ResponseContext
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse

class PlaybackStreamResolverTest {
    @Test
    fun highQualityPrefersBestDirectFormatAtOrBelowTarget() {
        val response =
            playerResponse(
                format(251, 155_000, "audio/webm; codecs=\"opus\"", direct = true),
                format(140, 128_000, "audio/mp4; codecs=\"mp4a.40.2\"", direct = true),
                format(141, 256_000, "audio/mp4; codecs=\"mp4a.40.2\"", direct = true),
            )

        assertEquals(
            251,
            PlaybackStreamResolver.selectAudioFormatCandidates(response, StreamAudioQuality.HIGH).first().itag,
        )
    }

    @Test
    fun directUrlWinsOverCipherAtSameBitrate() {
        val response =
            playerResponse(
                format(1, 160_000, "audio/webm; codecs=\"opus\"", direct = false),
                format(2, 160_000, "audio/mp4; codecs=\"mp4a.40.2\"", direct = true),
            )

        assertEquals(
            2,
            PlaybackStreamResolver.selectAudioFormatCandidates(response, StreamAudioQuality.HIGH).first().itag,
        )
    }

    @Test
    fun androidProfileCanExcludeMuxedFallbacks() {
        val muxed = format(18, 96_000, "video/mp4; codecs=\"avc1, mp4a.40.2\"", direct = true, width = 640)
        val response = playerResponse().copy(streamingData = PlayerResponse.StreamingData(formats = listOf(muxed), adaptiveFormats = emptyList()))

        assertTrue(
            PlaybackStreamResolver.selectAudioFormatCandidates(
                response,
                StreamAudioQuality.HIGHEST,
                includeMuxedFormats = false,
            ).isEmpty(),
        )
        assertEquals(
            listOf(muxed),
            PlaybackStreamResolver.selectAudioFormatCandidates(response, StreamAudioQuality.HIGHEST),
        )
    }

    @Test
    fun loggedInWithoutWebProofTokenUsesWebPrimaryAsPreferredClient() {
        val auth = PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "channel").normalized()

        val ordered =
            PlaybackStreamResolver.orderClients(
                preference = StreamClientPreference.WEB_REMIX,
                authState = auth,
            )

        assertEquals(YouTubeClient.WEB_PRIMARY.clientName, ordered.first().clientName)
    }

    @Test
    fun desktopProfileCanKeepCookieCapableFallbacksFirst() {
        val auth = PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "channel").normalized()

        val ordered =
            PlaybackStreamResolver.orderClients(
                preference = StreamClientPreference.WEB_REMIX,
                authState = auth,
                fallbackClients = PlaybackStreamResolver.desktopFallbackClients,
                preferCookieCapableWhenLoggedIn = true,
            )

        assertTrue(ordered.take(2).all { it.supportsCookieAuthentication })
    }

    @Test
    fun expiryAndHeadersAreSharedPolicy() {
        val now = 1_000_000L
        val url = "https://example.test/audio?expire=1120"

        assertEquals(120, PlaybackStreamResolver.resolveExpirySeconds(null, url, now))
        assertFalse(PlaybackStreamResolver.isExpiredOrNearExpiredStreamUrl(url, now, safetyMs = 60_000L))
        assertTrue(PlaybackStreamResolver.isExpiredOrNearExpiredStreamUrl(url, now + 61_000L, safetyMs = 60_000L))

        val webHeaders = PlaybackStreamResolver.requestHeaders(YouTubeClient.WEB_REMIX)
        assertTrue(webHeaders.containsKey("User-Agent"))
        assertTrue(webHeaders.containsKey("Origin"))
        assertTrue(webHeaders.containsKey("Referer"))
        assertFalse(webHeaders.keys.any { it.contains("cookie", ignoreCase = true) })
    }

    private fun playerResponse(vararg formats: PlayerResponse.StreamingData.Format) =
        PlayerResponse(
            responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
            playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
            playerConfig = null,
            streamingData = PlayerResponse.StreamingData(formats = null, adaptiveFormats = formats.toList()),
            videoDetails = null,
            playbackTracking = null,
        )

    private fun format(
        itag: Int,
        bitrate: Int,
        mimeType: String,
        direct: Boolean,
        cipher: Boolean = !direct,
        width: Int? = null,
    ) =
        PlayerResponse.StreamingData.Format(
            itag = itag,
            url = if (direct) "https://example.test/audio" else null,
            mimeType = mimeType,
            bitrate = bitrate,
            width = width,
            height = null,
            contentLength = null,
            quality = "tiny",
            fps = null,
            qualityLabel = null,
            averageBitrate = null,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = null,
            audioSampleRate = 48_000,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = if (cipher) "url=x&s=y" else null,
            cipher = null,
        )
}

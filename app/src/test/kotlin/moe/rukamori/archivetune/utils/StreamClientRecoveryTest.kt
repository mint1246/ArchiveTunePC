/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.innertube.models.YouTubeClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamClientRecoveryTest {
    @Test
    fun androidVr137MediaUrlUsesItsOwnRequestProfile() {
        val url =
            "https://rr1---sn.example.googlevideo.com/videoplayback?c=ANDROID_VR&cver=1.37"
                .toHttpUrl()

        val profile = StreamClientUtils.resolveRequestProfile(url)

        assertEquals(
            StreamClientUtils.buildClientKey(YouTubeClient.ANDROID_VR_NO_AUTH),
            profile.clientKey,
        )
        assertEquals(YouTubeClient.ANDROID_VR_NO_AUTH.userAgent, profile.userAgent)
    }

    @Test
    fun media403OnOneAndroidVrVariantRetiresWholeVrFamilyForThatVideo() {
        val videoId = "media-403-survives-auth-refresh"
        val failedClient = StreamClientUtils.buildClientKey(YouTubeClient.ANDROID_VR_1_65_10)
        val failed =
            YTPlayerUtils.mediaFailureClientKeys(
                failedClient,
            )

        assertTrue(failed.contains(StreamClientUtils.buildClientKey(YouTubeClient.ANDROID_VR_NO_AUTH).uppercase()))
        assertTrue(failed.contains(StreamClientUtils.buildClientKey(YouTubeClient.ANDROID_VR_1_65_10).uppercase()))
        assertTrue(failed.contains(StreamClientUtils.buildClientKey(YouTubeClient.ANDROID_VR_1_61_48).uppercase()))
        assertTrue(failed.contains(StreamClientUtils.buildClientKey(YouTubeClient.ANDROID_VR_1_43_32).uppercase()))

        YTPlayerUtils.markMediaStreamClientFailed(videoId, failedClient, 403)
        assertTrue(YTPlayerUtils.isStreamClientTemporarilyBlocked(videoId, failedClient, "auth-before-refresh"))
        assertTrue(YTPlayerUtils.isStreamClientTemporarilyBlocked(videoId, failedClient, "auth-after-refresh"))
    }
}

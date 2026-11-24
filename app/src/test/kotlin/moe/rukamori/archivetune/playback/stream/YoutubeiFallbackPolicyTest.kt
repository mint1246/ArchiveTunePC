/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiException
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiFailureKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeiFallbackPolicyTest {
    @Test
    fun authenticatedClientErrorsFallBackToAnonymousVisionOs() {
        val fallbackKinds = listOf(
            YoutubeiFailureKind.UNAVAILABLE,
            YoutubeiFailureKind.NO_FORMAT,
            YoutubeiFailureKind.PO_TOKEN,
            YoutubeiFailureKind.DECIPHER,
            YoutubeiFailureKind.HTTP,
        )
        for (kind in fallbackKinds) {
            val failure = YoutubeiException(kind = kind, message = "Video unavailable", httpStatus = if (kind == YoutubeiFailureKind.HTTP) 403 else null)
            assertTrue(
                "Expected fallback for $kind",
                YoutubeiStreamRepository.shouldFallbackToAnonymousVisionOs(hasLoginCookie = true, failure = failure),
            )
            assertFalse(
                "Anonymous request should not retry anonymous fallback",
                YoutubeiStreamRepository.shouldFallbackToAnonymousVisionOs(hasLoginCookie = false, failure = failure),
            )
        }
    }

    @Test
    fun ageRestrictedPrivateMembersAndUnauthorizedDoNotFallBack() {
        val blocked = listOf(
            YoutubeiException(YoutubeiFailureKind.UNAVAILABLE, "This video is private video"),
            YoutubeiException(YoutubeiFailureKind.UNAVAILABLE, "Join this channel — members-only"),
            YoutubeiException(YoutubeiFailureKind.LOGIN_REQUIRED, "Sign in to confirm your age"),
            YoutubeiException(YoutubeiFailureKind.HTTP, "Unauthorized", httpStatus = 401),
            YoutubeiException(YoutubeiFailureKind.NETWORK, "No internet"),
            YoutubeiException(YoutubeiFailureKind.TIMEOUT, "Timed out"),
        )
        for (failure in blocked) {
            assertFalse(
                "Must not fall back for ${failure.kind}: ${failure.message}",
                YoutubeiStreamRepository.shouldFallbackToAnonymousVisionOs(hasLoginCookie = true, failure = failure),
            )
        }
    }
}

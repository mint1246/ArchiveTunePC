/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrackPreResolutionPolicyTest {
    private val first =
        NextTrackPreResolutionCandidate(
            currentIndex = 0,
            currentMediaId = "current",
            nextIndex = 1,
            nextMediaId = "next-a",
            nextUri = "next-a",
        )

    @Test
    fun sameOccurrence_isResolvedOnlyOnce() {
        val policy = NextTrackPreResolutionPolicy()
        val firstAttempt = policy.update(first, isPlaying = true).attempt
        assertNotNull(firstAttempt)
        assertNull(policy.update(first, isPlaying = true).attempt)
        assertTrue(policy.isCurrent(requireNotNull(firstAttempt)))
    }

    @Test
    fun pausedCandidate_doesNotConsumeItsAttempt() {
        val policy = NextTrackPreResolutionPolicy()
        assertNull(policy.update(first, isPlaying = false).attempt)
        assertNotNull(policy.update(first, isPlaying = true).attempt)
    }

    @Test
    fun changedCandidate_fencesOldCacheWrite_andStartsNewAttempt() {
        val policy = NextTrackPreResolutionPolicy()
        val oldAttempt = requireNotNull(policy.update(first, isPlaying = true).attempt)
        val replacement = first.copy(nextMediaId = "next-b", nextUri = "next-b")
        val decision = policy.update(replacement, isPlaying = true)
        assertTrue(decision.candidateChanged)
        assertFalse(policy.isCurrent(oldAttempt))
        assertTrue(policy.isCurrent(requireNotNull(decision.attempt)))
    }

    @Test
    fun invalidation_fencesOutstandingAttempt() {
        val policy = NextTrackPreResolutionPolicy()
        val attempt = requireNotNull(policy.update(first, isPlaying = true).attempt)
        assertTrue(policy.invalidate())
        assertFalse(policy.isCurrent(attempt))
        assertFalse(policy.invalidate())
    }
}

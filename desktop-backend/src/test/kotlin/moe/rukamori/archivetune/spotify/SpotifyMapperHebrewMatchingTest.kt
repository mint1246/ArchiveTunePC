/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMapperHebrewMatchingTest {
    @Test
    fun `hebrew text is preserved during matching`() {
        val source = SpotifyMapper.precompute(
            title = "לא כזה עמוק",
            artist = "כהן",
            durationMs = 180_000,
        )

        val exact = SpotifyMapper.matchScoreComponentsPrecomputed(
            precomputed = source,
            candidateTitle = "לא כזה עמוק",
            candidateArtist = "כהן",
            candidateDurationSec = 180,
        )
        val wrong = SpotifyMapper.matchScoreComponentsPrecomputed(
            precomputed = source,
            candidateTitle = "גאבאגול",
            candidateArtist = "כהן",
            candidateDurationSec = 180,
        )

        assertEquals(1.0, exact.title, 0.0001)
        assertEquals(0.0, wrong.title, 0.0001)
        assertTrue(exact.total > wrong.total)
    }

    @Test
    fun `partial hebrew title is not treated as exact`() {
        val source = SpotifyMapper.precompute(
            title = "אישון לילה",
            artist = "כהן",
            durationMs = 210_000,
        )

        val partial = SpotifyMapper.matchScoreComponentsPrecomputed(
            precomputed = source,
            candidateTitle = "לילה",
            candidateArtist = "כהן",
            candidateDurationSec = 210,
        )

        assertTrue(partial.title < 0.58)
    }

    @Test
    fun `empty normalized strings never score as exact`() {
        val source = SpotifyMapper.precompute(
            title = "!!!",
            artist = "???",
            durationMs = 180_000,
        )

        val score = SpotifyMapper.matchScoreComponentsPrecomputed(
            precomputed = source,
            candidateTitle = "...",
            candidateArtist = "---",
            candidateDurationSec = 180,
        )

        assertEquals(0.0, score.title, 0.0001)
        assertEquals(0.0, score.artist, 0.0001)
    }

    @Test
    fun `latin transliteration can match hebrew metadata`() {
        assertTrue(SpotifyMapper.textSimilarity("Peled", "פלד") > 0.9)
        assertTrue(SpotifyMapper.textSimilarity("Hadag Nahash", "הדג נחש") > 0.9)
        assertTrue(SpotifyMapper.textSimilarity("Parash Boded", "פרש בודד") > 0.7)
    }

    @Test
    fun `cross script fallback does not weaken same script hebrew title discrimination`() {
        val exact = SpotifyMapper.textSimilarity("אישון לילה", "אישון לילה")
        val wrong = SpotifyMapper.textSimilarity("אישון לילה", "לילה")

        assertEquals(1.0, exact, 0.0001)
        assertTrue(wrong < 0.58)
    }
}

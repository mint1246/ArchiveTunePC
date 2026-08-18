/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.spotify

import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import java.text.Normalizer

/**
 * Utility object for creating search queries from Spotify track data.
 * The actual mapping to Metrolist MediaMetadata is done in the app module
 * where MediaMetadata class is available.
 */
object SpotifyMapper {
    // Pre-compiled regex patterns for title normalization (avoids re-creation on each call)
    private val FEAT_PATTERN = Regex("\\(feat\\..*?\\)")
    private val FT_PATTERN = Regex("\\(ft\\..*?\\)")
    private val BRACKET_PATTERN = Regex("\\[.*?]")
    private val REMASTER_PATTERN = Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE)
    private val REMIX_PATTERN = Regex("\\(.*?remix.*?\\)", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_PATTERN = Regex("[^\\p{L}\\p{N}\\s]")
    private val COMBINING_MARK_PATTERN = Regex("\\p{M}+")
    private val MULTI_SPACE_PATTERN = Regex("\\s+")
    private val LATIN_VOWEL_PATTERN = Regex("[aeiou]")
    private val PHONETIC_NON_ALNUM_PATTERN = Regex("[^a-z0-9\\s]")
    private val HEBREW_CHAR_PATTERN = Regex("[\\u0590-\\u05FF]")
    private val LATIN_CHAR_PATTERN = Regex("[a-zA-Z]")

    private const val NORM_CACHE_MAX_SIZE = 256
    private const val EARLY_EXIT_THRESHOLD = 0.95

    /**
     * LRU cache for normalized strings. Avoids re-running 7 regex replacements
     * on the same Spotify title/artist across multiple candidate comparisons.
     * Bounded to [NORM_CACHE_MAX_SIZE] entries to limit memory usage.
     */
    private val normalizeCache =
        object : LinkedHashMap<String, String>(
            NORM_CACHE_MAX_SIZE,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > NORM_CACHE_MAX_SIZE
        }

    /**
     * LRU cache for pre-computed bigram sets. Avoids re-creating Set<String>
     * on every stringSimilarity call for the same normalized string.
     */
    private val bigramCache =
        object : LinkedHashMap<String, Set<String>>(
            NORM_CACHE_MAX_SIZE,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean = size > NORM_CACHE_MAX_SIZE
        }

    private val cacheLock = Any()

    /**
     * Pre-computed data for one side of a match comparison.
     * Created once per Spotify track and reused across all candidates.
     */
    data class PrecomputedTrack(
        val rawTitle: String,
        val normalizedTitle: String,
        val titleBigrams: Set<String>,
        val rawArtist: String,
        val normalizedArtist: String,
        val artistBigrams: Set<String>,
        val durationMs: Int,
    )

    data class MatchScoreComponents(
        val total: Double,
        val title: Double,
        val artist: Double,
        val duration: Double,
    )

    /**
     * Builds a YouTube search query from a Spotify track.
     * The query is optimized for finding the matching song on YouTube Music.
     */
    fun buildSearchQuery(track: SpotifyTrack): String {
        val artist =
            track.artists
                .firstOrNull()
                ?.name
                .orEmpty()
        val title = track.name
        return if (artist.isEmpty()) title else "$artist $title"
    }

    /**
     * Returns the best thumbnail URL from a Spotify playlist, preferring medium resolution.
     */
    fun getPlaylistThumbnail(playlist: SpotifyPlaylist): String? =
        playlist.images.let { images ->
            // Prefer 300x300 or similar medium size, fallback to first
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }

    /**
     * Returns the best thumbnail URL from a Spotify track's album art.
     */
    fun getTrackThumbnail(track: SpotifyTrack): String? =
        track.album?.images?.let { images ->
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }

    /**
     * Pre-computes normalized title/artist and their bigrams for a Spotify track.
     * Call once before scoring against multiple candidates to avoid redundant work.
     */
    fun precompute(
        title: String,
        artist: String,
        durationMs: Int,
    ): PrecomputedTrack {
        val normTitle = cachedNormalize(title)
        val normArtist = cachedNormalize(artist)
        return PrecomputedTrack(
            rawTitle = title,
            normalizedTitle = normTitle,
            titleBigrams = cachedBigrams(normTitle),
            rawArtist = artist,
            normalizedArtist = normArtist,
            artistBigrams = cachedBigrams(normArtist),
            durationMs = durationMs,
        )
    }

    /**
     * Computes a match confidence score (0.0 - 1.0) between a Spotify track and
     * a candidate result based on title, artist, and duration similarity.
     */
    fun matchScore(
        spotifyTitle: String,
        spotifyArtist: String,
        spotifyDurationMs: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val titleScore = textSimilarity(spotifyTitle, candidateTitle)
        val artistScore = textSimilarity(spotifyArtist, candidateArtist)

        val durationScore = durationScore(spotifyDurationMs, candidateDurationSec)
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /**
     * Scores a candidate against pre-computed Spotify track data.
     * This is the fast path: normalization and bigrams for the Spotify side
     * are computed once and reused across all candidates.
     */
    fun matchScorePrecomputed(
        precomputed: PrecomputedTrack,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double =
        matchScoreComponentsPrecomputed(
            precomputed = precomputed,
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            candidateDurationSec = candidateDurationSec,
        ).total

    fun matchScoreComponentsPrecomputed(
        precomputed: PrecomputedTrack,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): MatchScoreComponents {
        val normCandidateTitle = cachedNormalize(candidateTitle)
        val normCandidateArtist = cachedNormalize(candidateArtist)

        val titleScore =
            maxOf(
                bigramSimilarity(
                    precomputed.normalizedTitle,
                    precomputed.titleBigrams,
                    normCandidateTitle,
                    cachedBigrams(normCandidateTitle),
                ),
                crossScriptPhoneticSimilarity(precomputed.rawTitle, candidateTitle),
            )
        val artistScore =
            maxOf(
                bigramSimilarity(
                    precomputed.normalizedArtist,
                    precomputed.artistBigrams,
                    normCandidateArtist,
                    cachedBigrams(normCandidateArtist),
                ),
                crossScriptPhoneticSimilarity(precomputed.rawArtist, candidateArtist),
            )

        val durationScore = durationScore(precomputed.durationMs, candidateDurationSec)
        return MatchScoreComponents(
            total = titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20,
            title = titleScore,
            artist = artistScore,
            duration = durationScore,
        )
    }

    /** Unicode-aware text similarity used for secondary metadata such as album names. */
    fun textSimilarity(a: String, b: String): Double {
        val normalizedA = cachedNormalize(a)
        val normalizedB = cachedNormalize(b)
        return maxOf(
            bigramSimilarity(
                normalizedA,
                cachedBigrams(normalizedA),
                normalizedB,
                cachedBigrams(normalizedB),
            ),
            crossScriptPhoneticSimilarity(a, b),
        )
    }

    /**
     * Conservative Hebrew↔Latin fallback for metadata that is transliterated on one service and
     * written in Hebrew on the other. It is deliberately disabled when both strings use the same
     * script, so normal Hebrew title discrimination remains untouched.
     */
    private fun crossScriptPhoneticSimilarity(a: String, b: String): Double {
        val aHasHebrew = HEBREW_CHAR_PATTERN.containsMatchIn(a)
        val bHasHebrew = HEBREW_CHAR_PATTERN.containsMatchIn(b)
        val aHasLatin = LATIN_CHAR_PATTERN.containsMatchIn(a)
        val bHasLatin = LATIN_CHAR_PATTERN.containsMatchIn(b)
        if (!((aHasHebrew && bHasLatin) || (bHasHebrew && aHasLatin))) return 0.0

        val phoneticA = phoneticSkeleton(a)
        val phoneticB = phoneticSkeleton(b)
        return bigramSimilarity(
            phoneticA,
            cachedBigrams(phoneticA),
            phoneticB,
            cachedBigrams(phoneticB),
        )
    }

    private fun phoneticSkeleton(value: String): String {
        val mapped = buildString {
            Normalizer
                .normalize(value, Normalizer.Form.NFD)
                .lowercase()
                .forEach { char ->
                    when (char) {
                        in 'a'..'z', in '0'..'9', ' ' -> append(char)
                        'א', 'ע' -> Unit
                        'ב' -> append('b')
                        'ג' -> append('g')
                        'ד' -> append('d')
                        'ה' -> append('h')
                        'ו' -> append('v')
                        'ז' -> append('z')
                        'ח' -> append('h')
                        'ט', 'ת' -> append('t')
                        'י' -> append('y')
                        'כ', 'ך', 'ק' -> append('k')
                        'ל' -> append('l')
                        'מ', 'ם' -> append('m')
                        'נ', 'ן' -> append('n')
                        'ס' -> append('s')
                        'פ', 'ף' -> append('p')
                        'צ', 'ץ' -> append('z')
                        'ר' -> append('r')
                        'ש' -> append('x')
                    }
                }
        }
        return mapped
            .replace("sch", "x")
            .replace("sh", "x")
            .replace("kh", "h")
            .replace("ch", "h")
            .replace("tz", "z")
            .replace("ts", "z")
            .replace('w', 'v')
            .replace(LATIN_VOWEL_PATTERN, "")
            .replace(PHONETIC_NON_ALNUM_PATTERN, "")
            .replace(MULTI_SPACE_PATTERN, " ")
            .trim()
    }

    /** Threshold above which we consider a match good enough to skip remaining candidates. */
    fun earlyExitThreshold(): Double = EARLY_EXIT_THRESHOLD

    private fun durationScore(
        spotifyDurationMs: Int,
        candidateDurationSec: Int?,
    ): Double {
        if (candidateDurationSec == null || spotifyDurationMs <= 0) return 0.5
        val diff = kotlin.math.abs(spotifyDurationMs / 1000 - candidateDurationSec)
        return when {
            diff <= 2 -> 1.0
            diff <= 5 -> 0.8
            diff <= 10 -> 0.5
            diff <= 30 -> 0.2
            else -> 0.0
        }
    }

    /**
     * Normalizes a title for comparison, with LRU caching.
     */
    private fun cachedNormalize(title: String): String {
        synchronized(cacheLock) {
            normalizeCache[title]?.let { return it }
        }
        val normalized = normalizeTitle(title)
        synchronized(cacheLock) {
            normalizeCache[title] = normalized
        }
        return normalized
    }

    /**
     * Returns cached bigrams for a normalized string.
     */
    private fun cachedBigrams(normalized: String): Set<String> {
        synchronized(cacheLock) {
            bigramCache[normalized]?.let { return it }
        }
        val bigrams = if (normalized.length < 2) emptySet() else normalized.windowed(2).toSet()
        synchronized(cacheLock) {
            bigramCache[normalized] = bigrams
        }
        return bigrams
    }

    private fun normalizeTitle(title: String): String =
        Normalizer
            .normalize(title, Normalizer.Form.NFD)
            .replace(COMBINING_MARK_PATTERN, "")
            .lowercase()
            .replace(FEAT_PATTERN, "")
            .replace(FT_PATTERN, "")
            .replace(BRACKET_PATTERN, "")
            .replace(REMASTER_PATTERN, "")
            .replace(REMIX_PATTERN, "")
            .replace(NON_ALNUM_PATTERN, "")
            .replace(MULTI_SPACE_PATTERN, " ")
            .trim()

    /**
     * Dice coefficient using pre-computed bigram sets.
     */
    private fun bigramSimilarity(
        a: String,
        bigramsA: Set<String>,
        b: String,
        bigramsB: Set<String>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        val intersection = bigramsA.count { it in bigramsB }
        return (2.0 * intersection) / (bigramsA.size + bigramsB.size)
    }
}

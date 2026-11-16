/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.playlistimport.ImportMatcher
import moe.rukamori.archivetune.playlistimport.ImportSongDescription

object FuzzyMatcher {
    const val DEFAULT_THRESHOLD = ImportMatcher.DEFAULT_THRESHOLD

    fun normalize(value: String): String = ImportMatcher.normalize(value)

    fun jaroWinkler(first: String, second: String): Double = ImportMatcher.jaroWinkler(first, second)

    fun songScore(candidate: Song, title: String, artists: List<String>): Double =
        ImportMatcher.songScore(candidate.title, candidate.artists.map { it.name }, title, artists)

    fun bestSongMatch(
        candidates: List<Song>,
        title: String,
        artists: List<String>,
        threshold: Double = DEFAULT_THRESHOLD,
    ): Song? = ImportMatcher.bestSongMatch(candidates, title, artists, threshold, ::describeImportSong)

    fun rankedSongMatches(
        candidates: List<Song>,
        title: String,
        artists: List<String>,
        limit: Int,
        minimumScore: Double = 0.45,
    ): List<Song> = ImportMatcher.rankedSongMatches(candidates, title, artists, limit, minimumScore, ::describeImportSong)

    fun isMatch(score: Double, threshold: Double = DEFAULT_THRESHOLD): Boolean =
        ImportMatcher.isMatch(score, threshold)
}

fun describeImportSong(song: Song): ImportSongDescription =
    ImportSongDescription(song.title, song.artists.map { it.name })

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

import java.util.Locale

/**
 * Pure quick-pick seed selection, remote history ranking, and recommendation filtering policies.
 */
object QuickPickSeedPolicy {
    const val YOUTUBE_VIDEO_ID_LENGTH: Int = 11

    fun isValidVideoId(id: String): Boolean = id.isNotBlank() && id.length == YOUTUBE_VIDEO_ID_LENGTH

    fun artistRecommendationKey(
        artistId: String?,
        artistName: String,
    ): String = artistId?.takeIf(String::isNotBlank) ?: artistName.lowercase(Locale.ROOT)

    fun primaryArtistKey(
        seedId: String,
        artistIds: List<String>,
        artistNames: List<String>,
    ): String =
        artistIds.firstOrNull(String::isNotBlank)
            ?: artistNames.firstOrNull(String::isNotBlank)?.lowercase(Locale.ROOT)
            ?: seedId

    fun <T> selectLocalSeeds(
        candidates: List<T>,
        limit: Int,
        primaryArtistKeyOf: (T) -> String,
    ): List<T> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        return candidates.distinctBy(primaryArtistKeyOf).take(limit)
    }

    fun <T> rankRemoteSeeds(
        songs: List<T>,
        limit: Int,
        idOf: (T) -> String,
        artistKeysOf: (T) -> List<String>,
    ): List<T> {
        if (limit <= 0 || songs.isEmpty()) return emptyList()
        val validSongs = songs.filter { song -> isValidVideoId(idOf(song)) }
        if (validSongs.isEmpty()) return emptyList()

        val artistFrequency =
            validSongs
                .flatMap { song -> artistKeysOf(song) }
                .groupingBy { artistKey -> artistKey }
                .eachCount()

        return validSongs
            .distinctBy(idOf)
            .sortedByDescending { song ->
                artistKeysOf(song).maxOfOrNull { artistKey -> artistFrequency[artistKey] ?: 0 } ?: 0
            }.take(limit)
    }

    fun <T> mergeSeeds(
        localSeeds: List<T>,
        remoteSeeds: List<T>,
        limit: Int,
        idOf: (T) -> String,
        primaryArtistKeyOf: (T) -> String,
    ): List<T> {
        if (limit <= 0) return emptyList()
        if (localSeeds.size >= limit) return localSeeds.take(limit)
        return (localSeeds + remoteSeeds)
            .distinctBy(idOf)
            .distinctBy(primaryArtistKeyOf)
            .take(limit)
    }

    fun <T> filterValidRecommendations(
        songs: List<T>,
        seedSongId: String,
        idOf: (T) -> String,
        titleOf: (T) -> String,
        hasArtists: (T) -> Boolean = { true },
    ): List<T> =
        songs
            .asSequence()
            .filter { song ->
                val id = idOf(song)
                id != seedSongId &&
                    isValidVideoId(id) &&
                    titleOf(song).isNotBlank() &&
                    hasArtists(song)
            }.distinctBy(idOf)
            .toList()
}

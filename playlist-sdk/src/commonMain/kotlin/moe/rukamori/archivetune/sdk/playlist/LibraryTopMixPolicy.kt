/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.playlist

data class WeightedMixCandidate<T>(
    val track: T,
    val id: String,
    val weight: Double,
)

/** Pure-JVM track eligibility, weighting, deduplication, and display limits for library top mixes. */
object LibraryTopMixPolicy {
    const val CANDIDATE_LIMIT = 300
    const val DISPLAY_LIMIT = 5

    fun <T> filterEligibleTracks(
        tracks: List<T>,
        hideExplicit: Boolean,
        isPodcast: (T) -> Boolean,
        isExplicit: (T) -> Boolean,
    ): List<T> =
        tracks.filter { track ->
            !isPodcast(track) && (!hideExplicit || !isExplicit(track))
        }

    fun <T> filterListenedTracks(
        tracks: List<T>,
        hideExplicit: Boolean,
        isPodcast: (T) -> Boolean,
        isExplicit: (T) -> Boolean,
        totalPlayTimeMs: (T) -> Long,
    ): List<T> =
        filterEligibleTracks(
            tracks = tracks,
            hideExplicit = hideExplicit,
            isPodcast = isPodcast,
            isExplicit = isExplicit,
        ).filter { track -> totalPlayTimeMs(track) > 0L }

    fun <T> reverseLikedTracks(
        tracks: List<T>,
        hideExplicit: Boolean,
        isPodcast: (T) -> Boolean,
        isExplicit: (T) -> Boolean,
    ): List<T> =
        filterEligibleTracks(
            tracks = tracks,
            hideExplicit = hideExplicit,
            isPodcast = isPodcast,
            isExplicit = isExplicit,
        ).asReversed()

    fun <M, T, R> buildVisibleMixes(
        mixes: List<M>,
        tracksForMix: (M) -> List<T>,
        hideExplicit: Boolean,
        isPodcast: (T) -> Boolean,
        isExplicit: (T) -> Boolean,
        mapMix: (M, List<T>) -> R,
    ): List<R> =
        mixes.mapNotNull { mix ->
            val eligible =
                filterEligibleTracks(
                    tracks = tracksForMix(mix),
                    hideExplicit = hideExplicit,
                    isPodcast = isPodcast,
                    isExplicit = isExplicit,
                )
            if (eligible.isEmpty()) null else mapMix(mix, eligible)
        }

    fun <M> limitTopMixes(
        mixes: List<M>,
        limit: Int = DISPLAY_LIMIT,
    ): List<M> = if (limit <= 0) emptyList() else mixes.take(limit)

    /**
     * Selects up to [limit] unique tracks ordered by descending [WeightedMixCandidate.weight],
     * preserving the highest-weighted occurrence when duplicate track IDs are present.
     */
    fun <T> selectWeightedMixTracks(
        candidates: List<WeightedMixCandidate<T>>,
        limit: Int,
    ): List<T> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val bestById = LinkedHashMap<String, WeightedMixCandidate<T>>()
        for (candidate in candidates) {
            if (candidate.id.isBlank() || candidate.weight <= 0.0 || !candidate.weight.isFinite()) continue
            val existing = bestById[candidate.id]
            if (existing == null || candidate.weight > existing.weight) {
                bestById[candidate.id] = candidate
            }
        }
        return bestById.values
            .sortedByDescending { it.weight }
            .take(limit)
            .map { it.track }
    }
}

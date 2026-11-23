package moe.rukamori.archivetune.sdk.playlist

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryTopMixPolicyTest {
    private data class Track(
        val id: String,
        val isPodcast: Boolean = false,
        val isExplicit: Boolean = false,
        val totalPlayTimeMs: Long = 0L,
    )

    private data class Mix(
        val id: String,
        val tracks: List<Track>,
    )

    @Test
    fun filterEligibleTracks_excludes_podcasts_and_honors_hideExplicit() {
        val tracks = listOf(
            Track("clean"),
            Track("explicit", isExplicit = true),
            Track("podcast", isPodcast = true),
            Track("explicit-podcast", isPodcast = true, isExplicit = true),
        )

        assertEquals(
            listOf("clean", "explicit"),
            LibraryTopMixPolicy.filterEligibleTracks(
                tracks = tracks,
                hideExplicit = false,
                isPodcast = Track::isPodcast,
                isExplicit = Track::isExplicit,
            ).map(Track::id),
        )
        assertEquals(
            listOf("clean"),
            LibraryTopMixPolicy.filterEligibleTracks(
                tracks = tracks,
                hideExplicit = true,
                isPodcast = Track::isPodcast,
                isExplicit = Track::isExplicit,
            ).map(Track::id),
        )
    }

    @Test
    fun filterListenedTracks_and_reverseLikedTracks_enforce_eligibility() {
        val tracks = listOf(
            Track("a", totalPlayTimeMs = 0L),
            Track("b", totalPlayTimeMs = 12_000L),
            Track("c", isExplicit = true, totalPlayTimeMs = 45_000L),
            Track("d", isPodcast = true, totalPlayTimeMs = 60_000L),
        )

        assertEquals(
            listOf("b"),
            LibraryTopMixPolicy.filterListenedTracks(
                tracks = tracks,
                hideExplicit = true,
                isPodcast = Track::isPodcast,
                isExplicit = Track::isExplicit,
                totalPlayTimeMs = Track::totalPlayTimeMs,
            ).map(Track::id),
        )

        assertEquals(
            listOf("b", "a"),
            LibraryTopMixPolicy.reverseLikedTracks(
                tracks = tracks,
                hideExplicit = true,
                isPodcast = Track::isPodcast,
                isExplicit = Track::isExplicit,
            ).map(Track::id),
        )
    }

    @Test
    fun buildVisibleMixes_drops_mixes_whose_tracks_are_all_filtered_out_and_limitTopMixes_caps_count() {
        val mixes = (1..7).map { index ->
            if (index == 2) {
                Mix("mix-$index", listOf(Track("p", isPodcast = true), Track("e", isExplicit = true)))
            } else {
                Mix("mix-$index", listOf(Track("t-$index")))
            }
        }

        val visible = LibraryTopMixPolicy.buildVisibleMixes(
            mixes = mixes,
            tracksForMix = Mix::tracks,
            hideExplicit = true,
            isPodcast = Track::isPodcast,
            isExplicit = Track::isExplicit,
            mapMix = { mix, eligible -> mix.id to eligible.map(Track::id) },
        )

        assertEquals(
            listOf("mix-1", "mix-3", "mix-4", "mix-5", "mix-6", "mix-7"),
            visible.map { it.first },
        )
        assertEquals(
            listOf("mix-1", "mix-3", "mix-4", "mix-5", "mix-6"),
            LibraryTopMixPolicy.limitTopMixes(visible).map { it.first },
        )
    }

    @Test
    fun selectWeightedMixTracks_deduplicates_by_highest_weight_and_ignores_non_positive_weights() {
        val candidates = listOf(
            WeightedMixCandidate("a-low", id = "a", weight = 1.0),
            WeightedMixCandidate("b", id = "b", weight = 5.0),
            WeightedMixCandidate("a-high", id = "a", weight = 10.0),
            WeightedMixCandidate("zero", id = "z", weight = 0.0),
            WeightedMixCandidate("neg", id = "n", weight = -2.0),
            WeightedMixCandidate("nan", id = "nan", weight = Double.NaN),
            WeightedMixCandidate("c", id = "c", weight = 3.0),
        )

        assertEquals(
            listOf("a-high", "b"),
            LibraryTopMixPolicy.selectWeightedMixTracks(candidates, limit = 2),
        )
        assertEquals(
            listOf("a-high", "b", "c"),
            LibraryTopMixPolicy.selectWeightedMixTracks(candidates, limit = 10),
        )
        assertEquals(
            emptyList(),
            LibraryTopMixPolicy.selectWeightedMixTracks(candidates, limit = 0),
        )
    }
}

package moe.rukamori.archivetune.sponsorblock.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SponsorBlockSegmentsTest {
    @Test
    fun `filters malformed ranges and merges selected skips`() {
        val segments = listOf(
            RawSponsorBlockSegment(2.0, 3.0, "sponsor", "skip"),
            RawSponsorBlockSegment(1.0, 2.0, "intro", "skip"),
            RawSponsorBlockSegment(2.5, 4.0, "sponsor", "skip"),
            RawSponsorBlockSegment(5.0, 5.05, "sponsor", "skip"),
            RawSponsorBlockSegment(Double.NaN, 6.0, "sponsor", "skip"),
            RawSponsorBlockSegment(-1.0, 1.0, "sponsor", "skip"),
            RawSponsorBlockSegment(7.0, 8.0, "outro", "skip"),
            RawSponsorBlockSegment(9.0, 10.0, "sponsor", "mute"),
        )
        assertEquals(
            listOf(SponsorBlockRange(1_000L, 4_000L)),
            normalizeSponsorBlockSegments(segments, setOf("sponsor", "intro")),
        )
    }
}

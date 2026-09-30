package moe.rukamori.archivetune.desktop.sponsorblock

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopSponsorBlockTest {
    @Test
    fun filtersToEnabledSkippableCategoriesAndMergesOverlappingRanges() {
        val raw = JSONArray(
            """
            [
              {"category":"sponsor","actionType":"skip","segment":[10.0,20.0]},
              {"category":"intro","actionType":"skip","segment":[19.5,25.0]},
              {"category":"filler","actionType":"skip","segment":[30.0,35.0]},
              {"category":"sponsor","actionType":"mute","segment":[40.0,45.0]},
              {"category":"sponsor","actionType":"skip","segment":[50.0,50.05]},
              {"category":"sponsor","actionType":"skip","segment":[60.0,-1.0]}
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(DesktopSponsorBlockSegment(10_000, 25_000)),
            DesktopSponsorBlockRepository().parseSegments(raw, setOf("sponsor", "intro")),
        )
    }

    @Test
    fun apiUrlRequiresHttpsAndRemovesTrailingSlash() {
        assertEquals("https://sponsor.ajay.app", normalizeDesktopSponsorBlockApiUrl(" https://sponsor.ajay.app/ "))
        assertNull(normalizeDesktopSponsorBlockApiUrl("http://sponsor.ajay.app"))
        assertNull(normalizeDesktopSponsorBlockApiUrl("https://user:pass@sponsor.ajay.app"))
        assertNull(normalizeDesktopSponsorBlockApiUrl("https://sponsor.ajay.app?token=x"))
    }
}

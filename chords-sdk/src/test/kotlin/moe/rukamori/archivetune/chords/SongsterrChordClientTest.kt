package moe.rukamori.archivetune.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongsterrChordClientTest {
    @Test
    fun `authored chords preserve slash chords sections and tempo timing`() {
        val parsed =
            SongsterrChordClient.parseTrackJsonForTest(
                """
                {
                  "tuning": [64,59,55,50,45,40],
                  "automations": {"tempo":[{"measure":0,"position":0,"bpm":60}]},
                  "measures": [
                    {
                      "signature":[4,4],
                      "marker":{"text":"Intro"},
                      "voices":[{"beats":[
                        {"duration":[1,4],"chord":{"text":"Am"},"notes":[{"fret":0,"string":0}]},
                        {"duration":[1,4],"notes":[{"fret":1,"string":1}]},
                        {"duration":[1,4],"chord":{"text":"E/G#"},"notes":[{"fret":1,"string":2}]},
                        {"duration":[1,4],"notes":[{"fret":2,"string":3}]}
                      ]}]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(4_000L, parsed.durationMs)
        assertEquals(listOf("Am", "E/G#"), parsed.authored.map { it.name })
        assertEquals(listOf(0L, 2_000L), parsed.authored.map { it.startMs })
        assertEquals(listOf(2_000L, 4_000L), parsed.authored.map { it.endMs })
        assertEquals("Intro", parsed.authored.first().section)
        assertTrue(parsed.derived.isEmpty())
    }

    @Test
    fun `note only guitar tab gets conservative derived chords without audio model`() {
        val parsed =
            SongsterrChordClient.parseTrackJsonForTest(
                """
                {
                  "tuning": [64,59,55,50,45,40],
                  "automations": {"tempo":[{"measure":0,"position":0,"bpm":120}]},
                  "measures": [
                    {"signature":[4,4],"voices":[{"beats":[
                      {"duration":[1,4],"notes":[{"fret":3,"string":5},{"fret":2,"string":4},{"fret":0,"string":3}]},
                      {"duration":[1,4],"notes":[{"fret":3,"string":5},{"fret":2,"string":4},{"fret":0,"string":3}]},
                      {"duration":[1,4],"notes":[{"fret":3,"string":5},{"fret":2,"string":4},{"fret":0,"string":3}]},
                      {"duration":[1,4],"notes":[{"fret":3,"string":5},{"fret":2,"string":4},{"fret":0,"string":3}]}
                    ]}]}
                  ]
                }
                """.trimIndent(),
            )

        assertTrue(parsed.authored.isEmpty())
        assertTrue(parsed.derived.isNotEmpty())
        // G2 + B2 + D3, expressed through standard guitar tuning, is G major.
        assertEquals("G", parsed.derived.first().name.substringBefore('/'))
        assertTrue(parsed.derived.first().confidence >= 0.76f)
    }

    @Test
    fun `two note fifth is kept as an unambiguous power chord`() {
        val parsed =
            SongsterrChordClient.parseTrackJsonForTest(
                """
                {
                  "tuning": [64,59,55,50,45,40],
                  "automations": {"tempo":[{"measure":0,"position":0,"bpm":120}]},
                  "measures": [
                    {"signature":[4,4],"voices":[{"beats":[
                      {"duration":[1,4],"notes":[{"fret":3,"string":4}]},
                      {"duration":[1,4],"notes":[{"fret":0,"string":2}]},
                      {"duration":[1,4],"notes":[{"fret":3,"string":4}]},
                      {"duration":[1,4],"notes":[{"fret":0,"string":2}]}
                    ]}]}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("C5", parsed.derived.first().name)
    }

    @Test
    fun `song matching rejects unrelated partial search noise`() {
        val good = songMatchScore("Creep", "Radiohead", "Creep", "Radiohead")
        val bad = songMatchScore("זה הסוף", "היהודים", "חופשי זה לגמרי לבד", "בנזין")
        assertTrue(good > 0.95)
        assertTrue(bad < 0.68)
    }
}

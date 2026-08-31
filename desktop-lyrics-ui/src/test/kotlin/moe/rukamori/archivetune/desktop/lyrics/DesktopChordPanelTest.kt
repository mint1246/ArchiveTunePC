package moe.rukamori.archivetune.desktop.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import moe.rukamori.archivetune.chords.ChordEvent

class DesktopChordPanelTest {
    @Test
    fun `transpose keeps chord quality and slash bass`() {
        assertEquals("Bm7/F#", transposeChordName("Am7/E", 2))
        assertEquals("Cmaj7", transposeChordName("Bmaj7", 1))
        assertEquals("Abm", transposeChordName("Gbm", 2))
    }

    @Test
    fun `playback pitch automatically transposes displayed chords`() {
        assertEquals(2, chordTransposeForPitch(2f))
        assertEquals(-3, chordTransposeForPitch(-3f))
        assertEquals(1, chordTransposeForPitch(0.5f))
        assertEquals("D", transposeChordName("C", chordTransposeForPitch(2f)))
    }

    @Test
    fun `active chord lookup follows playback time`() {
        val events =
            listOf(
                ChordEvent(1_000, 2_000, "C"),
                ChordEvent(2_000, 3_000, "G"),
                ChordEvent(3_000, 4_000, "Am"),
            )
        assertEquals(-1, activeChordIndexAt(999, events))
        assertEquals(0, activeChordIndexAt(1_000, events))
        assertEquals(1, activeChordIndexAt(2_500, events))
        assertEquals(2, activeChordIndexAt(9_000, events))
    }
}

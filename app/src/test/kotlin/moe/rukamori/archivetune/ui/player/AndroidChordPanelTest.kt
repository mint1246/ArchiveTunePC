package moe.rukamori.archivetune.ui.player

import moe.rukamori.archivetune.chords.ChordEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidChordPanelTest {
    @Test
    fun `active chord lookup follows playback position`() {
        val events = listOf(
            ChordEvent(1_000, 2_000, "C"),
            ChordEvent(2_000, 3_000, "G"),
            ChordEvent(3_000, 4_000, "Am"),
        )

        assertEquals(-1, activeChordIndexAt(999, events))
        assertEquals(0, activeChordIndexAt(1_000, events))
        assertEquals(1, activeChordIndexAt(2_750, events))
        assertEquals(2, activeChordIndexAt(9_000, events))
    }

    @Test
    fun `transpose preserves suffix and slash bass`() {
        assertEquals("Bm/D#", transposeChordName("Am/C#", 2))
        assertEquals("Abmaj7", transposeChordName("Gbmaj7", 2))
    }

    @Test
    fun `playback pitch multiplier maps to chord semitones`() {
        assertEquals(0, chordTransposeForPitchMultiplier(1f))
        assertEquals(12, chordTransposeForPitchMultiplier(2f))
        assertEquals(-12, chordTransposeForPitchMultiplier(0.5f))
    }
}

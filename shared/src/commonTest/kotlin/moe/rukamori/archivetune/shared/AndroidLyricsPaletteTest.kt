package moe.rukamori.archivetune.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidLyricsPaletteTest {
    @Test fun grayProducesDescendingNeutralStops() {
        val colors = lyricsGradient(listOf(AndroidPaletteSwatch(0xff808080.toInt(), 100)))
        assertEquals(3, colors.size)
        val values = colors.map { androidHsv(it) }
        assertTrue(values.all { it[1] == 0f })
        assertTrue(values[0][2] > values[1][2] && values[1][2] > values[2][2])
    }

    @Test fun exclusiveTargetsCanSelectLessCommonVibrantColor() {
        val muted = AndroidPaletteSwatch(0xff777777.toInt(), 1000)
        val vibrant = AndroidPaletteSwatch(0xffe02020.toInt(), 50)
        val targets = lyricsPaletteTargets(listOf(muted, vibrant))
        assertEquals(vibrant, targets.first())
        assertEquals(1, targets.count { it == muted })
    }
}

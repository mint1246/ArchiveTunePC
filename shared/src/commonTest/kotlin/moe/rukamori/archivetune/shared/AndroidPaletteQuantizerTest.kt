package moe.rukamori.archivetune.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPaletteQuantizerTest {
    @Test
    fun filtersAndCountsQuantizedColors() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0xffff0102.toInt(), 0xff0000ff.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(), 0xffaa7755.toInt())
        assertEquals(
            listOf(AndroidPaletteSwatch(0xff0000f8.toInt(), 1), AndroidPaletteSwatch(0xfff80000.toInt(), 2)),
            androidPaletteSwatches(pixels),
        )
        assertEquals(31 shl 10, pixels[0])
    }

    @Test
    fun splitUsesPopulationAndReturnsOpaqueAverage() {
        val pixels = intArrayOf(0xff0000f8.toInt(), 0xff0000f8.toInt(), 0xff00f800.toInt(), 0xfff80000.toInt())
        val result = androidPaletteSwatches(pixels, 2)
        assertEquals(listOf(AndroidPaletteSwatch(0xff808000.toInt(), 2), AndroidPaletteSwatch(0xff0000f8.toInt(), 2)), result)
        assertTrue(androidPaletteSwatches(intArrayOf()).isEmpty())
    }
}

package moe.rukamori.archivetune.shared

import moe.rukamori.archivetune.parityreference.AndroidPaletteReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPaletteReferenceTest {
    @Test fun portableQuantizerMatchesOriginalAndroidXJavaIncludingHeapOrderAndFiltering() {
        val examples = buildList {
            add(IntArray(112 * 112) { 0xff336699.toInt() })
            add(IntArray(112 * 112) { if (it % 2 == 0) -1 else 0xff000000.toInt() })
            add(IntArray(112 * 112) { index -> 0xff000000.toInt() or ((index % 112 * 255 / 111) shl 16) or ((index / 112 * 255 / 111) shl 8) or 127 })
            repeat(12) { seed ->
                val random = Random(seed)
                add(IntArray(112 * 112) { random.nextInt() or 0xff000000.toInt() })
            }
        }
        for ((case, pixels) in examples.withIndex()) for (limit in listOf(1, 2, 8, 16, 48)) {
            val expected = AndroidPaletteReference.quantize(pixels.copyOf(), limit).map { it[0] to it[1] }
            val actual = androidPaletteSwatches(pixels.copyOf(), limit).map { it.rgb to it.population }
            assertEquals(expected, actual, "Original Java comparison: case=$case limit=$limit")
        }
    }
}

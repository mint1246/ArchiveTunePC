package moe.rukamori.archivetune.shared

import kotlin.test.Test
import kotlin.test.assertContentEquals

class AndroidBackdropBlurTest {
    @Test fun clampsEdgeSamplesAndTruncatesChannelsLikeAndroid() {
        val input = intArrayOf(0xff000000.toInt(), 0xff5a2d12.toInt(), 0xffb45a24.toInt())
        assertContentEquals(intArrayOf(0xff1e0f06.toInt(), 0xff5a2d12.toInt(), 0xff964b1e.toInt()),
            androidBackdropBoxBlur(input, 3, 1, 1))
    }

    @Test fun androidThreePassRadiusFourPreservesAUniformFieldIncludingAlpha() {
        var pixels = IntArray(128 * 128) { 0x7f274361 }
        repeat(3) { pixels = androidBackdropBoxBlur(pixels, 128, 128, 4) }
        assertContentEquals(IntArray(pixels.size) { 0x7f274361 }, pixels)
    }
}

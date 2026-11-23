package moe.rukamori.archivetune.shared

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidColorMathTest {
    @Test fun byteRgbRoundTripsAcrossAllHueSectorsAndGray() {
        val random = Random(1416)
        val colors = listOf(0xff000000.toInt(), -1, 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()) +
            List(2048) { random.nextInt() or 0xff000000.toInt() }
        for (color in colors) {
            val hsv = androidHsv(color)
            assertEquals(color, androidHsvToArgb(hsv[0], hsv[1], hsv[2]))
        }
    }
}

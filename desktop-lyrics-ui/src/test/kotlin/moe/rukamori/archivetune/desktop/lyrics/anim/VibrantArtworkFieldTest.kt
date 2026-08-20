package moe.rukamori.archivetune.desktop.lyrics.anim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import moe.rukamori.archivetune.desktop.artwork.DesktopArtworkImage

class VibrantArtworkFieldTest {
    @Test
    fun `shared decoded artwork still produces the expected 128px colour field`() {
        val pixels = IntArray(256 * 128) { index ->
            val x = index % 256
            val y = index / 256
            0xFF000000.toInt() or (x shl 16) or (y shl 8) or 0x40
        }
        val artwork = DesktopArtworkImage(256, 128, pixels)

        val field = assertNotNull(makeVibrantColourField(artwork))
        field.use {
            assertEquals(128, it.width)
            assertEquals(128, it.height)
        }
    }
}

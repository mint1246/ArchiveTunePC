package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxDisplayScaleTest {
    @Test
    fun `explicit uiScale property wins over environment and X resources`() {
        val decision = decideLinuxUiScale("1.5", "2", "3", 192.0)

        assertEquals(1.5, decision.scale)
        assertEquals(LinuxScaleSource.PROPERTY, decision.source)
        assertTrue(decision.explicit)
    }

    @Test
    fun `J2D_UISCALE is honored when the property is absent`() {
        val decision = decideLinuxUiScale(null, "1.75", null, 192.0)

        assertEquals(1.75, decision.scale)
        assertEquals(LinuxScaleSource.J2D_UISCALE_ENVIRONMENT, decision.source)
        assertTrue(decision.explicit)
    }

    @Test
    fun `GDK_SCALE is honored when no AWT-specific request exists`() {
        val decision = decideLinuxUiScale(null, null, "2", 96.0)

        assertEquals(2.0, decision.scale)
        assertEquals(LinuxScaleSource.GDK_SCALE_ENVIRONMENT, decision.source)
        assertTrue(decision.explicit)
    }

    @Test
    fun `Xwayland 192 dpi resolves to the same 2x scale Skiko derives on the JVM`() {
        val decision = decideLinuxUiScale(null, null, null, 192.0)

        assertEquals(2.0, decision.scale)
        assertEquals(LinuxScaleSource.XFT_DPI, decision.source)
        assertFalse(decision.explicit)
    }

    @Test
    fun `fractional Xft dpi is kept fractional instead of truncated`() {
        val decision = decideLinuxUiScale(null, null, null, 120.0)

        assertEquals(1.25, decision.scale)
        assertEquals("1.25", formatLinuxUiScale(decision.scale!!))
    }

    @Test
    fun `malformed and out-of-range requests fall through to X resources`() {
        assertEquals(
            LinuxScaleSource.XFT_DPI,
            decideLinuxUiScale("not-a-number", "", "0", 144.0).source,
        )
        assertEquals(
            LinuxScaleSource.XFT_DPI,
            decideLinuxUiScale(null, null, null, 144.0).source,
        )
        assertNull(decideLinuxUiScale("0", "-2", "99", null).scale)
    }

    @Test
    fun `missing Xft dpi leaves toolkit detection in charge`() {
        val decision = decideLinuxUiScale(null, null, null, null)

        assertNull(decision.scale)
        assertNull(decision.source)
        assertFalse(decision.explicit)
    }

    @Test
    fun `Java2D unit spellings are explicit requests and win over detection`() {
        val multiplier = decideLinuxUiScale("2x", null, null, 96.0)
        val percentage = decideLinuxUiScale("200%", null, null, 192.0)
        val dpi = decideLinuxUiScale("192dpi", null, null, 96.0)

        assertEquals(2.0, multiplier.scale)
        assertEquals(2.0, percentage.scale)
        assertEquals(2.0, dpi.scale)
        assertEquals(LinuxScaleSource.PROPERTY, percentage.source)
        assertTrue(percentage.explicit)
        assertTrue(dpi.explicit)
    }

    @Test
    fun `parseXftDpi reads the X resource manager string`() {
        val resources = "Xft.antialias:\t1\nXft.dpi:\t192\nXft.rgba:\tnone\n"

        assertEquals(192.0, parseXftDpi(resources))
        assertEquals(120.0, parseXftDpi("Xft.dpi: 120"))
        assertNull(parseXftDpi("Xft.antialias:\t1"))
        assertNull(parseXftDpi(""))
        assertNull(parseXftDpi(null))
    }

    @Test
    fun `integral scales are formatted without a fractional suffix`() {
        assertEquals("2", formatLinuxUiScale(2.0))
        assertEquals("1", formatLinuxUiScale(1.0))
        assertEquals("1.25", formatLinuxUiScale(1.25))
        assertEquals("1.333", formatLinuxUiScale(4.0 / 3.0))
    }
}

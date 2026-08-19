package moe.rukamori.archivetune.desktop.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopQueueOverlayLayoutTest {
    @Test
    fun `normal desktop viewport keeps 360dp queue`() {
        assertEquals(360.dp, desktopQueueOverlayWidth(900.dp))
    }

    @Test
    fun `minimum window viewport keeps queue below half width`() {
        val width = desktopQueueOverlayWidth(640.dp)

        assertTrue(width < 320.dp)
        assertTrue(width > 300.dp)
    }

    @Test
    fun `narrow content viewport scales proportionally instead of forcing 360dp`() {
        assertEquals(240.dp, desktopQueueOverlayWidth(500.dp))
    }

    @Test
    fun `invalid negative viewport cannot produce negative width`() {
        assertEquals(0.dp, desktopQueueOverlayWidth((-10).dp))
    }
}

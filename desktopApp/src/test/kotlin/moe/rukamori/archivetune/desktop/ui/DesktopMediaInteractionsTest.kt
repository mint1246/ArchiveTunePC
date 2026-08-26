package moe.rukamori.archivetune.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMediaInteractionsTest {
    @Test
    fun `explicit song click wins over any unrelated queue selection`() {
        assertEquals(2, requestedSongQueueIndex(listOf("a", "napoleon", "requested"), "requested"))
    }

    @Test
    fun `missing requested song does not silently select another queue item`() {
        assertNull(requestedSongQueueIndex(listOf("a", "napoleon"), "requested"))
    }

    @Test
    fun `overflow affordance stays hidden while an actionable item is idle`() {
        assertFalse(
            shouldShowMediaOverflowButton(
                hasMenu = true,
                hovered = false,
                focusWithin = false,
                menuOpen = false,
            ),
        )
    }

    @Test
    fun `hover focus or an open menu keeps overflow affordance visible`() {
        assertTrue(shouldShowMediaOverflowButton(true, hovered = true, focusWithin = false, menuOpen = false))
        assertTrue(shouldShowMediaOverflowButton(true, hovered = false, focusWithin = true, menuOpen = false))
        assertTrue(shouldShowMediaOverflowButton(true, hovered = false, focusWithin = false, menuOpen = true))
    }

    @Test
    fun `items without actions never show an overflow affordance`() {
        assertFalse(shouldShowMediaOverflowButton(false, hovered = true, focusWithin = true, menuOpen = true))
    }
}

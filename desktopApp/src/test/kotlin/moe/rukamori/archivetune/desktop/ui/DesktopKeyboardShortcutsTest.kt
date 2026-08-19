package moe.rukamori.archivetune.desktop.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopKeyboardShortcutsTest {
    @Test
    fun `search shortcuts include ctrl f and ctrl k`() {
        assertEquals(DesktopShortcutAction.SEARCH, desktopShortcutAction(Key.F, ctrlPressed = true, altPressed = false))
        assertEquals(DesktopShortcutAction.SEARCH, desktopShortcutAction(Key.K, ctrlPressed = true, altPressed = false))
    }

    @Test
    fun `settings shortcut is ctrl comma`() {
        assertEquals(DesktopShortcutAction.SETTINGS, desktopShortcutAction(Key.Comma, ctrlPressed = true, altPressed = false))
    }

    @Test
    fun `alt arrows map to navigation history`() {
        assertEquals(DesktopShortcutAction.BACK, desktopShortcutAction(Key.DirectionLeft, ctrlPressed = false, altPressed = true))
        assertEquals(DesktopShortcutAction.FORWARD, desktopShortcutAction(Key.DirectionRight, ctrlPressed = false, altPressed = true))
    }

    @Test
    fun `typing keys and mixed modifiers do not become shell shortcuts`() {
        assertNull(desktopShortcutAction(Key.K, ctrlPressed = false, altPressed = false))
        assertNull(desktopShortcutAction(Key.Comma, ctrlPressed = false, altPressed = false))
        assertNull(desktopShortcutAction(Key.K, ctrlPressed = true, altPressed = true))
    }

    @Test
    fun `escape dismisses immersive content before queue and search`() {
        assertEquals(
            DesktopEscapeAction.DISMISS_IMMERSIVE,
            desktopEscapeAction(immersiveVisible = true, queueVisible = true, hasSearchQuery = true),
        )
    }

    @Test
    fun `escape dismisses queue before clearing search`() {
        assertEquals(
            DesktopEscapeAction.DISMISS_QUEUE,
            desktopEscapeAction(immersiveVisible = false, queueVisible = true, hasSearchQuery = true),
        )
    }

    @Test
    fun `escape falls back to existing search clear and otherwise does nothing`() {
        assertEquals(
            DesktopEscapeAction.CLEAR_SEARCH,
            desktopEscapeAction(immersiveVisible = false, queueVisible = false, hasSearchQuery = true),
        )
        assertNull(desktopEscapeAction(immersiveVisible = false, queueVisible = false, hasSearchQuery = false))
    }
}

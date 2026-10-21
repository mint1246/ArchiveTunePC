package moe.rukamori.archivetune.linuxmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxShortcutTriggersTest {
    @Test
    fun `modifiers and one key become a shortcuts specification trigger`() {
        assertEquals(
            "CTRL+SHIFT+k",
            LinuxShortcutTriggers.triggerFor(setOf(0x11, 0x10, 0x4B)),
        )
    }

    @Test
    fun `base letters are lower case because an upper case letter is the shifted symbol`() {
        assertEquals("CTRL+a", LinuxShortcutTriggers.triggerFor(setOf(0x11, 0x41)))
    }

    @Test
    fun `the windows key is the logo modifier`() {
        assertEquals("LOGO+l", LinuxShortcutTriggers.triggerFor(setOf(0x5B, 0x4C)))
    }

    @Test
    fun `a single ordinary key without a modifier is supported`() {
        assertEquals("k", LinuxShortcutTriggers.triggerFor(setOf(0x4B)))
    }

    @Test
    fun `several ordinary keys are not supported by the portal`() {
        assertNull(LinuxShortcutTriggers.triggerFor(setOf(0x11, 0x41, 0x42)))
        assertFalse(LinuxShortcutTriggers.isSupported(setOf(0x11, 0x41, 0x42)))
    }

    @Test
    fun `punctuation uses the xkbcommon names`() {
        assertEquals("CTRL+semicolon", LinuxShortcutTriggers.triggerFor(setOf(0x11, 0xBA)))
        assertEquals("CTRL+backslash", LinuxShortcutTriggers.triggerFor(setOf(0x11, 0xDC)))
    }

    @Test
    fun `space uses the space name`() {
        assertEquals("ALT+space", LinuxShortcutTriggers.triggerFor(setOf(0x12, 0x20)))
    }

    @Test
    fun `function keys keep their number`() {
        assertEquals("CTRL+F5", LinuxShortcutTriggers.triggerFor(setOf(0x11, 0x74)))
    }

    @Test
    fun `left and right modifiers normalize to the generic modifier`() {
        assertEquals(
            "CTRL+SHIFT+k",
            LinuxShortcutTriggers.triggerFor(setOf(0xA3, 0xA1, 0x4B)),
        )
    }

    @Test
    fun `keys without a trigger token are not supported`() {
        assertNull(LinuxShortcutTriggers.triggerFor(setOf(0x11, 0x2C)))
    }

    @Test
    fun `the description uses friendly names`() {
        assertEquals(
            "Ctrl + Shift + K",
            LinuxShortcutTriggers.describe(setOf(0x11, 0x10, 0x4B)),
        )
    }

    @Test
    fun `the modifier and ordinary keys are reported separately`() {
        val chord = setOf(0x11, 0x10, 0x4B)
        assertEquals(setOf(0x11, 0x10), LinuxShortcutTriggers.modifierKeys(chord))
        assertEquals(setOf(0x4B), LinuxShortcutTriggers.ordinaryKeys(chord))
    }
}

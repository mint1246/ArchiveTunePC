package moe.rukamori.archivetune.linuxmedia
import java.awt.event.KeyEvent
import kotlin.test.*
class LinuxAwtKeysTest {
    @Test fun `AWT punctuation is not mistaken for Super or navigation keys`() {
        assertEquals(0xDB, awtToPersistedKey(KeyEvent.VK_OPEN_BRACKET))
        assertEquals(0xDD, awtToPersistedKey(KeyEvent.VK_CLOSE_BRACKET))
        assertEquals(0xDC, awtToPersistedKey(KeyEvent.VK_BACK_SLASH))
        assertEquals(0xBD, awtToPersistedKey(KeyEvent.VK_MINUS))
        assertEquals(0x2D, awtToPersistedKey(KeyEvent.VK_INSERT))
        assertEquals(0x2E, awtToPersistedKey(KeyEvent.VK_DELETE))
    }
    @Test fun `capture and portal use the same IDs for Enter Super and function keys`() {
        assertEquals("CTRL+Return", LinuxShortcutTriggers.triggerFor(setOf(0x11, awtToPersistedKey(KeyEvent.VK_ENTER)!!)))
        assertEquals("LOGO+k", LinuxShortcutTriggers.triggerFor(setOf(awtToPersistedKey(KeyEvent.VK_META)!!, KeyEvent.VK_K)))
        assertEquals("F13", LinuxShortcutTriggers.triggerFor(setOf(awtToPersistedKey(KeyEvent.VK_F13)!!)))
        assertNull(awtToPersistedKey(KeyEvent.VK_UNDEFINED))
    }
}

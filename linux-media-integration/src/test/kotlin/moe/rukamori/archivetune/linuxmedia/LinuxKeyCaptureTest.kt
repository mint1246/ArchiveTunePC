package moe.rukamori.archivetune.linuxmedia

import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxKeyCaptureTest {
    @Test
    fun `AWT macro completes before a UI collector runs without dropping keys`() {
        LinuxKeyCapture().use { capture ->
            capture.start()
            SwingUtilities.invokeAndWait {
                val source = Canvas()
                val keys = listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_Y)
                var mask = 0
                for (key in keys) {
                    mask = mask or modifier(key)
                    assertTrue(capture.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, mask, key, KeyEvent.CHAR_UNDEFINED)))
                }
                for (key in keys.reversed()) {
                    mask = mask and modifier(key).inv()
                    assertTrue(capture.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, mask, key, KeyEvent.CHAR_UNDEFINED)))
                }
            }
            assertEquals(listOf(0x10, 0x11, 0x12, 0x59), capture.state.value.completedChord)
        }
    }

    @Test
    fun `modifier only release stays recording and a missing modifier press is recovered from AWT flags`() {
        LinuxKeyCapture().use { capture ->
            capture.start()
            SwingUtilities.invokeAndWait {
                val source = Canvas()
                capture.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED))
                capture.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED))
                assertNull(capture.state.value.completedChord)
                capture.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_Y, KeyEvent.CHAR_UNDEFINED))
                capture.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_Y, KeyEvent.CHAR_UNDEFINED))
                capture.dispatchKeyEvent(KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED))
            }
            assertEquals(listOf(0x11, 0x59), capture.state.value.completedChord)
        }
    }

    private fun modifier(key: Int): Int = when (key) {
        KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK
        KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK
        KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK
        else -> 0
    }
}

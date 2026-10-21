/* ArchiveTune (2026), GPL-3.0. See LICENSE. */
package moe.rukamori.archivetune.linuxmedia

import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Captures and finishes a chord on the AWT event thread, independently of Compose frame timing. */
class LinuxKeyCapture : KeyEventDispatcher, AutoCloseable {
    private val recorder = LinuxShortcutRecorder()
    private val _state = MutableStateFlow(recorder.state)
    val state = _state.asStateFlow()
    private var captureWindow: Window? = null
    private val focusListener = AWTEventListener { event ->
        if (event is WindowEvent &&
            event.id in listOf(WindowEvent.WINDOW_LOST_FOCUS, WindowEvent.WINDOW_DEACTIVATED) &&
            (captureWindow == null || event.window === captureWindow)
        ) stop()
    }

    /** Attach immediately when Set is clicked, so a fast following mouse macro is not missed. */
    fun start(): Long = onEventThread {
        detach()
        val state = recorder.start()
        _state.value = state
        if (!GraphicsEnvironment.isHeadless()) {
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            captureWindow = manager.activeWindow
            manager.addKeyEventDispatcher(this)
            Toolkit.getDefaultToolkit().addAWTEventListener(
                focusListener,
                AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK,
            )
        }
        state.session
    }

    fun stop() = onEventThread {
        _state.value = recorder.cancel()
        detach()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!recorder.active) return false
        if (event.id == KeyEvent.KEY_TYPED) return true
        val key = awtToPersistedKey(event.keyCode) ?: return false
        val state = when (event.id) {
            KeyEvent.KEY_PRESSED -> recorder.press(key, modifiers(event.modifiersEx))
            KeyEvent.KEY_RELEASED -> recorder.release(key)
            else -> return false
        }
        _state.value = state
        if (!recorder.active) detach()
        return true
    }

    override fun close() = stop()

    private fun detach() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this)
        if (!GraphicsEnvironment.isHeadless()) Toolkit.getDefaultToolkit().removeAWTEventListener(focusListener)
        captureWindow = null
    }

    private fun modifiers(mask: Int): Set<Int> = buildSet {
        if (mask and InputEvent.CTRL_DOWN_MASK != 0) add(LinuxVirtualKeys.CONTROL)
        if (mask and InputEvent.SHIFT_DOWN_MASK != 0) add(LinuxVirtualKeys.SHIFT)
        if (mask and InputEvent.ALT_DOWN_MASK != 0) add(LinuxVirtualKeys.ALT)
        if (mask and InputEvent.META_DOWN_MASK != 0) add(LinuxVirtualKeys.WINDOWS)
    }

    private fun <T> onEventThread(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(block)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}

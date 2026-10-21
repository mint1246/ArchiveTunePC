/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records a shortcut while ArchiveTune is focused.
 *
 * The capture is local on purpose: the global shortcut portal can only express modifiers plus a
 * single key, so the settings surface records the chord that the user presses and reports it to
 * the application. Recorded keys are consumed so they cannot trigger a playback command while the
 * shortcut is being recorded.
 */
class LinuxKeyCapture : KeyEventDispatcher, AutoCloseable {
    private val _pressedKeys = MutableStateFlow<List<Int>>(emptyList())

    /** The keys that are held right now, as the virtual-key ids the settings file uses. */
    val pressedKeys: StateFlow<List<Int>> = _pressedKeys.asStateFlow()

    private val _cancellations = MutableStateFlow(0L)

    /**
     * Counts the recordings that were dropped instead of being saved.
     *
     * A recording is dropped when the window loses the focus, because the keys the user pressed
     * before that are only a part of the shortcut they wanted to record.
     */
    val cancellations: StateFlow<Long> = _cancellations.asStateFlow()

    private val tracker = LinuxChordTracker()
    @Volatile private var active = false
    private var captureWindow: Window? = null
    private var focusListener: AWTEventListener? = null

    /** True while the capture is listening for key events. */
    val isActive: Boolean
        get() = active

    /** Starts recording. Calling it again while the capture runs does nothing. */
    fun start() {
        if (active) return
        if (GraphicsEnvironment.isHeadless()) return
        active = true
        tracker.reset()
        publish(emptySet())
        captureWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val listener = AWTEventListener { event -> handleWindowEvent(event) }
        focusListener = listener
        runCatching { Toolkit.getDefaultToolkit().addAWTEventListener(listener, LISTENER_MASK) }
        SwingUtilities.invokeLater { attachDispatcher() }
    }

    /**
     * Stops recording and forgets the keys that were held.
     *
     * [cancelled] tells the caller that the recording was dropped rather than finished, so a
     * partial chord is never saved. A dropped recording keeps the pressed keys visible, because an
     * empty key set would look like a finished chord to the settings surface.
     */
    fun stop(cancelled: Boolean = false) {
        if (!active) return
        active = false
        tracker.reset()
        if (cancelled) {
            _cancellations.value += 1L
        } else {
            publish(emptySet())
        }
        focusListener?.let { listener -> runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) } }
        focusListener = null
        captureWindow = null
        runCatching { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this) }
    }

    /** The chord that was recorded, or an empty set when nothing was recorded yet. */
    fun recordedChord(): Set<Int> = tracker.bestChord()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!active) return false
        val key = awtToPersistedKey(event.keyCode) ?: return false
        return when (event.id) {
            KeyEvent.KEY_PRESSED -> {
                publish(tracker.keyDown(key))
                true
            }

            KeyEvent.KEY_RELEASED -> {
                publish(tracker.keyUp(key))
                true
            }

            else -> false
        }
    }

    override fun close() {
        stop()
    }

    private fun attachDispatcher() {
        if (!active) return
        runCatching { KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this) }
    }

    private fun handleWindowEvent(event: AWTEvent) {
        if (event !is WindowEvent) return
        if (event.id != WindowEvent.WINDOW_LOST_FOCUS && event.id != WindowEvent.WINDOW_DEACTIVATED) return
        val window = captureWindow
        if (window == null || event.window === window) stop(cancelled = true)
    }

    private fun publish(keys: Set<Int>) {
        _pressedKeys.value = keys.sorted()
    }

    private companion object {
        val LISTENER_MASK: Long = AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK
    }
}

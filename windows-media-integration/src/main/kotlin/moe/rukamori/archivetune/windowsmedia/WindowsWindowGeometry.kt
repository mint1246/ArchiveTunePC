/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.windowsmedia

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window

/**
 * Small Win32 boundary used by the custom desktop chrome while a window is being dragged.
 *
 * AWT's virtual-desktop coordinates are discontinuous on mixed-DPI Windows setups: a 150% monitor
 * can have a logical width but a physical origin. Mixing those coordinates with a pointer delta is
 * what makes a window jump/resize as the cursor crosses between displays. Win32 window and cursor
 * coordinates are both physical, so keeping the whole drag in this coordinate space avoids that.
 */
class WindowsWindowGeometry private constructor(
    private val hwnd: WinDef.HWND,
) {
    fun cursorPosition(): Point? = runCatching {
        val point = WinDef.POINT()
        if (!User32.INSTANCE.GetCursorPos(point)) return null
        Point(point.x, point.y)
    }.getOrNull()

    fun windowBounds(): Rectangle? = runCatching {
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) return null
        Rectangle(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    }.getOrNull()

    /**
     * Moves a floating window while explicitly keeping its physical pixel size.
     *
     * This is intentionally not SWP_NOSIZE. On a per-monitor-DPI-aware JVM, crossing onto a
     * monitor with a different scale can process WM_DPICHANGED during SetWindowPos and resize the
     * window to preserve its old logical size. Reasserting the drag-start width/height makes the
     * custom chrome preserve what the user actually sees on screen instead.
     */
    fun moveTo(x: Int, y: Int, width: Int, height: Int): Boolean = runCatching {
        User32.INSTANCE.SetWindowPos(
            hwnd,
            null,
            x,
            y,
            width,
            height,
            SWP_NOZORDER or SWP_NOACTIVATE,
        )
    }.getOrDefault(false)

    companion object {
        fun forWindow(window: Window): WindowsWindowGeometry? {
            if (!Platform.isWindows()) return null
            return runCatching {
                val pointer = Native.getWindowPointer(window)
                if (pointer == null) null else WindowsWindowGeometry(WinDef.HWND(pointer))
            }.getOrNull()
        }

        private const val SWP_NOZORDER = 0x0004
        private const val SWP_NOACTIVATE = 0x0010
    }
}

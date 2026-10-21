/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

/**
 * Windows virtual-key ids shared by the desktop app on every platform.
 *
 * The settings file stores these ids, so the Linux capture path has to normalize the platform
 * specific AWT key codes into exactly these values before a chord is persisted.
 */
object LinuxVirtualKeys {
    const val BACK_SPACE = 0x08
    const val TAB = 0x09
    const val ENTER = 0x0D
    const val SHIFT = 0x10
    const val CONTROL = 0x11
    const val ALT = 0x12
    const val PAUSE = 0x13
    const val CAPS_LOCK = 0x14
    const val ESCAPE = 0x1B
    const val SPACE = 0x20
    const val PAGE_UP = 0x21
    const val PAGE_DOWN = 0x22
    const val END = 0x23
    const val HOME = 0x24
    const val LEFT = 0x25
    const val UP = 0x26
    const val RIGHT = 0x27
    const val DOWN = 0x28
    const val INSERT = 0x2D
    const val DELETE = 0x2E
    const val WINDOWS = 0x5B
    const val MENU = 0x5D
    const val F1 = 0x70
    const val F12 = 0x7B
    const val NUM_LOCK = 0x90
    const val SCROLL_LOCK = 0x91
    const val LEFT_SHIFT = 0xA0
    const val RIGHT_SHIFT = 0xA1
    const val LEFT_CONTROL = 0xA2
    const val RIGHT_CONTROL = 0xA3
    const val LEFT_ALT = 0xA4
    const val RIGHT_ALT = 0xA5

    /** Collapses the left and right variants onto the generic modifier ids used for persistence. */
    fun normalize(virtualKey: Int): Int = when (virtualKey) {
        LEFT_SHIFT, RIGHT_SHIFT -> SHIFT
        LEFT_CONTROL, RIGHT_CONTROL -> CONTROL
        LEFT_ALT, RIGHT_ALT -> ALT
        0x5C -> WINDOWS // VK_RWIN
        else -> virtualKey
    }

    /** True for the modifier keys the global shortcut portal can express. */
    fun isModifier(virtualKey: Int): Boolean = when (normalize(virtualKey)) {
        SHIFT, CONTROL, ALT, WINDOWS -> true
        else -> false
    }
}

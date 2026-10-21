/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

/**
 * A shortcut the xdg-desktop-portal `GlobalShortcuts` portal is able to represent.
 *
 * [trigger] is the `preferred_trigger` string of the portal, for example `Ctrl+Shift+K`.
 */
data class LinuxGlobalShortcutDescriptor(
    val id: String,
    val description: String,
    val trigger: String,
)

/**
 * Maps the virtual keys that the desktop app persists onto portal shortcut triggers.
 *
 * The portal only accepts modifiers plus exactly one ordinary key, so a chord that holds several
 * ordinary keys is reported as unsupported instead of being bound to something else.
 */
object LinuxShortcutTriggers {
    private val MODIFIER_ORDER = listOf(
        LinuxVirtualKeys.CONTROL,
        LinuxVirtualKeys.ALT,
        LinuxVirtualKeys.SHIFT,
        LinuxVirtualKeys.WINDOWS,
    )

    /**
     * Modifier tokens of the shortcuts specification.
     *
     * The specification builds the trigger from the xkbcommon modifier names, so the tokens are
     * `CTRL`, `ALT`, `SHIFT` and `LOGO` and not the GTK accelerator spelling.
     */
    private val MODIFIER_TOKENS = mapOf(
        LinuxVirtualKeys.CONTROL to "CTRL",
        LinuxVirtualKeys.ALT to "ALT",
        LinuxVirtualKeys.SHIFT to "SHIFT",
        LinuxVirtualKeys.WINDOWS to "LOGO",
    )

    /** Normalized virtual keys of the chord. */
    fun normalize(virtualKeys: Set<Int>): Set<Int> =
        virtualKeys.mapTo(linkedSetOf<Int>(), LinuxVirtualKeys::normalize)

    /** Modifier keys of the chord, normalized to the generic modifier ids. */
    fun modifierKeys(virtualKeys: Set<Int>): Set<Int> =
        normalize(virtualKeys).filterTo(linkedSetOf<Int>(), LinuxVirtualKeys::isModifier)

    /** Ordinary (non modifier) keys of the chord. */
    fun ordinaryKeys(virtualKeys: Set<Int>): Set<Int> =
        normalize(virtualKeys).filterNotTo(linkedSetOf<Int>(), LinuxVirtualKeys::isModifier)

    /**
     * The portal trigger of the chord, or null when the portal cannot represent it.
     *
     * A chord is representable when it holds exactly one ordinary key that has a trigger token and
     * only modifiers the portal understands.
     */
    fun triggerFor(virtualKeys: Set<Int>): String? {
        val ordinary = ordinaryKeys(virtualKeys)
        if (ordinary.size != 1) return null
        val keyToken = keyToken(ordinary.single()) ?: return null
        val modifiers = modifierKeys(virtualKeys)
        if (modifiers.any { it !in MODIFIER_TOKENS }) return null
        val prefix = MODIFIER_ORDER.filter { it in modifiers }.map { MODIFIER_TOKENS.getValue(it) }
        return (prefix + keyToken).joinToString("+")
    }

    /** True when the portal is able to bind the chord as a system wide shortcut. */
    fun isSupported(virtualKeys: Set<Int>): Boolean = triggerFor(virtualKeys) != null

    /** Human readable form of the chord, used by the settings surface. */
    fun describe(virtualKeys: Set<Int>): String {
        val normalized = normalize(virtualKeys)
        if (normalized.isEmpty()) return ""
        val modifiers = MODIFIER_ORDER.filter { it in normalized }
        val ordinary = normalized.filterNot { it in MODIFIER_TOKENS }
        return (modifiers.map { friendlyModifier(it) } + ordinary.mapNotNull { friendlyKey(it) })
            .joinToString(" + ")
    }

    private fun friendlyModifier(virtualKey: Int): String = when (virtualKey) {
        LinuxVirtualKeys.CONTROL -> "Ctrl"
        LinuxVirtualKeys.ALT -> "Alt"
        LinuxVirtualKeys.SHIFT -> "Shift"
        LinuxVirtualKeys.WINDOWS -> "Super"
        else -> "?"
    }

    private fun friendlyKey(virtualKey: Int): String? = when (virtualKey) {
        in 0x30..0x39 -> (virtualKey - 0x30).toString()
        in 0x41..0x5A -> ('A' + (virtualKey - 0x41)).toString()
        in LinuxVirtualKeys.F1..0x87 -> "F${virtualKey - LinuxVirtualKeys.F1 + 1}"
        LinuxVirtualKeys.SPACE -> "Space"
        LinuxVirtualKeys.ENTER -> "Enter"
        LinuxVirtualKeys.ESCAPE -> "Escape"
        LinuxVirtualKeys.TAB -> "Tab"
        LinuxVirtualKeys.BACK_SPACE -> "Backspace"
        LinuxVirtualKeys.LEFT -> "Left"
        LinuxVirtualKeys.UP -> "Up"
        LinuxVirtualKeys.RIGHT -> "Right"
        LinuxVirtualKeys.DOWN -> "Down"
        LinuxVirtualKeys.HOME -> "Home"
        LinuxVirtualKeys.END -> "End"
        LinuxVirtualKeys.PAGE_UP -> "Page Up"
        LinuxVirtualKeys.PAGE_DOWN -> "Page Down"
        LinuxVirtualKeys.INSERT -> "Insert"
        LinuxVirtualKeys.DELETE -> "Delete"
        LinuxVirtualKeys.PAUSE -> "Pause"
        LinuxVirtualKeys.MENU -> "Menu"
        LinuxVirtualKeys.CAPS_LOCK -> "Caps Lock"
        LinuxVirtualKeys.NUM_LOCK -> "Num Lock"
        LinuxVirtualKeys.SCROLL_LOCK -> "Scroll Lock"
        else -> keyToken(virtualKey)
    }

    /**
     * Portal token of a single ordinary key, or null when the key has no representation.
     *
     * The names are the xkbcommon keysym names without the `XKB_KEY_` prefix, which is what the
     * shortcuts specification asks for. Base letters are lower case because an upper case letter
     * names the shifted symbol instead of the key itself.
     */
    fun keyToken(virtualKey: Int): String? = when (virtualKey) {
        in 0x30..0x39 -> (virtualKey - 0x30).toString()
        in 0x41..0x5A -> ('a' + (virtualKey - 0x41)).toString()
        in LinuxVirtualKeys.F1..0x87 -> "F${virtualKey - LinuxVirtualKeys.F1 + 1}"
        in 0x60..0x69 -> "KP_${virtualKey - 0x60}"
        LinuxVirtualKeys.SPACE -> "space"
        LinuxVirtualKeys.ENTER -> "Return"
        LinuxVirtualKeys.ESCAPE -> "Escape"
        LinuxVirtualKeys.TAB -> "Tab"
        LinuxVirtualKeys.BACK_SPACE -> "BackSpace"
        LinuxVirtualKeys.LEFT -> "Left"
        LinuxVirtualKeys.UP -> "Up"
        LinuxVirtualKeys.RIGHT -> "Right"
        LinuxVirtualKeys.DOWN -> "Down"
        LinuxVirtualKeys.HOME -> "Home"
        LinuxVirtualKeys.END -> "End"
        LinuxVirtualKeys.PAGE_UP -> "Prior"
        LinuxVirtualKeys.PAGE_DOWN -> "Next"
        LinuxVirtualKeys.INSERT -> "Insert"
        LinuxVirtualKeys.DELETE -> "Delete"
        LinuxVirtualKeys.PAUSE -> "Pause"
        LinuxVirtualKeys.MENU -> "Menu"
        LinuxVirtualKeys.CAPS_LOCK -> "Caps_Lock"
        LinuxVirtualKeys.NUM_LOCK -> "Num_Lock"
        LinuxVirtualKeys.SCROLL_LOCK -> "Scroll_Lock"
        0x6A -> "KP_Multiply"
        0x6B -> "KP_Add"
        0x6C -> "KP_Separator"
        0x6D -> "KP_Subtract"
        0x6E -> "KP_Decimal"
        0x6F -> "KP_Divide"
        0xBA -> "semicolon"
        0xBB -> "equal"
        0xBC -> "comma"
        0xBD -> "minus"
        0xBE -> "period"
        0xBF -> "slash"
        0xC0 -> "grave"
        0xDB -> "bracketleft"
        0xDC -> "backslash"
        0xDD -> "bracketright"
        0xDE -> "apostrophe"
        else -> null
    }
}

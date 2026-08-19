/* ArchiveTune (2026) — GPL-3.0-or-later */
package moe.rukamori.archivetune.desktop.ui

import androidx.compose.ui.input.key.Key

internal enum class DesktopShortcutAction {
    BACK,
    FORWARD,
    SEARCH,
    SETTINGS,
    ESCAPE,
}

/** Pure shortcut mapping so shell key handling stays consistent and unit-testable. */
internal fun desktopShortcutAction(
    key: Key,
    ctrlPressed: Boolean,
    altPressed: Boolean,
): DesktopShortcutAction? = when {
    ctrlPressed && !altPressed && (key == Key.F || key == Key.K) -> DesktopShortcutAction.SEARCH
    ctrlPressed && !altPressed && key == Key.Comma -> DesktopShortcutAction.SETTINGS
    altPressed && !ctrlPressed && key == Key.DirectionLeft -> DesktopShortcutAction.BACK
    altPressed && !ctrlPressed && key == Key.DirectionRight -> DesktopShortcutAction.FORWARD
    !ctrlPressed && !altPressed && key == Key.Escape -> DesktopShortcutAction.ESCAPE
    else -> null
}

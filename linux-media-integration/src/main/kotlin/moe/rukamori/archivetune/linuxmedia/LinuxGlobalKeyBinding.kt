/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

/**
 * One global Linux shortcut.
 *
 * [virtualKeys] uses the same Windows virtual-key ids that the desktop app persists on every
 * operating system, so a shortcut that was recorded on one platform keeps its meaning on another.
 */
data class LinuxGlobalKeyBinding(
    val actionId: String,
    val virtualKeys: Set<Int>,
    val repeatWhileHeld: Boolean = false,
)

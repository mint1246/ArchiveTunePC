/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

/** Receives the activation state changes of the shortcuts that are bound to a portal session. */
interface GlobalShortcutsListener {
    fun onActivated(shortcutId: String)

    fun onDeactivated(shortcutId: String)

    fun onClosed() {}
}

/**
 * Minimal surface of the `org.freedesktop.portal.GlobalShortcuts` portal.
 *
 * The interface exists so the shortcut lifecycle can be exercised without a live D-Bus session.
 */
interface GlobalShortcutsPortal : AutoCloseable {
    /** Listener that receives the activation changes, or null to stop listening. */
    fun setListener(listener: GlobalShortcutsListener?)

    /**
     * Binds the given shortcuts in a fresh portal session.
     *
     * The portal only allows a single bind attempt per session, so every call replaces the
     * previously bound shortcuts.
     */
    fun bind(shortcuts: List<LinuxGlobalShortcutDescriptor>): GlobalShortcutsBindResult
}

/** Outcome of a bind request against the portal. */
sealed interface GlobalShortcutsBindResult {
    /** The portal accepted the request; [boundIds] lists the shortcuts that are active. */
    data class Bound(val boundIds: List<String>) : GlobalShortcutsBindResult

    /**
     * The request failed; [reason] describes the problem in a user facing way.
     *
     * [cancelled] is true when the user dismissed the request, which must not be retried.
     */
    data class Failed(val reason: String, val cancelled: Boolean = false) : GlobalShortcutsBindResult
}

package moe.rukamori.archivetune.linuxmedia

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class GlobalShortcutsPortalSmokeTest {
    @Test fun `activation and deactivation are decoded across a real bus connection`() {
        assumeTrue(System.getenv("ARCHIVETUNE_PORTAL_SIGNAL_SMOKE") == "1", "Opt-in D-Bus signal smoke")
        val activated = java.util.concurrent.LinkedBlockingQueue<String>()
        val deactivated = java.util.concurrent.LinkedBlockingQueue<String>()
        DbusGlobalShortcutsPortal().use { portal ->
            portal.setListener(object : GlobalShortcutsListener {
                override fun onActivated(shortcutId: String) { activated.add(shortcutId) }
                override fun onDeactivated(shortcutId: String) { deactivated.add(shortcutId) }
            })
            val connect = portal.javaClass.getDeclaredMethod("ensureConnection").apply { isAccessible = true }
            assertNotNull(connect.invoke(portal))
            // An isolated synthetic session: no desktop shortcut, request, or user setting changes.
            val session = "/org/freedesktop/portal/desktop/session/archivetune_signal_test_${System.nanoTime()}"
            portal.javaClass.getDeclaredField("sessionHandle").apply { isAccessible = true }.set(portal, session)
            org.freedesktop.dbus.connections.impl.DBusConnectionBuilder.forSessionBus().withShared(false).build().use { sender ->
                for (member in listOf("Activated", "Deactivated")) {
                    sender.sendMessage(sender.messageFactory.createSignal(
                        null, "/org/freedesktop/portal/desktop", "org.freedesktop.portal.GlobalShortcuts",
                        member, "osta{sv}", org.freedesktop.dbus.DBusPath(session), "VOLUME_DOWN",
                        org.freedesktop.dbus.types.UInt64(123L), emptyMap<String, org.freedesktop.dbus.types.Variant<*>>(),
                    ))
                }
                assertEquals("VOLUME_DOWN", activated.poll(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("VOLUME_DOWN", deactivated.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `desktop portal registers the installed app and creates a session without prompting`() {
        assumeTrue(System.getenv("ARCHIVETUNE_PORTAL_SMOKE") == "1", "Opt-in portal session smoke")
        DbusGlobalShortcutsPortal(diagnostic = ::println).use { portal ->
            val connect = portal.javaClass.getDeclaredMethod("ensureConnection").apply { isAccessible = true }
            val bus = connect.invoke(portal) as DBusConnection
            val create = portal.javaClass.getDeclaredMethod("createSession", DBusConnection::class.java).apply { isAccessible = true }
            val session = create.invoke(portal, bus) as? String
            assertNotNull(session, "Portal refused session creation")
            assertTrue(session.startsWith("/org/freedesktop/portal/desktop/session/"))
            // No BindShortcuts call: this smoke never opens a permission dialog.
        }
    }
    @Test fun `desktop bind request accepts a chord and cancels promptly`() {
        assumeTrue(System.getenv("ARCHIVETUNE_PORTAL_BIND_SMOKE") == "1", "Opt-in visible portal cancellation smoke")
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
        val portal = DbusGlobalShortcutsPortal(diagnostic = ::println)
        try {
            val pending = worker.submit<GlobalShortcutsBindResult> {
                portal.bind(listOf(LinuxGlobalShortcutDescriptor("test_play_pause", "Temporary playback shortcut test", "CTRL+SHIFT+F12")))
            }
            Thread.sleep(1800)
            assertFalse(pending.isDone, "Desktop rejected the bind request before confirmation")
            val started = System.nanoTime()
            portal.close()
            val result = pending.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(result is GlobalShortcutsBindResult.Failed && result.cancelled)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000, "Closing a pending portal request blocked")
        } finally { portal.close(); worker.shutdownNow() }
    }
}

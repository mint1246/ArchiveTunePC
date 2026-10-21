package moe.rukamori.archivetune.linuxmedia

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class GlobalShortcutsPortalSmokeTest {
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

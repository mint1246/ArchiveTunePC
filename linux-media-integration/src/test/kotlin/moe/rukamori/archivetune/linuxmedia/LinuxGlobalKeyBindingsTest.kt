package moe.rukamori.archivetune.linuxmedia

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxGlobalKeyBindingsTest {
    private class FakePortal : GlobalShortcutsPortal {
        val bindLatch = CountDownLatch(1)
        var bound: List<LinuxGlobalShortcutDescriptor> = emptyList()
        var result: GlobalShortcutsBindResult = GlobalShortcutsBindResult.Bound(emptyList())
        var closed = false
        var bindCount = 0
        private var listener: GlobalShortcutsListener? = null

        override fun setListener(listener: GlobalShortcutsListener?) {
            this.listener = listener
        }

        override fun bind(shortcuts: List<LinuxGlobalShortcutDescriptor>): GlobalShortcutsBindResult {
            bindCount += 1
            bound = shortcuts
            bindLatch.countDown()
            return result
        }

        override fun close() {
            closed = true
        }

        fun activate(shortcutId: String) {
            listener?.onActivated(shortcutId)
        }

        fun deactivate(shortcutId: String) {
            listener?.onDeactivated(shortcutId)
        }
    }

    @Test
    fun `a chord is bound with the shortcuts specification trigger`() {
        val portal = FakePortal()
        val bindings = LinuxGlobalKeyBindings(onAction = {}, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("PLAY_PAUSE", setOf(0x11, 0x10, 0x4B))))

        assertTrue(portal.bindLatch.await(5, TimeUnit.SECONDS), "the portal was never asked to bind")
        assertEquals(1, portal.bound.size)
        assertEquals("PLAY_PAUSE", portal.bound.single().id)
        assertEquals("CTRL+SHIFT+k", portal.bound.single().trigger)
        bindings.close()
    }

    @Test
    fun `an activation of the portal fires the action`() {
        val portal = FakePortal()
        val actions = mutableListOf<String>()
        val bindings = LinuxGlobalKeyBindings(onAction = { actions += it }, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("NEXT", setOf(0x11, 0x4E))))
        assertTrue(portal.bindLatch.await(5, TimeUnit.SECONDS), "the portal was never asked to bind")

        portal.activate("NEXT")

        assertEquals(listOf("NEXT"), actions)
        bindings.close()
    }

    @Test
    fun `the capture suspends the activations`() {
        val portal = FakePortal()
        val actions = mutableListOf<String>()
        val bindings = LinuxGlobalKeyBindings(onAction = { actions += it }, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("NEXT", setOf(0x11, 0x4E))))
        assertTrue(portal.bindLatch.await(5, TimeUnit.SECONDS), "the portal was never asked to bind")

        bindings.setCaptureActive(true)
        portal.activate("NEXT")
        assertTrue(actions.isEmpty(), "a recorded shortcut must not trigger an action")

        bindings.setCaptureActive(false)
        portal.activate("NEXT")
        assertEquals(listOf("NEXT"), actions)
        bindings.close()
    }

    @Test
    fun `a chord with several ordinary keys is reported instead of being bound`() {
        val portal = FakePortal()
        val bindings = LinuxGlobalKeyBindings(onAction = {}, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("PLAY_PAUSE", setOf(0x11, 0x41, 0x42))))

        val status = awaitStatus(bindings)
        assertNotNull(status, "the unsupported chord has to be reported")
        assertTrue(status.contains("modifiers plus one key"), "unexpected status: $status")
        assertTrue(portal.bound.isEmpty(), "an unsupported chord must not be bound")
        bindings.close()
    }

    @Test
    fun `a chord the desktop does not accept is reported`() {
        val portal = FakePortal()
        portal.result = GlobalShortcutsBindResult.Bound(emptyList())
        val bindings = LinuxGlobalKeyBindings(onAction = {}, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("PLAY_PAUSE", setOf(0x11, 0x4B))))

        val status = awaitStatus(bindings)
        assertNotNull(status, "a shortcut the desktop ignored has to be reported")
        assertTrue(status.contains("did not accept"), "unexpected status: $status")
        bindings.close()
    }

    @Test
    fun `a dismissed request is reported and not retried`() {
        val portal = FakePortal()
        portal.result = GlobalShortcutsBindResult.Failed("the shortcut request was dismissed", cancelled = true)
        val bindings = LinuxGlobalKeyBindings(onAction = {}, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("PLAY_PAUSE", setOf(0x11, 0x4B))))

        val status = awaitStatus(bindings)
        assertNotNull(status, "a dismissed request has to be reported")
        assertTrue(status.contains("dismissed"), "unexpected status: $status")
        Thread.sleep(200L)
        assertEquals(1, portal.bindCount, "a dismissed request must not be retried")
        bindings.close()
    }

    @Test
    fun `closing releases the portal`() {
        val portal = FakePortal()
        val bindings = LinuxGlobalKeyBindings(onAction = {}, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("PLAY_PAUSE", setOf(0x11, 0x4B))))
        assertTrue(portal.bindLatch.await(5, TimeUnit.SECONDS), "the portal was never asked to bind")

        bindings.close()

        assertTrue(portal.closed, "the portal has to be closed")
        assertNull(bindings.status.value)
    }

    @Test
    fun `an activation of an unknown action is ignored`() {
        val portal = FakePortal()
        val actions = mutableListOf<String>()
        val bindings = LinuxGlobalKeyBindings(onAction = { actions += it }, portalFactory = { portal })

        bindings.updateBindings(listOf(LinuxGlobalKeyBinding("NEXT", setOf(0x11, 0x4E))))
        assertTrue(portal.bindLatch.await(5, TimeUnit.SECONDS), "the portal was never asked to bind")

        portal.activate("SOMETHING_ELSE")

        assertTrue(actions.isEmpty(), "an unknown action must not fire")
        bindings.close()
    }

    private fun awaitStatus(bindings: LinuxGlobalKeyBindings, timeoutMs: Long = 5_000L): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = bindings.status.value
            if (status != null) return status
            Thread.sleep(10L)
        }
        return bindings.status.value
    }
}

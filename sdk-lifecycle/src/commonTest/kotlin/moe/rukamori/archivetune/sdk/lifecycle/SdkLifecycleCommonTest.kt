/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.lifecycle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SdkLifecycleCommonTest {
    @Test
    fun closeCancelsScopesRunsHooksAndClosesComponentsInReverseOrder() {
        val events = mutableListOf<String>()
        val lifecycle = SdkLifecycle(Dispatchers.Default)
        val childScope = lifecycle.scope()
        lifecycle.register(AutoCloseable { events += "component-1" })
        lifecycle.register(AutoCloseable { events += "component-2" })
        lifecycle.onClosing("first") { events += "hook-1" }
        lifecycle.onClosing("first") { events += "hook-duplicate" }
        lifecycle.onClosing("second") { events += "hook-2" }

        lifecycle.close()
        lifecycle.close()

        assertTrue(lifecycle.isClosed)
        assertFalse(childScope.isActive)
        assertEquals(listOf("hook-2", "hook-1", "component-2", "component-1"), events)
    }

    @Test
    fun closeAggregatesFailuresWithoutSkippingRemainingCleanups() {
        val events = mutableListOf<String>()
        val lifecycle = SdkLifecycle(Dispatchers.Default)
        lifecycle.register(AutoCloseable { events += "ok" })
        lifecycle.register(AutoCloseable {
            events += "fail-2"
            throw IllegalStateException("second")
        })
        lifecycle.register(AutoCloseable {
            events += "fail-1"
            throw IllegalArgumentException("first")
        })

        val thrown = assertFailsWith<IllegalArgumentException> { lifecycle.close() }
        assertEquals("first", thrown.message)
        assertEquals(1, thrown.suppressedExceptions.size)
        assertEquals("second", thrown.suppressedExceptions.single().message)
        assertEquals(listOf("fail-1", "fail-2", "ok"), events)
    }

    @Test
    fun registerAfterCloseClosesImmediately() {
        val lifecycle = SdkLifecycle(Dispatchers.Default)
        lifecycle.close()
        var closedLate = false
        lifecycle.register(AutoCloseable { closedLate = true })
        assertTrue(closedLate)
        assertFailsWith<IllegalStateException> { lifecycle.scope() }
    }
}

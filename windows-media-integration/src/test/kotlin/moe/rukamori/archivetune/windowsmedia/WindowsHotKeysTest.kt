package moe.rukamori.archivetune.windowsmedia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsHotKeysTest {
    private val bindings = listOf(
        WindowsHotKeyBinding(1, 0xB3, MediaCommand.PLAY_PAUSE),
        WindowsHotKeyBinding(2, 0xB0, MediaCommand.NEXT),
        WindowsHotKeyBinding(3, 0xB1, MediaCommand.PREVIOUS),
    )

    @Test
    fun `all failed registrations report unsupported and own nothing`() {
        val api = FakeRegistrationApi(successfulIds = emptySet())
        val registrations = WindowsHotKeyRegistrations(api)

        assertFailsWith<UnsupportedOperationException> { registrations.register(bindings) }
        registrations.close()

        assertEquals(listOf(1, 2, 3), api.registeredIds)
        assertTrue(api.unregisteredIds.isEmpty())
    }

    @Test
    fun `partial registration tracks and unregisters only successful ids`() {
        val api = FakeRegistrationApi(successfulIds = setOf(1, 3))
        val registrations = WindowsHotKeyRegistrations(api)

        val active = registrations.register(bindings)

        assertEquals(mapOf(1 to MediaCommand.PLAY_PAUSE, 3 to MediaCommand.PREVIOUS), active)
        registrations.close()
        assertEquals(listOf(1, 3), api.unregisteredIds)
    }

    @Test
    fun `full registration is closed exactly once`() {
        val api = FakeRegistrationApi(successfulIds = setOf(1, 2, 3))
        val registrations = WindowsHotKeyRegistrations(api)

        assertEquals(setOf(1, 2, 3), registrations.register(bindings).keys)
        registrations.close()
        registrations.close()

        assertEquals(listOf(1, 2, 3), api.unregisteredIds)
    }

    @Test
    fun `registration exception cleans up ids acquired before failure`() {
        val api = FakeRegistrationApi(successfulIds = setOf(1, 2, 3), throwOnRegisterId = 2)
        val registrations = WindowsHotKeyRegistrations(api)

        assertFailsWith<IllegalStateException> { registrations.register(bindings) }

        assertEquals(listOf(1, 2), api.registeredIds)
        assertEquals(listOf(1), api.unregisteredIds)
    }

    @Test
    fun `unregister exception still releases remaining owned ids`() {
        val api = FakeRegistrationApi(successfulIds = setOf(1, 2, 3), throwOnUnregisterId = 2)
        val registrations = WindowsHotKeyRegistrations(api)
        registrations.register(bindings)

        assertFailsWith<IllegalStateException> { registrations.close() }

        assertEquals(listOf(1, 2, 3), api.unregisteredIds)
        registrations.close()
        assertEquals(listOf(1, 2, 3), api.unregisteredIds)
    }

    private class FakeRegistrationApi(
        private val successfulIds: Set<Int>,
        private val throwOnRegisterId: Int? = null,
        private val throwOnUnregisterId: Int? = null,
    ) : WindowsHotKeyRegistrationApi {
        val registeredIds = mutableListOf<Int>()
        val unregisteredIds = mutableListOf<Int>()

        override fun register(id: Int, virtualKey: Int): Boolean {
            registeredIds += id
            if (id == throwOnRegisterId) error("register failed")
            return id in successfulIds
        }

        override fun unregister(id: Int) {
            unregisteredIds += id
            if (id == throwOnUnregisterId) error("unregister failed")
        }
    }
}

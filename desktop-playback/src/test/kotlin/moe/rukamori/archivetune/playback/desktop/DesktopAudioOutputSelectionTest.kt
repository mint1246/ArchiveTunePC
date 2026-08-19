package moe.rukamori.archivetune.playback.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAudioOutputSelectionTest {
    @Test
    fun `enumeration prepends system default and normalizes duplicate devices`() {
        val backend = FakeAudioOutputBackend(
            devices = listOf(
                DesktopAudioOutputDevice(" speakers ", " Main speakers "),
                DesktopAudioOutputDevice("speakers", "Duplicate speakers"),
                DesktopAudioOutputDevice("", "Ignored"),
                DesktopAudioOutputDevice("headphones", ""),
            ),
        )
        val selection = DesktopAudioOutputSelection()

        selection.replaceBackend(backend)

        assertEquals(listOf(null, "speakers", "headphones"), selection.state.value.devices.map { it.id })
        assertEquals(listOf("System default", "Main speakers", "headphones"), selection.state.value.devices.map { it.name })
        assertNull(selection.state.value.selectedDeviceId)
        assertEquals(listOf<String?>(null), backend.selections)
    }

    @Test
    fun `saved device is restored when backend attaches`() {
        val backend = FakeAudioOutputBackend(devices = standardDevices())
        val selection = DesktopAudioOutputSelection(initialDeviceId = " headphones ")

        selection.replaceBackend(backend)

        assertTrue(selection.state.value.resolved)
        assertEquals("headphones", selection.state.value.selectedDeviceId)
        assertEquals(listOf<String?>("headphones"), backend.selections)
    }

    @Test
    fun `missing saved device falls back to default and stays default after reattach`() {
        val first = FakeAudioOutputBackend(devices = listOf(device("speakers", "Speakers")))
        val second = FakeAudioOutputBackend(devices = standardDevices())
        val selection = DesktopAudioOutputSelection(initialDeviceId = "unplugged-headset")

        selection.replaceBackend(first)
        assertTrue(selection.state.value.resolved)
        assertNull(selection.state.value.selectedDeviceId)
        assertEquals(listOf<String?>(null), first.selections)

        selection.replaceBackend(null)
        selection.replaceBackend(second)

        assertNull(selection.state.value.selectedDeviceId)
        assertEquals(listOf<String?>(null), second.selections)
    }

    @Test
    fun `live selection switches device and can return to system default`() {
        val backend = FakeAudioOutputBackend(devices = standardDevices())
        val selection = DesktopAudioOutputSelection()
        selection.replaceBackend(backend)
        backend.selections.clear()

        val headphones = selection.select("headphones")
        val default = selection.select(null)

        assertEquals("headphones", headphones.selectedDeviceId)
        assertNull(default.selectedDeviceId)
        assertEquals(listOf<String?>("headphones", null), backend.selections)
    }

    @Test
    fun `backend replacement reapplies saved preference to recreated player`() {
        val first = FakeAudioOutputBackend(devices = standardDevices())
        val second = FakeAudioOutputBackend(devices = standardDevices())
        val selection = DesktopAudioOutputSelection()

        selection.replaceBackend(first)
        selection.select("headphones")
        selection.replaceBackend(null)
        assertFalse(selection.state.value.resolved)
        selection.replaceBackend(second)

        assertEquals(listOf<String?>(null, "headphones"), first.selections)
        assertEquals(listOf<String?>("headphones"), second.selections)
        assertEquals("headphones", selection.state.value.selectedDeviceId)
    }

    @Test
    fun `enumeration failure preserves preference for a later backend`() {
        val broken = FakeAudioOutputBackend(
            devices = emptyList(),
            enumerationFailure = IllegalStateException("enumeration failed"),
        )
        val recovered = FakeAudioOutputBackend(devices = standardDevices())
        val selection = DesktopAudioOutputSelection(initialDeviceId = "headphones")

        selection.replaceBackend(broken)
        assertFalse(selection.state.value.resolved)
        assertTrue(selection.state.value.errorMessage?.contains("enumeration failed") == true)
        assertTrue(broken.selections.isEmpty())

        selection.replaceBackend(recovered)

        assertEquals("headphones", selection.state.value.selectedDeviceId)
        assertEquals(listOf<String?>("headphones"), recovered.selections)
    }

    private fun standardDevices(): List<DesktopAudioOutputDevice> = listOf(
        device("speakers", "Speakers"),
        device("headphones", "Headphones"),
    )

    private fun device(id: String, name: String) = DesktopAudioOutputDevice(id, name)

    private class FakeAudioOutputBackend(
        var devices: List<DesktopAudioOutputDevice>,
        var enumerationFailure: Throwable? = null,
    ) : DesktopAudioOutputBackend {
        val selections = mutableListOf<String?>()

        override fun outputDevices(): List<DesktopAudioOutputDevice> {
            enumerationFailure?.let { throw it }
            return devices
        }

        override fun selectOutputDevice(deviceId: String?) {
            selections += deviceId
        }
    }
}

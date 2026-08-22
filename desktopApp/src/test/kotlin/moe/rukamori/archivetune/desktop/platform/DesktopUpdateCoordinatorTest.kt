package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopUpdateCoordinatorTest {
    @Test
    fun comparesNumericVersions() {
        assertTrue(compareVersions("14.2.0", "14.1.9") > 0)
        assertTrue(compareVersions("15.0", "14.99.99") > 0)
        assertTrue(compareVersions("v14.1.1", "14.1.0") > 0)
        assertEquals(0, compareVersions("14.1", "14.1.0"))
    }
}
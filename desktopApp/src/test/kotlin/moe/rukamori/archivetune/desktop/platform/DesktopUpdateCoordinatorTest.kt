package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateCoordinatorTest {
    @Test
    fun comparesNumericVersions() {
        assertTrue(compareVersions("14.2.0", "14.1.9") > 0)
        assertTrue(compareVersions("15.0", "14.99.99") > 0)
        assertTrue(compareVersions("v14.1.1", "14.1.0") > 0)
        assertEquals(0, compareVersions("14.1", "14.1.0"))
    }

    @Test
    fun `build hash overrides version comparison`() {
        val current = "1".repeat(64)
        val different = "2".repeat(64)

        assertTrue(shouldStageDesktopUpdate("14.15", "14.15", different, current))
        assertTrue(shouldStageDesktopUpdate("1.0", "99.0", different, current))
        assertFalse(shouldStageDesktopUpdate("99.0", "1.0", current, current))
    }

    @Test
    fun `legacy manifest without build hash still uses version`() {
        assertTrue(shouldStageDesktopUpdate("14.16", "14.15", null, null))
        assertFalse(shouldStageDesktopUpdate("14.15", "14.15", null, null))
        assertFalse(shouldStageDesktopUpdate("14.14", "14.15", null, null))
    }
}

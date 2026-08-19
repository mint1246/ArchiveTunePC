package moe.rukamori.archivetune.desktop.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import moe.rukamori.archivetune.desktop.state.DesktopThemePreference

class ArchiveTuneDesktopThemeTest {
    @Test
    fun `custom accent recolors roles without changing light and dark surfaces`() {
        val seed = 0xFF4D_7CFEL
        val light = archiveTuneStaticColorScheme(dark = false, accentSeedArgb = seed)
        val dark = archiveTuneStaticColorScheme(dark = true, accentSeedArgb = seed)

        assertEquals(Color(0xFFF7FBF5), light.background)
        assertEquals(Color(0xFF101412), dark.background)
        assertNotEquals(Color(0xFF376A43), light.primary)
        assertNotEquals(Color(0xFF9FC8A6), dark.primary)
        assertNotEquals(light.primary, dark.primary)
    }

    @Test
    fun `default accent preserves branded schemes and seed alpha is ignored`() {
        assertEquals(Color(0xFF376A43), archiveTuneStaticColorScheme(dark = false, accentSeedArgb = null).primary)
        assertEquals(Color(0xFF9FC8A6), archiveTuneStaticColorScheme(dark = true, accentSeedArgb = null).primary)
        assertEquals(
            archiveTuneStaticColorScheme(dark = false, accentSeedArgb = 0xFF4D_7CFEL).primary,
            archiveTuneStaticColorScheme(dark = false, accentSeedArgb = 0x004D_7CFEL).primary,
        )
    }

    @Test
    fun `dynamic artwork palette stays authoritative over custom accent`() {
        val palette = DynamicThemePalette(
            primary = Color(0xFFCC5533),
            secondary = Color(0xFF3377AA),
            tertiary = Color(0xFF9966BB),
            neutral = Color(0xFF444444),
        )

        val blueAccent = archiveTuneColorScheme(DesktopThemePreference.DYNAMIC, true, 0xFF4D_7CFEL, palette)
        val roseAccent = archiveTuneColorScheme(DesktopThemePreference.DYNAMIC, true, 0xFFC7_5573L, palette)

        assertEquals(blueAccent.primary, roseAccent.primary)
        assertEquals(blueAccent.secondary, roseAccent.secondary)
        assertEquals(blueAccent.tertiary, roseAccent.tertiary)
        assertEquals(blueAccent.surface, roseAccent.surface)
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.sdk.settings

import moe.rukamori.archivetune.desktop.settings.DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES
import moe.rukamori.archivetune.desktop.settings.DesktopEqualizerSettings
import moe.rukamori.archivetune.desktop.settings.DesktopKeybind
import moe.rukamori.archivetune.desktop.settings.DesktopKeybindAction
import moe.rukamori.archivetune.desktop.settings.DesktopSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSettingsPolicyTest {
    @Test
    fun assignKeybindResolvesActionAndChordConflicts() {
        val initial = DesktopSettings(
            keybinds = listOf(
                DesktopKeybind(DesktopKeybindAction.PLAY_PAUSE, listOf(0x11, 0x20)),
                DesktopKeybind(DesktopKeybindAction.NEXT, listOf(0x11, 0x27)),
            ),
        )

        val reassigned = DesktopSettingsPolicy.assignKeybind(
            initial,
            DesktopKeybind(DesktopKeybindAction.VOLUME_UP, listOf(0x20, 0x11), repeatWhileHeld = true),
        )

        assertEquals(
            listOf(
                DesktopKeybind(DesktopKeybindAction.NEXT, listOf(0x11, 0x27)),
                DesktopKeybind(DesktopKeybindAction.VOLUME_UP, listOf(0x20, 0x11), repeatWhileHeld = true),
            ),
            reassigned.keybinds,
        )

        val cleared = DesktopSettingsPolicy.clearKeybind(reassigned, DesktopKeybindAction.NEXT)
        assertEquals(
            listOf(DesktopKeybind(DesktopKeybindAction.VOLUME_UP, listOf(0x20, 0x11), repeatWhileHeld = true)),
            cleared.keybinds,
        )
    }

    @Test
    fun toggleLyricsProviderExpandsDefaultsRejectsEmptyAndCollapsesFullSelection() {
        val defaults = listOf("lrclib", "betterlyrics", "kugou")
        assertTrue(DesktopSettingsPolicy.isLyricsProviderEnabled(emptyList(), "lrclib"))

        val afterDisable = DesktopSettingsPolicy.toggleLyricsProvider(
            DesktopSettings(lyricsProviderIds = emptyList()),
            "betterlyrics",
            defaults,
        ) as LyricsProviderToggleResult.Updated
        assertEquals(listOf("lrclib", "kugou"), afterDisable.settings.lyricsProviderIds)
        assertFalse(DesktopSettingsPolicy.isLyricsProviderEnabled(afterDisable.settings.lyricsProviderIds, "betterlyrics"))

        val afterReenable = DesktopSettingsPolicy.toggleLyricsProvider(
            afterDisable.settings,
            "betterlyrics",
            defaults,
        ) as LyricsProviderToggleResult.Updated
        assertEquals(emptyList<String>(), afterReenable.settings.lyricsProviderIds)

        val single = DesktopSettings(lyricsProviderIds = listOf("lrclib"))
        val rejected = DesktopSettingsPolicy.toggleLyricsProvider(single, "lrclib", defaults)
        assertEquals(LyricsProviderToggleResult.EmptySelectionNotAllowed, rejected)
    }

    @Test
    fun toggleSponsorBlockCategoryTogglesKnownCategoriesAndFiltersUnknown() {
        val initial = DesktopSettings(sponsorBlockCategories = DEFAULT_DESKTOP_SPONSOR_BLOCK_CATEGORIES)
        val withoutSponsor = DesktopSettingsPolicy.toggleSponsorBlockCategory(initial, "sponsor")
        assertFalse("sponsor" in withoutSponsor.sponsorBlockCategories)

        val restored = DesktopSettingsPolicy.toggleSponsorBlockCategory(withoutSponsor, "sponsor")
        assertTrue("sponsor" in restored.sponsorBlockCategories)

        val unknownIgnored = DesktopSettingsPolicy.toggleSponsorBlockCategory(restored, "not_a_real_category")
        assertFalse("not_a_real_category" in unknownIgnored.sponsorBlockCategories)
    }

    @Test
    fun equalizerPolicyClampsGainsAndClearsPresetOnManualEdit() {
        val preset = DesktopSettingsPolicy.applyEqualizerPreset(
            presetName = "Rock",
            preampDb = 25f,
            bandGainsDb = listOf(-30f, 3.5f),
        )
        assertEquals(true, preset.enabled)
        assertEquals("Rock", preset.presetName)
        assertEquals(20f, preset.preampDb, 0.0001f)
        assertEquals(listOf(-20f, 3.5f), preset.bandGainsDb)

        val afterPreamp = DesktopSettingsPolicy.updateEqualizerPreamp(preset, -4.5f)
        assertNull(afterPreamp.presetName)
        assertEquals(-4.5f, afterPreamp.preampDb, 0.0001f)

        val afterBand = DesktopSettingsPolicy.updateEqualizerBand(
            equalizer = DesktopEqualizerSettings(enabled = true, presetName = "Pop", bandGainsDb = listOf(1f)),
            bandIndex = 2,
            bandCount = 4,
            gainDb = 12f,
        )
        assertNull(afterBand.presetName)
        assertEquals(listOf(1f, 0f, 12f, 0f), afterBand.bandGainsDb)
    }

    @Test
    fun crossfadeAndUrlPoliciesPreserveBoundsAndFormatting() {
        assertEquals(5.5f, DesktopSettingsPolicy.snapCrossfadeDurationSeconds(5.4f), 0.0001f)
        assertEquals(10f, DesktopSettingsPolicy.snapCrossfadeDurationSeconds(15f), 0.0001f)
        assertEquals(7_500L, DesktopSettingsPolicy.crossfadeDurationMillis(DesktopSettings(crossfadeDurationSeconds = 7.5f)))

        assertNull(DesktopSettingsPolicy.normalizeHttpsUrl("http://sponsor.ajay.app"))
        assertNull(DesktopSettingsPolicy.normalizeHttpsUrl("https://sponsor.ajay.app?foo=bar"))
        assertEquals("https://sponsor.ajay.app", DesktopSettingsPolicy.normalizeHttpsUrl("  https://sponsor.ajay.app/  "))
        assertEquals("https://example.com/2.0/", DesktopSettingsPolicy.normalizeLastFmEndpoint("https://example.com/2.0/"))
        assertEquals(0, DesktopSettingsPolicy.sanitizeCanvasCacheLimitMb(0))
        assertEquals(64, DesktopSettingsPolicy.sanitizeCanvasCacheLimitMb(10))
    }
}

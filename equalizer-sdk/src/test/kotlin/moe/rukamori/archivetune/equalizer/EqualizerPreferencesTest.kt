/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.equalizer

import moe.rukamori.archivetune.playback.EqProfile
import moe.rukamori.archivetune.playback.EqSettings
import moe.rukamori.archivetune.playback.EqualizerJson
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class InMemoryPreferences(
    initial: Map<String, Any> = emptyMap(),
) : EqualizerPreferenceWriter {
    private val values = initial.toMutableMap()

    @Suppress("UNCHECKED_CAST")
    override fun string(key: String): String? = values[key] as? String

    @Suppress("UNCHECKED_CAST")
    override fun boolean(key: String): Boolean? = values[key] as? Boolean

    @Suppress("UNCHECKED_CAST")
    override fun int(key: String): Int? = values[key] as? Int

    override fun setString(key: String, value: String) {
        values[key] = value
    }

    override fun setBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun setInt(key: String, value: Int) {
        values[key] = value
    }

    fun snapshot(): Map<String, Any> = values.toMap()
}

class EqualizerPreferencesTest {
    @Test
    fun `defaults are applied for missing preferences`() {
        val stored = EqualizerPreferences.read(InMemoryPreferences())

        assertEquals(EqualizerControlMode.BASIC, stored.controlMode)
        assertEquals(EqualizerProfileIds.FLAT, stored.selectedProfileId)
        assertEquals(
            EqSettings(
                enabled = false,
                bandLevelsMb = emptyList(),
                outputGainEnabled = false,
                outputGainMb = 0,
                bassBoostEnabled = false,
                bassBoostStrength = 0,
                virtualizerEnabled = false,
                virtualizerStrength = 0,
                autoHeadroomEnabled = false,
            ),
            stored.settings,
        )
        assertTrue(stored.profiles.isEmpty())
    }

    @Test
    fun `serialized key names are stable`() {
        assertEquals("equalizerEnabled", EqualizerPreferenceKeys.ENABLED)
        assertEquals("equalizerControlMode", EqualizerPreferenceKeys.CONTROL_MODE)
        assertEquals("equalizerBandLevelsMb", EqualizerPreferenceKeys.BAND_LEVELS_MB)
        assertEquals("equalizerAutoHeadroomEnabled", EqualizerPreferenceKeys.AUTO_HEADROOM_ENABLED)
        assertEquals("equalizerOutputGainEnabled", EqualizerPreferenceKeys.OUTPUT_GAIN_ENABLED)
        assertEquals("equalizerOutputGainMb", EqualizerPreferenceKeys.OUTPUT_GAIN_MB)
        assertEquals("equalizerBassBoostEnabled", EqualizerPreferenceKeys.BASS_BOOST_ENABLED)
        assertEquals("equalizerBassBoostStrength", EqualizerPreferenceKeys.BASS_BOOST_STRENGTH)
        assertEquals("equalizerVirtualizerEnabled", EqualizerPreferenceKeys.VIRTUALIZER_ENABLED)
        assertEquals("equalizerVirtualizerStrength", EqualizerPreferenceKeys.VIRTUALIZER_STRENGTH)
        assertEquals("equalizerSelectedProfileId", EqualizerPreferenceKeys.SELECTED_PROFILE_ID)
        assertEquals("equalizerCustomProfilesJson", EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON)
    }

    @Test
    fun `out of range persisted strengths are coerced`() {
        val stored =
            EqualizerPreferences.read(
                InMemoryPreferences(
                    mapOf(
                        EqualizerPreferenceKeys.BASS_BOOST_STRENGTH to 9_999,
                        EqualizerPreferenceKeys.VIRTUALIZER_STRENGTH to -50,
                        EqualizerPreferenceKeys.OUTPUT_GAIN_MB to 9_999,
                    ),
                ),
            )

        assertEquals(1_000, stored.settings.bassBoostStrength)
        assertEquals(0, stored.settings.virtualizerStrength)
        assertEquals(9_999, stored.settings.outputGainMb)
    }

    @Test
    fun `malformed JSON falls back to defaults`() {
        val stored =
            EqualizerPreferences.read(
                InMemoryPreferences(
                    mapOf(
                        EqualizerPreferenceKeys.BAND_LEVELS_MB to "not json",
                        EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON to "{broken",
                    ),
                ),
            )

        assertTrue(stored.settings.bandLevelsMb.isEmpty())
        assertTrue(stored.profiles.isEmpty())
    }

    @Test
    fun `band levels JSON round trips exactly`() {
        val levels = listOf(-1200, 0, 350, 1500)
        val encoded = EqualizerPreferences.encodeLevels(levels)

        assertEquals("[${levels.joinToString(",")}]", encoded)
        assertEquals(levels, EqualizerPreferences.decodeLevels(encoded))
    }

    @Test
    fun `profile JSON round trips with defaults and unknown keys`() {
        val raw =
            """
            {"profiles":[{"id":"abc","name":"My curve","bandLevelsMb":[100,-100],"futureField":42}],"unknown":true}
            """.trimIndent()

        val profiles = EqualizerPreferences.decodeProfiles(raw)

        assertEquals(1, profiles.size)
        assertEquals("abc", profiles.first().id)
        assertEquals(listOf(100, -100), profiles.first().bandLevelsMb)
        assertEquals(0, profiles.first().outputGainMb)
        assertEquals(false, profiles.first().autoHeadroomEnabled)
    }

    @Test
    fun `manual edits detach from a saved profile without changing other values`() {
        val prefs = InMemoryPreferences()
        EqualizerPreferences.setEnabled(prefs, true)
        EqualizerPreferences.updateBandLevels(prefs, listOf(100, 200))
        EqualizerPreferences.applyProfile(prefs, EqProfile(id = "p1", name = "Curve"))
        assertEquals(EqualizerProfileIds.stored("p1"), prefs.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID))

        EqualizerPreferences.setBassBoostStrength(prefs, 250)

        assertEquals(EqualizerProfileIds.MANUAL, prefs.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID))
        assertEquals(250, prefs.int(EqualizerPreferenceKeys.BASS_BOOST_STRENGTH))
        assertEquals(true, prefs.boolean(EqualizerPreferenceKeys.ENABLED))
    }

    @Test
    fun `applying a profile writes every setting with clamped values`() {
        val prefs = InMemoryPreferences()
        EqualizerPreferences.applyProfile(
            prefs,
            EqProfile(
                id = "p1",
                name = "Loud",
                bandLevelsMb = listOf(100, -100),
                outputGainMb = 9_999,
                bassBoostStrength = 2_000,
                virtualizerStrength = -10,
                autoHeadroomEnabled = true,
            ),
        )

        assertEquals(true, prefs.boolean(EqualizerPreferenceKeys.ENABLED))
        assertEquals(1_500, prefs.int(EqualizerPreferenceKeys.OUTPUT_GAIN_MB))
        assertEquals(true, prefs.boolean(EqualizerPreferenceKeys.OUTPUT_GAIN_ENABLED))
        assertEquals(1_000, prefs.int(EqualizerPreferenceKeys.BASS_BOOST_STRENGTH))
        assertEquals(true, prefs.boolean(EqualizerPreferenceKeys.BASS_BOOST_ENABLED))
        assertEquals(0, prefs.int(EqualizerPreferenceKeys.VIRTUALIZER_STRENGTH))
        // The enabled flag is derived from the original (unclamped) strength, matching the
        // pre-extraction behavior: a negative strength still enables the effect.
        assertEquals(true, prefs.boolean(EqualizerPreferenceKeys.VIRTUALIZER_ENABLED))
        assertEquals(true, prefs.boolean(EqualizerPreferenceKeys.AUTO_HEADROOM_ENABLED))
        assertEquals(EqualizerProfileIds.stored("p1"), prefs.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID))
    }

    @Test
    fun `saving profiles de-duplicates by id and sorts by name on write`() {
        val prefs = InMemoryPreferences()
        val first = EqualizerPreferences.saveProfile(prefs, "Zeta", emptyList(), defaultSettings())
        val second = EqualizerPreferences.saveProfile(prefs, "Alpha", emptyList(), defaultSettings())

        val stored = EqualizerPreferences.read(prefs)
        assertEquals(listOf("Alpha", "Zeta"), stored.profiles.map { it.name })
        assertEquals(second.id, prefs.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID)?.removePrefix("profile:"))
        assertTrue(first.id != second.id)

        EqualizerPreferences.deleteProfile(prefs, second.id)
        assertEquals(listOf("Zeta"), EqualizerPreferences.read(prefs).profiles.map { it.name })
        assertEquals(EqualizerProfileIds.MANUAL, prefs.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID))
    }

    @Test
    fun `import accepts both wrapped and bare payloads and normalizes ids`() {
        val fixedId = UUID.randomUUID().toString()
        val bare = EqualizerJson.json.encodeToString(listOf(EqProfile(id = fixedId, name = "  Saved  ")))

        val prefs = InMemoryPreferences()
        val count = EqualizerPreferences.importProfiles(prefs, bare)

        assertEquals(1, count)
        val profile = EqualizerPreferences.read(prefs).profiles.single()
        assertEquals(fixedId, profile.id)
        assertEquals("Saved", profile.name)
        assertEquals(EqualizerProfileIds.stored(fixedId), prefs.string(EqualizerPreferenceKeys.SELECTED_PROFILE_ID))

        val duplicateIdCount =
            EqualizerPreferences.importProfiles(
                prefs,
                """{"profiles":[{"id":"$fixedId","name":"Copy"}]}""",
            )
        assertEquals(1, duplicateIdCount)
        val ids = EqualizerPreferences.read(prefs).profiles.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `an empty import is rejected without touching preferences`() {
        val prefs = InMemoryPreferences(mapOf(EqualizerPreferenceKeys.ENABLED to true))

        assertFailsWith<IllegalArgumentException> { EqualizerPreferences.importProfiles(prefs, "[]") }

        assertTrue(prefs.snapshot()[EqualizerPreferenceKeys.ENABLED] as Boolean)
        assertFalse(prefs.snapshot().containsKey(EqualizerPreferenceKeys.CUSTOM_PROFILES_JSON))
    }

    private fun defaultSettings() =
        EqSettings(
            enabled = false,
            bandLevelsMb = emptyList(),
            outputGainEnabled = false,
            outputGainMb = 0,
            bassBoostEnabled = false,
            bassBoostStrength = 0,
            virtualizerEnabled = false,
            virtualizerStrength = 0,
            autoHeadroomEnabled = false,
        )
}

package moe.rukamori.archivetune.playback.desktop

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAudioEffectsTest {
    @Test
    fun `profile normalization clamps values and matches native band count`() {
        val normalized = DesktopEqualizerProfile(
            enabled = true,
            preampDb = 99f,
            bandGainsDb = listOf(Float.NaN, -99f, 3.5f, 99f),
        ).normalizedForBands(6)

        assertEquals(20f, normalized.preampDb)
        assertEquals(listOf(0f, -20f, 3.5f, 20f, 0f, 0f), normalized.bandGainsDb)
    }

    @Test
    fun `desired profile is applied when native backend attaches and reapplied after replacement`() = runBlocking {
        lateinit var backend: FakeEqualizerBackend
        val effects = VlcjAudioEffects { backend }
        val desired = DesktopEqualizerProfile(
            enabled = true,
            preampDb = 4f,
            bandGainsDb = listOf(1f, 2f),
        )

        effects.applyEqualizer(desired)
        backend = FakeEqualizerBackend()
        effects.attach(backend)
        assertEquals(listOf(desired), backend.applied)

        val updated = desired.copy(preampDb = -2f)
        effects.applyEqualizer(updated)
        assertEquals(listOf(desired, updated), backend.applied)

        effects.detach(backend)
        val replacement = FakeEqualizerBackend()
        backend = replacement
        effects.attach(replacement)
        assertEquals(listOf(updated), replacement.applied)
    }

    @Test
    fun `capabilities and preset come from ensured backend`() = runBlocking {
        val backend = FakeEqualizerBackend()
        val effects = VlcjAudioEffects { backend }

        assertEquals(backend.capabilities, effects.equalizerCapabilities())
        assertEquals(backend.preset("Rock"), effects.presetEqualizer("Rock"))
        assertNull(effects.presetEqualizer("Missing"))
    }

    private class FakeEqualizerBackend : EqualizerBackend {
        override val capabilities = DesktopEqualizerCapabilities(
            bandFrequenciesHz = listOf(60f, 170f),
            presets = listOf("Flat", "Rock"),
        )
        val applied = mutableListOf<DesktopEqualizerProfile>()

        override fun preset(name: String): DesktopEqualizerProfile? =
            if (name == "Rock") DesktopEqualizerProfile(true, 1f, listOf(3f, -2f)) else null

        override fun apply(profile: DesktopEqualizerProfile) {
            applied += profile
        }
    }
}

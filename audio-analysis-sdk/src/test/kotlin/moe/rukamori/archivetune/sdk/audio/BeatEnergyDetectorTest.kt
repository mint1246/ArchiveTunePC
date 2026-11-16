package moe.rukamori.archivetune.sdk.audio

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class BeatEnergyDetectorTest {
    @Test
    fun `transient above adaptive floor triggers a beat`() {
        val detector = BeatEnergyDetector(triggerRatio = 1.6f, minimumEnergy = 2f)
        assertFalse(detector.acceptEnergy(4f, 0L))
        assertFalse(detector.acceptEnergy(4.1f, 100L))
        assertTrue(detector.acceptEnergy(9f, 400L))
    }

    @Test
    fun `refractory window blocks double triggers`() {
        val detector = BeatEnergyDetector(triggerRatio = 1.5f, minimumEnergy = 1f, refractoryMs = 220L)
        assertFalse(detector.acceptEnergy(2f, 0L))
        assertTrue(detector.acceptEnergy(6f, 300L))
        assertFalse(detector.acceptEnergy(7f, 400L))
        assertTrue(detector.acceptEnergy(8f, 600L))
    }

    @Test
    fun `invalid energy never triggers`() {
        val detector = BeatEnergyDetector()
        assertFalse(detector.acceptEnergy(Float.NaN, 0L))
        assertFalse(detector.acceptEnergy(-1f, 300L))
    }
}

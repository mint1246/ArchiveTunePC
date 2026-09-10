/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBeatAnalysisTest {
    @Test
    fun `tracker recovers a stable 120 bpm pulse train`() {
        val sampleRate = AndroidBeatTracker.SAMPLE_RATE
        val durationSeconds = 12
        val samples = FloatArray(sampleRate * durationSeconds)
        val beatInterval = sampleRate / 2

        var beatStart = sampleRate / 2
        while (beatStart < samples.size) {
            val transientLength = (sampleRate * 0.075).toInt()
            for (offset in 0 until transientLength) {
                val index = beatStart + offset
                if (index >= samples.size) break
                val envelope = 1f - offset.toFloat() / transientLength
                val kick = sin(2.0 * PI * 92.0 * offset / sampleRate).toFloat()
                samples[index] += kick * envelope * envelope
            }
            beatStart += beatInterval
        }

        val analysis = AndroidBeatTracker.analyze(samples)

        assertNotNull(analysis)
        analysis!!
        assertTrue("expected enough beat timestamps, got ${analysis.beatsSeconds.size}", analysis.beatsSeconds.size >= 12)
        assertTrue("expected ~120 bpm, got ${analysis.bpm}", abs(analysis.bpm - 120f) <= 6f)

        val intervals = mutableListOf<Float>()
        for (index in 0 until analysis.beatsSeconds.lastIndex) {
            intervals += analysis.beatsSeconds[index + 1] - analysis.beatsSeconds[index]
        }
        intervals.sort()
        val medianInterval = intervals[intervals.size / 2]
        assertTrue("expected ~500ms grid, got ${medianInterval * 1_000f}ms", abs(medianInterval - 0.5f) <= 0.06f)
    }

    @Test
    fun `tracker rejects silence`() {
        val samples = FloatArray(AndroidBeatTracker.SAMPLE_RATE * 4)
        assertTrue(AndroidBeatTracker.analyze(samples) == null)
    }
}

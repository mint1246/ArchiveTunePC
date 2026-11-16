/*
 * ArchiveTune (2026)
 * Â© Rukamori â€” github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class BeatAnalysisTest {
    @Test
    fun `tracker recovers a stable 120 bpm pulse train`() {
        val sampleRate = BeatTracker.SAMPLE_RATE
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

        val analysis = BeatTracker.analyze(samples)

        assertNotNull(analysis)
        assertTrue(
            analysis.beatsSeconds.size >= 12,
            "expected enough beat timestamps, got ${analysis.beatsSeconds.size}",
        )
        assertTrue(abs(analysis.bpm - 120f) <= 6f, "expected ~120 bpm, got ${analysis.bpm}")

        val intervals = mutableListOf<Float>()
        for (index in 0 until analysis.beatsSeconds.lastIndex) {
            intervals += analysis.beatsSeconds[index + 1] - analysis.beatsSeconds[index]
        }
        intervals.sort()
        val medianInterval = intervals[intervals.size / 2]
        assertTrue(
            abs(medianInterval - 0.5f) <= 0.06f,
            "expected ~500ms grid, got ${medianInterval * 1_000f}ms",
        )
    }

    @Test
    fun `tracker rejects silence`() {
        val samples = FloatArray(BeatTracker.SAMPLE_RATE * 4)
        assertTrue(BeatTracker.analyze(samples) == null)
    }
}

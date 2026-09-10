/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBeatTimelineTest {
    @Test
    fun `matches desktop local interval phase attack and decay semantics`() {
        val beats =
            floatArrayOf(
                8.661043f,
                9.392471f,
                10.112290f,
                10.820498f,
                11.528708f,
                12.236916f,
            )

        val onBeat = sampleAndroidBeatTimeline(beats, beats[1].toDouble() * 1_000.0)
        val middle = sampleAndroidBeatTimeline(beats, 9_752.380)
        val beforeNext = sampleAndroidBeatTimeline(beats, 10_080.0)

        assertEquals(1, onBeat.beatIndex)
        assertTrue(onBeat.pulse > 0.99f)
        assertTrue(onBeat.phase < 0.001f)
        assertEquals(719.819f, onBeat.beatDurationMs, 0.05f)
        assertEquals(60_000f / 719.819f, onBeat.localBpm, 0.02f)
        assertTrue(middle.pulse < 0.05f)
        assertTrue(middle.phase in 0.49f..0.51f)
        assertTrue(beforeNext.pulse > 0.45f)
    }

    @Test
    fun `uses first interval for pre-roll and final interval after last beat`() {
        val beats = floatArrayOf(1.0f, 1.5f, 2.1f)

        val preroll = sampleAndroidBeatTimeline(beats, 970.0)
        val tail = sampleAndroidBeatTimeline(beats, 2_120.0)

        assertEquals(-1, preroll.beatIndex)
        assertEquals(500f, preroll.beatDurationMs, 0.001f)
        assertTrue(preroll.pulse > 0f)
        assertEquals(2, tail.beatIndex)
        assertEquals(600f, tail.beatDurationMs, 0.01f)
        assertEquals(1f, tail.phase)
        assertTrue(tail.pulse > 0.9f)
    }

    @Test
    fun `invalid grids stay neutral`() {
        assertEquals(
            AndroidBeatTimelineSample(-1, 0f, 0f, 0f, 0f),
            sampleAndroidBeatTimeline(floatArrayOf(1f), 1_000.0),
        )
        assertEquals(
            AndroidBeatTimelineSample(-1, 0f, 0f, 0f, 0f),
            sampleAndroidBeatTimeline(floatArrayOf(1f, 2f), -1.0),
        )
    }
}
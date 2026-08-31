package moe.rukamori.archivetune.desktop.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAudioAnalyzerTest {
    private val analyzer = DesktopAudioAnalyzer()

    @Test
    fun `degara binding finds 140 bpm pulse train`() {
        val audio = pulseTrain(bpm = 140.0, seconds = 30)
        val result = analyzer.analyzeMono44k(audio)

        assertTrue(abs(result.bpm - 140.0) < 0.5, "bpm=${result.bpm}")
        assertTrue(result.beatsSeconds.size >= 65, "beats=${result.beatsSeconds.size}")
        assertTrue((1 until result.beatsSeconds.size).all { index ->
            result.beatsSeconds[index] > result.beatsSeconds[index - 1]
        })
    }

    @Test
    fun `chunked input produces the same beat analysis`() {
        val audio = pulseTrain(bpm = 120.0, seconds = 20)
        val oneShot = analyzer.analyzeMono44k(audio)
        val chunked = analyzer.createBeatKeySession(audio.size).use { session ->
            var offset = 0
            while (offset < audio.size) {
                val count = minOf(4096, audio.size - offset)
                session.append(audio, offset, count)
                offset += count
            }
            session.analyze()
        }

        assertTrue(abs(oneShot.bpm - chunked.bpm) < 0.001)
        assertEquals(oneShot.beatsSeconds.size, chunked.beatsSeconds.size)
    }

    @Test
    fun `key binding recognizes a C major chord`() {
        val audio = cMajor(seconds = 30)
        val result = analyzer.analyzeMono44k(audio)

        assertEquals("C", result.key)
        assertEquals("major", result.mode)
        assertTrue(result.keyStrength > 0.5f, "strength=${result.keyStrength}")
    }

    @Test
    fun `loudness binding returns finite EBU values`() {
        val frames = SAMPLE_RATE * 8
        val stereo = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val value = (0.15 * sin(2.0 * PI * 440.0 * i / SAMPLE_RATE)).toFloat()
            stereo[i * 2] = value
            stereo[i * 2 + 1] = value
        }

        val result = analyzer.analyzeStereo44kInterleaved(stereo)
        assertTrue(result.integratedLufs.isFinite(), "LUFS=${result.integratedLufs}")
        assertTrue(result.loudnessRangeLu.isFinite(), "LRA=${result.loudnessRangeLu}")
    }

    private fun pulseTrain(bpm: Double, seconds: Int): FloatArray {
        val output = FloatArray(SAMPLE_RATE * seconds)
        val period = (SAMPLE_RATE / (bpm / 60.0)).toInt()
        var sample = (SAMPLE_RATE * 0.1).toInt()
        while (sample < output.size) {
            for (i in 0 until 64) {
                if (sample + i >= output.size) break
                output[sample + i] = (1.0 - i / 64.0).toFloat()
            }
            sample += period
        }
        return output
    }

    private fun cMajor(seconds: Int): FloatArray {
        val frequencies = doubleArrayOf(261.625565, 329.627557, 391.995436, 523.251131)
        return FloatArray(SAMPLE_RATE * seconds) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            frequencies.sumOf { frequency ->
                0.18 * sin(2.0 * PI * frequency * t) +
                    0.05 * sin(4.0 * PI * frequency * t)
            }.toFloat()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}

/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import java.nio.file.Files
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemucsDspAndWavTest {
    @Test
    fun contract_matchesDesktopRustWorker() {
        assertEquals(44_100, DemucsContract.SAMPLE_RATE)
        assertEquals(4_096, DemucsContract.NFFT)
        assertEquals(1_024, DemucsContract.HOP)
        assertEquals(2_048, DemucsContract.FREQ_BINS)
        assertEquals(44, DemucsContract.FRAMES)
        assertEquals(39_690, DemucsContract.STRIDE)
        assertEquals(11_025, DemucsContract.SHIFT_CONTEXT)
        assertEquals(6, DemucsContract.SOURCES)
    }

    @Test
    fun reflectionAndTriangularOverlap_matchTorchWorkerSemantics() {
        val input = floatArrayOf(10f, 20f, 30f, 40f)
        assertEquals(30f, reflectedSample(input, -2))
        assertEquals(20f, reflectedSample(input, -1))
        assertEquals(10f, reflectedSample(input, 0))
        assertEquals(30f, reflectedSample(input, 4))
        assertEquals(20f, reflectedSample(input, 5))

        val weight = triangularDemucsWeight()
        assertEquals(1f / 22_050f, weight.first(), 1e-8f)
        assertEquals(1f, weight[22_049], 1e-7f)
        assertEquals(1f, weight[22_050], 1e-7f)
        assertEquals(1f / 22_050f, weight.last(), 1e-8f)
    }

    @Test
    fun stftAndReconstruct_roundTripsAllSixSources() {
        val chunk = Array(2) { FloatArray(DemucsContract.SEGMENT) }
        for (i in 0 until DemucsContract.SEGMENT) {
            chunk[0][i] = kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / DemucsContract.SAMPLE_RATE).toFloat() * 0.25f
        }
        val dsp = DemucsDsp()
        val magnitude = dsp.stftMagnitude(chunk)
        val planeSize = DemucsContract.FREQ_BINS * DemucsContract.FRAMES
        assertTrue(magnitude.copyOfRange(0, planeSize * 2).any { abs(it) > 1e-8f })
        assertTrue(magnitude.copyOfRange(planeSize * 2, planeSize * 4).all { abs(it) <= 1e-8f })

        val freq = FloatArray(DemucsContract.FREQ_OUTPUT_SIZE)
        val time = FloatArray(DemucsContract.TIME_OUTPUT_SIZE)
        for (source in 0 until DemucsContract.SOURCES) {
            magnitude.copyInto(freq, source * 4 * planeSize)
            for (channel in 0 until DemucsContract.CHANNELS) {
                time[(source * 2 + channel) * DemucsContract.SEGMENT + 123] = (source + 1) * 0.01f
            }
        }
        val reconstructed = dsp.reconstruct(freq, time)
        assertEquals(DemucsContract.SOURCES * DemucsContract.CHANNELS, reconstructed.size)
        for (source in 0 until DemucsContract.SOURCES) {
            assertTrue(reconstructed[source * 2].any { abs(it) > 1e-3f })
            assertEquals((source + 1) * 0.01f, reconstructed[source * 2 + 1][123], 1e-6f)
        }
    }

    @Test
    fun canonicalWavAndRollingWriter_writeSixValidStemWavs() {
        val root = Files.createTempDirectory("archivetune-sdk-wav").toFile()
        try {
            val inputWav = root.resolve("input.wav")
            val frameCount = 2_000
            Pcm16WavOutput(inputWav, DemucsContract.SAMPLE_RATE, 2).use { out ->
                repeat(frameCount) { i ->
                    val sample = ((i % 100) / 100f - 0.5f) * 0.5f
                    out.writeFloat(sample, -sample)
                }
            }
            CanonicalPcm16WavReader(inputWav).use { reader ->
                assertEquals(frameCount, reader.frames)
                val (mean, std) = reader.normalization()
                assertTrue(std > 0f)
                val dest = Array(2) { FloatArray(frameCount) }
                reader.fillNormalized(0, frameCount, mean, std, dest, 0)
                assertTrue(dest[0].any { abs(it) > 1e-4f })
            }

            val outDir = root.resolve("out")
            val blend = triangularDemucsWeight()
            val stems = Array(DemucsContract.SOURCES * 2) { FloatArray(DemucsContract.SEGMENT) { 0.1f } }
            RollingStemPcm16WavWriter(outDir, originalLength = 1_000, outerMean = 0f, outerStd = 1f).use { writer ->
                writer.addChunk(
                    offset = DemucsContract.SHIFT_CONTEXT,
                    chunkLength = 1_000,
                    trimStart = 0,
                    stems = stems,
                    blend = blend,
                )
                writer.flushUntil(DemucsContract.SHIFT_CONTEXT + 1_000)
                writer.finish()
            }

            StemSource.workerOrder.forEach { source ->
                val wavFile = outDir.resolve("${source.cacheName}.wav")
                assertTrue("Missing ${wavFile.name}", wavFile.isFile)
                CanonicalPcm16WavReader(wavFile).use { reader ->
                    assertEquals(1_000, reader.frames)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

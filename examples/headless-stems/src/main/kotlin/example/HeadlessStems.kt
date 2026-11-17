package example

import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import moe.rukamori.archivetune.sdk.stems.DemucsContract
import moe.rukamori.archivetune.sdk.stems.DemucsDsp
import moe.rukamori.archivetune.sdk.stems.Pcm16WavOutput
import moe.rukamori.archivetune.sdk.stems.StemCache
import moe.rukamori.archivetune.sdk.stems.StemChannelControl
import moe.rukamori.archivetune.sdk.stems.StemControls
import moe.rukamori.archivetune.sdk.stems.StemEncoding
import moe.rukamori.archivetune.sdk.stems.StemMixStore
import moe.rukamori.archivetune.sdk.stems.StemSource

fun main() {
    val root = Files.createTempDirectory("headless-stems-demo").toFile()
    try {
        // 1. Exercise 6-stem StemControls (including guitar & piano solo/mute)
        val guitarSoloControls =
            StemControls()
                .withControl(StemSource.GUITAR, StemChannelControl(gain = 0.8f, solo = true))
                .withControl(StemSource.PIANO, StemChannelControl(gain = 0.6f, muted = true))
        val guitarSoloGains = guitarSoloControls.effectiveGains()
        check(guitarSoloGains == listOf(0f, 0f, 0f, 0f, 0.8f, 0f)) {
            "Unexpected guitar-solo gains: $guitarSoloGains"
        }

        val pianoSoloControls =
            StemControls()
                .withControl(StemSource.GUITAR, StemChannelControl(gain = 0.5f, muted = true, solo = true))
                .withControl(StemSource.PIANO, StemChannelControl(gain = 0.9f, solo = true))
        val pianoSoloGains = pianoSoloControls.effectiveGains()
        check(pianoSoloGains == listOf(0f, 0f, 0f, 0f, 0f, 0.9f)) {
            "Unexpected piano-solo gains: $pianoSoloGains"
        }

        // 2. Exercise StemMixStore persistence
        val mixStore = StemMixStore(root.resolve("mixes.properties"))
        mixStore.save("headless-track-1", pianoSoloControls)
        val loadedControls = mixStore.load("headless-track-1")
        check(loadedControls == pianoSoloControls) { "StemMixStore round-trip mismatch" }

        // 3. Exercise StemCache WAV install
        val generatedDir = root.resolve("generated").apply { mkdirs() }
        StemSource.workerOrder.forEach { source ->
            Pcm16WavOutput(generatedDir.resolve("${source.cacheName}.wav"), DemucsContract.SAMPLE_RATE, 2).use { wav ->
                repeat(1_024) { i ->
                    val s = sin(2.0 * PI * 440.0 * i / DemucsContract.SAMPLE_RATE).toFloat() * 0.25f
                    wav.writeFloat(s, s)
                }
            }
        }
        val cache = StemCache(root.resolve("cache"))
        val installed = cache.installGeneratedSet("headless-track-1", 120_000L, "Headless Demo", generatedDir)
        check(installed.encoding == StemEncoding.PCM16_WAV) { "Expected PCM16_WAV encoding" }
        check(cache.find("headless-track-1", 120_000L) != null) { "Installed stem set not found" }

        // 4. Exercise DemucsDsp STFT + reconstruct
        val dsp = DemucsDsp()
        val chunk = Array(DemucsContract.CHANNELS) { FloatArray(DemucsContract.SEGMENT) }
        for (i in 0 until DemucsContract.SEGMENT) {
            chunk[0][i] = sin(2.0 * PI * 440.0 * i / DemucsContract.SAMPLE_RATE).toFloat() * 0.25f
        }
        val mag = dsp.stftMagnitude(chunk)
        val freq = FloatArray(DemucsContract.FREQ_OUTPUT_SIZE)
        val planeBytes = 4 * DemucsContract.FREQ_BINS * DemucsContract.FRAMES
        mag.copyInto(freq, destinationOffset = 4 * planeBytes) // guitar frequency plane
        val time = FloatArray(DemucsContract.TIME_OUTPUT_SIZE)
        time[5 * DemucsContract.CHANNELS * DemucsContract.SEGMENT + 100] = 0.42f // piano time sample
        val reconstructed = dsp.reconstruct(freq, time)
        check(reconstructed[4 * 2].any { abs(it) > 1e-3f }) { "Expected non-zero guitar reconstruction" }
        check(abs(reconstructed[5 * 2][100] - 0.42f) < 1e-5f) { "Expected piano time sample reconstruction" }

        println("HEADLESS_STEMS_PASS: 6-stem controls, mix store, WAV cache, and Demucs DSP verified.")
    } finally {
        root.deleteRecursively()
    }
}

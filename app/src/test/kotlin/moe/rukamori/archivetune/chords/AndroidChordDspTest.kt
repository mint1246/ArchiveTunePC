package moe.rukamori.archivetune.chords

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AndroidChordDspTest {
    @Test
    fun `chord labels match desktop worker`() {
        assertEquals("Cm", prettyAndroidChord("C:min"))
        assertEquals("F#m7b5", prettyAndroidChord("F#:hdim7"))
        assertEquals("Amaj7", prettyAndroidChord("A:maj7"))
        assertEquals("G", prettyAndroidChord("G"))
    }

    @Test
    fun `decode downmix is arithmetic stereo mean and resampling targets 22050`() {
        assertEquals(0.25f, androidChordStereoMean(0.75f, -0.25f), 0f)
        val output = mutableListOf<Float>()
        val resampler = AndroidChordLinearResampler(44_100, 22_050)
        listOf(0f, 1f, 2f, 3f, 4f).forEach { resampler.push(it, output::add) }
        assertEquals(listOf(0f, 2f, 4f), output)
    }

    @Test
    fun `smoothing never leaks across model sequence windows`() {
        val logits = FloatArray(2 * AndroidChordModelSpec.SEQUENCE_LENGTH * AndroidChordModelSpec.OUTPUT_CLASSES)
        val lastFirstWindow = 107 * AndroidChordModelSpec.OUTPUT_CLASSES
        logits[lastFirstWindow] = 10f
        val firstSecondWindow = 108 * AndroidChordModelSpec.OUTPUT_CLASSES
        logits[firstSecondWindow + 1] = 100f

        val predictions = smoothAndroidChordArgmax(logits, frameCount = 109, windowCount = 2)
        assertEquals(0, predictions[107])
        assertEquals(1, predictions[108])
    }

    @Test
    fun `events skip no-chord labels and confidence uses original softmax rows`() {
        val logits = FloatArray(AndroidChordModelSpec.SEQUENCE_LENGTH * AndroidChordModelSpec.OUTPUT_CLASSES) { -10f }
        for (frame in 0..1) logits[frame * AndroidChordModelSpec.OUTPUT_CLASSES] = 10f
        val vocabulary = MutableList(AndroidChordModelSpec.OUTPUT_CLASSES) { "N" }
        vocabulary[0] = "C:min"
        vocabulary[168] = "X"
        val events =
            androidChordPredictionsToEvents(
                predictions = intArrayOf(0, 0, 169),
                logits = logits,
                frameCount = 3,
                vocabulary = vocabulary,
                durationMs = 10_000L,
            )

        assertEquals(1, events.size)
        assertEquals("Cm", events.single().name)
        assertEquals(androidChordFrameToMs(2), events.single().endMs)
        assertTrue(events.single().confidence > 0.99f)
    }

    @Test
    fun `cqt binary parser accepts exact chordmini contract`() {
        val plan = AndroidChordCqtPlan.decode(fakeValidPlan()).validate()
        assertEquals(22_050.0, plan.sampleRate, 0.0)
        assertEquals(144, plan.nBins)
        assertEquals(6, plan.octaves.size)
        assertEquals(255, plan.tapCount)
    }

    @Test
    fun `config parser pins model tensor contract and vocabulary`() {
        val vocabulary = JSONArray().apply { repeat(170) { put(if (it == 169) "N" else "C") } }
        val json =
            JSONObject()
                .put("input", JSONObject().put("name", "features").put("shape", JSONArray(listOf("windows", 108, 144))))
                .put("output", JSONObject().put("name", "logits").put("shape", JSONArray(listOf("windows", 108, 170))))
                .put("sampleRate", 22_050)
                .put("hopLength", 2_048)
                .put("normalizationInGraph", true)
                .put("chordVocab", vocabulary)
        val config = AndroidChordConfig.parseAndValidate(json.toString())
        assertEquals(170, config.chordVocab.size)
        assertEquals("N", config.chordVocab.last())
    }

    @Test
    fun `cqt compute follows sparse plan rows`() {
        val plan = AndroidChordCqtPlan.decode(fakeValidPlan()).validate()
        val features = computeAndroidChordCqt(FloatArray(512), plan)
        val silence = ln(1e-6).toFloat()
        assertEquals(144, features.size)
        assertTrue(features.all { abs(it - silence) < 1e-5f })
    }

    private fun fakeValidPlan(): ByteArray {
        val octavesOffset = 128
        val rowOffsetsOffset = octavesOffset + 6 * 32
        val coefficientOffset = rowOffsetsOffset + 145 * 4
        val downsampleOffset = coefficientOffset
        val totalSize = downsampleOffset + 128 * 4
        val bytes = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0, AndroidChordContract.CQT_MAGIC)
        bytes.putInt(8, 128)
        bytes.putDouble(16, 22_050.0)
        bytes.putInt(24, 2_048)
        bytes.putInt(28, 144)
        bytes.putInt(36, 1)
        bytes.putInt(48, 6)
        bytes.putInt(52, 255)
        bytes.putInt(56, 0)
        bytes.putInt(60, totalSize - 128)
        bytes.putInt(72, octavesOffset)
        bytes.putInt(76, rowOffsetsOffset)
        bytes.putInt(80, coefficientOffset)
        bytes.putInt(84, coefficientOffset)
        bytes.putInt(92, downsampleOffset)
        repeat(6) { index ->
            val base = octavesOffset + index * 32
            bytes.putInt(base, index)
            bytes.putInt(base + 12, 64)
            bytes.putInt(base + 16, 512)
            bytes.putInt(base + 20, index * 24)
            bytes.putInt(base + 24, 24)
        }
        return bytes.array()
    }
}

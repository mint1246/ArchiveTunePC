/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.modelruntime.AndroidModelStore
import moe.rukamori.archivetune.modelruntime.AndroidOnnxDiagnostics
import moe.rukamori.archivetune.modelruntime.AndroidOnnxModelContract
import moe.rukamori.archivetune.modelruntime.AndroidOnnxRuntime
import moe.rukamori.archivetune.modelruntime.AndroidOnnxSession
import timber.log.Timber

enum class AndroidStemSeparationStage {
    MODEL,
    DECODING,
    SEPARATING,
    INSTALLING,
    READY,
}

data class AndroidStemSeparationProgress(
    val stage: AndroidStemSeparationStage,
    val fraction: Float,
    val message: String,
)

data class AndroidStemSeparationResult(
    val entry: AndroidStemCacheEntry,
    val diagnostics: AndroidOnnxDiagnostics,
    val elapsedMs: Long,
)

/**
 * Service-lifetime decode -> HTDemucs -> cache pipeline. The QNN/NNAPI/XNNPACK session is reused,
 * while separation itself is serialized so large provider allocations never overlap.
 */
internal class AndroidStemSeparationCenter(
    context: Context,
    private val cache: AndroidStemCache,
    private val modelStore: AndroidModelStore = AndroidModelStore(File(context.noBackupFilesDir, "models")),
    private val runtime: AndroidOnnxRuntime = AndroidOnnxRuntime(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val decoder = AndroidStemPcmDecoder(appContext)
    private val mutex = Mutex()
    private var session: AndroidOnnxSession? = null
    @Volatile private var closed = false

    suspend fun separate(
        trackId: String,
        durationMs: Long,
        title: String?,
        input: AndroidStemInput,
        onProgress: (AndroidStemSeparationProgress) -> Unit = {},
    ): AndroidStemSeparationResult =
        mutex.withLock {
            check(!closed) { "Stem separation center is closed" }
            withContext(Dispatchers.Default) {
                cache.find(trackId, durationMs)?.let { cached ->
                    return@withContext AndroidStemSeparationResult(
                        entry = cached,
                        diagnostics = AndroidOnnxRuntime.diagnostics.value,
                        elapsedMs = 0L,
                    )
                }

                val started = System.nanoTime()
                val workRoot = File(appContext.cacheDir, "stem-generation")
                val work = File(workRoot, UUID.randomUUID().toString())
                check(work.mkdirs()) { "Could not create stem generation directory" }
                val canonicalInput = File(work, ".input.wav")
                var writer: RollingStemPcm16WavWriter? = null
                try {
                    emit(onProgress, AndroidStemSeparationStage.MODEL, 0f, "Checking HTDemucs model")
                    val model =
                        modelStore.ensureStemModel { downloaded, total ->
                            val ratio = if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
                            emit(
                                onProgress,
                                AndroidStemSeparationStage.MODEL,
                                ratio.coerceIn(0f, 1f) * MODEL_SHARE,
                                "Downloading HTDemucs model",
                            )
                        }
                    currentCoroutineContext().ensureActive()
                    val activeSession = ensureSession(model)
                    emit(onProgress, AndroidStemSeparationStage.DECODING, MODEL_SHARE, "Decoding audio")

                    val decoded = decoder.decode(input, canonicalInput)
                    currentCoroutineContext().ensureActive()
                    val reader = CanonicalPcm16WavReader(decoded.file)
                    reader.use {
                        val (mean, std) = reader.normalization()
                        val originalLength = decoded.frames
                        val shiftedLength = Math.addExact(originalLength, AndroidDemucsContract.SHIFT_CONTEXT * 2)
                        val chunks =
                            ((shiftedLength + AndroidDemucsContract.STRIDE - 1) / AndroidDemucsContract.STRIDE)
                                .coerceAtLeast(1)
                        val dsp = AndroidDemucsDsp()
                        val blend = triangularDemucsWeight()
                        writer = RollingStemPcm16WavWriter(work, originalLength, mean, std)
                        var offset = 0
                        var chunkIndex = 0
                        while (offset < shiftedLength) {
                            currentCoroutineContext().ensureActive()
                            val chunkLength = minOf(AndroidDemucsContract.SEGMENT, shiftedLength - offset)
                            val padLeft = (AndroidDemucsContract.SEGMENT - chunkLength) / 2
                            val chunk = Array(AndroidDemucsContract.CHANNELS) { FloatArray(AndroidDemucsContract.SEGMENT) }
                            val originalStart = (offset - AndroidDemucsContract.SHIFT_CONTEXT).coerceAtLeast(0)
                            val shiftedEnd = offset + chunkLength
                            val originalEnd =
                                (shiftedEnd - AndroidDemucsContract.SHIFT_CONTEXT)
                                    .coerceAtLeast(0)
                                    .coerceAtMost(originalLength)
                            if (originalStart < originalEnd) {
                                val destinationStart =
                                    padLeft + originalStart + AndroidDemucsContract.SHIFT_CONTEXT - offset
                                reader.fillNormalized(
                                    startFrame = originalStart,
                                    endFrame = originalEnd,
                                    mean = mean,
                                    std = std,
                                    destination = chunk,
                                    destinationStart = destinationStart,
                                )
                            }

                            val mag = dsp.stftMagnitude(chunk)
                            val mix = interleavedDemucsMix(chunk)
                            val output = runModel(activeSession, mag, mix)
                            currentCoroutineContext().ensureActive()
                            val stems = dsp.reconstruct(output.freqStems, output.timeStems)
                            writer!!.addChunk(offset, chunkLength, padLeft, stems, blend)
                            writer!!.flushUntil(minOf(offset + AndroidDemucsContract.STRIDE, shiftedLength))
                            chunkIndex++
                            val separationRatio = chunkIndex.toFloat() / chunks.toFloat()
                            emit(
                                onProgress,
                                AndroidStemSeparationStage.SEPARATING,
                                DECODE_END + separationRatio * SEPARATION_SHARE,
                                "Separating stems ${chunkIndex}/$chunks",
                            )
                            offset += AndroidDemucsContract.STRIDE
                        }
                        writer!!.flushUntil(shiftedLength)
                        writer!!.finish()
                        writer = null
                    }

                    currentCoroutineContext().ensureActive()
                    emit(onProgress, AndroidStemSeparationStage.INSTALLING, 0.99f, "Installing stem cache")
                    val entry =
                        withContext(Dispatchers.IO) {
                            cache.installGeneratedSet(
                                trackId = trackId,
                                durationMs = durationMs,
                                title = title,
                                generatedDirectory = work,
                            )
                        }
                    val diagnostics = AndroidOnnxRuntime.diagnostics.value
                    val elapsed = elapsedMs(started)
                    emit(onProgress, AndroidStemSeparationStage.READY, 1f, "6-stem cache ready")
                    Timber.tag(LOG_TAG).i(
                        "HTDemucs complete track=%s frames=%d provider=%s elapsed=%dms runs=%d lastRun=%sms",
                        trackId,
                        decoded.frames,
                        diagnostics.selectedProvider,
                        elapsed,
                        diagnostics.runCount,
                        diagnostics.lastRunMs,
                    )
                    AndroidStemSeparationResult(entry, diagnostics, elapsed)
                } catch (cancelled: CancellationException) {
                    Timber.tag(LOG_TAG).i("HTDemucs separation cancelled track=%s", trackId)
                    throw cancelled
                } catch (error: Throwable) {
                    Timber.tag(LOG_TAG).e(error, "HTDemucs separation failed track=%s", trackId)
                    throw error
                } finally {
                    runCatching { writer?.close() }
                    // No manifest is ever installed from this directory until all six WAVs close.
                    work.deleteRecursively()
                }
            }
        }

    override fun close() {
        if (closed) return
        closed = true
        session?.close()
        session = null
    }

    suspend fun clearStemModelCache(storage: AndroidStemStorage): Long =
        mutex.withLock {
            check(!closed) { "Stem separation center is closed" }
            session?.close()
            session = null
            withContext(Dispatchers.IO) { storage.clearStemModel() }
        }

    suspend fun clearStemCache(storage: AndroidStemStorage): Long =
        mutex.withLock {
            check(!closed) { "Stem separation center is closed" }
            withContext(Dispatchers.IO) { storage.clearStems() }
        }

    private fun ensureSession(model: File): AndroidOnnxSession {
        session?.let { return it }
        return runtime
            .createAcceleratedSession(
                model = model,
                contract =
                    AndroidOnnxModelContract(
                        expectedInputs = setOf("mag", "mix"),
                        expectedOutputs = setOf("freq_stems", "time_stems"),
                    ),
            ).also { session = it }
    }

    private fun runModel(
        session: AndroidOnnxSession,
        mag: FloatArray,
        mix: FloatArray,
    ): ModelOutput {
        val environment = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(mag), AndroidDemucsContract.MAG_SHAPE).use { magTensor ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(mix), AndroidDemucsContract.MIX_SHAPE).use { mixTensor ->
                session.run(mapOf("mag" to magTensor, "mix" to mixTensor)).use { result ->
                    return ModelOutput(
                        freqStems = result.copyFloatTensor("freq_stems", AndroidDemucsContract.FREQ_OUTPUT_SIZE),
                        timeStems = result.copyFloatTensor("time_stems", AndroidDemucsContract.TIME_OUTPUT_SIZE),
                    )
                }
            }
        }
    }

    private data class ModelOutput(
        val freqStems: FloatArray,
        val timeStems: FloatArray,
    )

    private companion object {
        const val LOG_TAG = "OnnxRuntime"
        const val MODEL_SHARE = 0.05f
        const val DECODE_END = 0.10f
        const val SEPARATION_SHARE = 0.89f
    }
}

private fun ai.onnxruntime.OrtSession.Result.copyFloatTensor(
    name: String,
    expectedSize: Int,
): FloatArray {
    val value = get(name).orElseThrow { IllegalStateException("HTDemucs did not return $name") }
    val tensor = value as? OnnxTensor ?: error("HTDemucs output $name is not a tensor")
    val buffer = tensor.floatBuffer.duplicate().apply { rewind() }
    check(buffer.remaining() == expectedSize) {
        "Unexpected $name size ${buffer.remaining()}; expected $expectedSize"
    }
    return FloatArray(expectedSize).also(buffer::get)
}

private fun emit(
    sink: (AndroidStemSeparationProgress) -> Unit,
    stage: AndroidStemSeparationStage,
    fraction: Float,
    message: String,
) {
    sink(AndroidStemSeparationProgress(stage, fraction.coerceIn(0f, 1f), message))
}

private fun elapsedMs(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L

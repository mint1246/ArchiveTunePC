package moe.rukamori.archivetune.desktop.analysis

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

data class BeatKeyAnalysis(
    val bpm: Double,
    val beatsSeconds: FloatArray,
    val key: String,
    val mode: String,
    val keyStrength: Float,
)

data class LoudnessAnalysis(
    val integratedLufs: Float,
    val loudnessRangeLu: Float,
)

data class BeatKeyAnalysisOptions(
    val minTempo: Int = 40,
    val maxTempo: Int = 208,
    val keyProfile: String = "bgate",
) {
    init {
        require(minTempo in 40..180) { "minTempo must be in 40..180" }
        require(maxTempo in 60..250) { "maxTempo must be in 60..250" }
        require(minTempo < maxTempo) { "minTempo must be below maxTempo" }
        require(keyProfile.isNotBlank()) { "keyProfile must not be blank" }
    }
}

/**
 * In-process desktop music analysis backed by a minimal native Essentia build.
 *
 * Input contracts intentionally match the algorithms used by the native side:
 * beat/key analysis expects mono 44.1 kHz float PCM, while EBU R128 loudness
 * expects interleaved stereo 44.1 kHz float PCM.
 */
class DesktopAudioAnalyzer(
    libraryPath: Path? = null,
) {
    private val native = AudioAnalysisNative.load(libraryPath)

    fun createBeatKeySession(expectedSamples: Int = 0): BeatKeyAnalysisSession =
        BeatKeyAnalysisSession(native, expectedSamples)

    fun createLoudnessSession(expectedFrames: Int = 0): LoudnessAnalysisSession =
        LoudnessAnalysisSession(native, expectedFrames)

    fun analyzeMono44k(
        samples: FloatArray,
        options: BeatKeyAnalysisOptions = BeatKeyAnalysisOptions(),
    ): BeatKeyAnalysis =
        createBeatKeySession(samples.size).use { session ->
            session.append(samples)
            session.analyze(options)
        }

    fun analyzeStereo44kInterleaved(samples: FloatArray): LoudnessAnalysis {
        require(samples.size % 2 == 0) { "Stereo PCM must contain an even number of interleaved samples" }
        return createLoudnessSession(samples.size / 2).use { session ->
            session.appendInterleaved(samples)
            session.analyze()
        }
    }
}

class BeatKeyAnalysisSession internal constructor(
    private val native: AudioAnalysisNative,
    expectedSamples: Int,
) : Closeable {
    private var handle: Pointer? = native.monoCreate()
        ?: error("Native audio analyzer failed to allocate a mono analysis session")
    private var transferMemory: Memory? = null

    init {
        require(expectedSamples >= 0) { "expectedSamples must be non-negative" }
        if (expectedSamples > 0) checkStatus(native.monoReserve(requireHandle(), expectedSamples))
    }

    fun append(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) { "Invalid PCM range" }
        if (length == 0) return
        val memory = transferMemory(length.toLong() * Float.SIZE_BYTES)
        memory.write(0, samples, offset, length)
        append(memory, length)
    }

    /** Lets native decoders feed PCM without first constructing a Kotlin FloatArray. */
    fun append(pointer: Pointer, sampleCount: Int) {
        require(sampleCount >= 0) { "sampleCount must be non-negative" }
        if (sampleCount == 0) return
        checkStatus(native.monoAppend(requireHandle(), pointer, sampleCount))
    }

    fun analyze(options: BeatKeyAnalysisOptions = BeatKeyAnalysisOptions()): BeatKeyAnalysis {
        val handle = requireHandle()
        checkStatus(native.monoAnalyze(handle, options.minTempo, options.maxTempo, options.keyProfile))
        val count = native.monoBeatCount(handle)
        checkStatus(count)
        val beats = if (count == 0) {
            FloatArray(0)
        } else {
            val memory = Memory(count.toLong() * Float.SIZE_BYTES)
            val copied = native.monoCopyBeats(handle, memory, count)
            checkStatus(copied)
            memory.getFloatArray(0, copied)
        }
        return BeatKeyAnalysis(
            bpm = native.monoBpm(handle),
            beatsSeconds = beats,
            key = native.monoKey(handle),
            mode = native.monoMode(handle),
            keyStrength = native.monoKeyStrength(handle),
        )
    }

    override fun close() {
        handle?.let(native::monoDestroy)
        handle = null
        // Keep at most one small JNA transfer allocation alive per session. It becomes collectible
        // with the session instead of creating one native allocation for every streamed PCM chunk.
        transferMemory = null
    }

    private fun requireHandle(): Pointer = checkNotNull(handle) { "Beat/key analysis session is closed" }

    private fun transferMemory(requiredBytes: Long): Memory {
        val current = transferMemory
        if (current != null && current.size() >= requiredBytes) return current
        return Memory(requiredBytes).also { transferMemory = it }
    }

    private fun checkStatus(status: Int) {
        if (status >= 0) return
        val detail = handle?.let(native::monoLastError).orEmpty()
        throw AudioAnalysisException(status, detail.ifBlank { "Native beat/key analysis failed" })
    }
}

class LoudnessAnalysisSession internal constructor(
    private val native: AudioAnalysisNative,
    expectedFrames: Int,
) : Closeable {
    private var handle: Pointer? = native.loudnessCreate()
        ?: error("Native audio analyzer failed to allocate a loudness analysis session")
    private var transferMemory: Memory? = null

    init {
        require(expectedFrames >= 0) { "expectedFrames must be non-negative" }
        if (expectedFrames > 0) checkStatus(native.loudnessReserve(requireHandle(), expectedFrames))
    }

    fun appendInterleaved(samples: FloatArray, offsetFrames: Int = 0, frameCount: Int = samples.size / 2 - offsetFrames) {
        require(samples.size % 2 == 0) { "Stereo PCM must contain an even number of interleaved samples" }
        require(offsetFrames >= 0 && frameCount >= 0 && (offsetFrames + frameCount) * 2 <= samples.size) { "Invalid stereo PCM range" }
        if (frameCount == 0) return
        val memory = transferMemory(frameCount.toLong() * 2L * Float.SIZE_BYTES)
        memory.write(0, samples, offsetFrames * 2, frameCount * 2)
        appendInterleaved(memory, frameCount)
    }

    /** Lets a native decoder feed interleaved stereo PCM without a Kotlin-side copy. */
    fun appendInterleaved(pointer: Pointer, frameCount: Int) {
        require(frameCount >= 0) { "frameCount must be non-negative" }
        if (frameCount == 0) return
        checkStatus(native.loudnessAppend(requireHandle(), pointer, frameCount))
    }

    fun analyze(): LoudnessAnalysis {
        val handle = requireHandle()
        checkStatus(native.loudnessAnalyze(handle))
        return LoudnessAnalysis(
            integratedLufs = native.loudnessIntegratedLufs(handle),
            loudnessRangeLu = native.loudnessRangeLu(handle),
        )
    }

    override fun close() {
        handle?.let(native::loudnessDestroy)
        handle = null
        transferMemory = null
    }

    private fun requireHandle(): Pointer = checkNotNull(handle) { "Loudness analysis session is closed" }

    private fun transferMemory(requiredBytes: Long): Memory {
        val current = transferMemory
        if (current != null && current.size() >= requiredBytes) return current
        return Memory(requiredBytes).also { transferMemory = it }
    }

    private fun checkStatus(status: Int) {
        if (status >= 0) return
        val detail = handle?.let(native::loudnessLastError).orEmpty()
        throw AudioAnalysisException(status, detail.ifBlank { "Native loudness analysis failed" })
    }
}

class AudioAnalysisException(
    val nativeStatus: Int,
    message: String,
) : RuntimeException("$message (native status $nativeStatus)")

internal class AudioAnalysisNative private constructor(
    private val library: NativeLibrary,
) {
    private val abiVersionFn = library.getFunction("at_audio_analysis_abi_version")
    private val monoCreateFn = library.getFunction("at_mono_create")
    private val monoDestroyFn = library.getFunction("at_mono_destroy")
    private val monoReserveFn = library.getFunction("at_mono_reserve")
    private val monoAppendFn = library.getFunction("at_mono_append_f32")
    private val monoAnalyzeFn = library.getFunction("at_mono_analyze")
    private val monoBpmFn = library.getFunction("at_mono_bpm")
    private val monoKeyStrengthFn = library.getFunction("at_mono_key_strength")
    private val monoGetKeyFn = library.getFunction("at_mono_get_key")
    private val monoGetModeFn = library.getFunction("at_mono_get_mode")
    private val monoBeatCountFn = library.getFunction("at_mono_beat_count")
    private val monoCopyBeatsFn = library.getFunction("at_mono_copy_beats")
    private val monoLastErrorFn = library.getFunction("at_mono_last_error")
    private val loudnessCreateFn = library.getFunction("at_loudness_create")
    private val loudnessDestroyFn = library.getFunction("at_loudness_destroy")
    private val loudnessReserveFn = library.getFunction("at_loudness_reserve")
    private val loudnessAppendFn = library.getFunction("at_loudness_append_interleaved_f32")
    private val loudnessAnalyzeFn = library.getFunction("at_loudness_analyze")
    private val loudnessIntegratedFn = library.getFunction("at_loudness_integrated_lufs")
    private val loudnessRangeFn = library.getFunction("at_loudness_range_lu")
    private val loudnessLastErrorFn = library.getFunction("at_loudness_last_error")

    init {
        val abi = abiVersionFn.invokeInt(emptyArray())
        require(abi == EXPECTED_ABI) { "Unsupported ArchiveTune audio-analysis native ABI $abi (expected $EXPECTED_ABI)" }
    }

    fun monoCreate(): Pointer? = monoCreateFn.invokePointer(emptyArray())
    fun monoDestroy(handle: Pointer) = monoDestroyFn.invokeVoid(arrayOf(handle))
    fun monoReserve(handle: Pointer, sampleCount: Int): Int = monoReserveFn.invokeInt(arrayOf(handle, sampleCount))
    fun monoAppend(handle: Pointer, samples: Pointer, sampleCount: Int): Int = monoAppendFn.invokeInt(arrayOf(handle, samples, sampleCount))
    fun monoAnalyze(handle: Pointer, minTempo: Int, maxTempo: Int, keyProfile: String): Int =
        monoAnalyzeFn.invokeInt(arrayOf(handle, minTempo, maxTempo, keyProfile))
    fun monoBpm(handle: Pointer): Double = monoBpmFn.invokeDouble(arrayOf(handle))
    fun monoKeyStrength(handle: Pointer): Float = (monoKeyStrengthFn.invoke(Float::class.javaPrimitiveType, arrayOf(handle)) as Number).toFloat()
    fun monoBeatCount(handle: Pointer): Int = monoBeatCountFn.invokeInt(arrayOf(handle))
    fun monoCopyBeats(handle: Pointer, output: Pointer, capacity: Int): Int = monoCopyBeatsFn.invokeInt(arrayOf(handle, output, capacity))
    fun monoKey(handle: Pointer): String = readNativeString(monoGetKeyFn, handle)
    fun monoMode(handle: Pointer): String = readNativeString(monoGetModeFn, handle)
    fun monoLastError(handle: Pointer): String = readNativeString(monoLastErrorFn, handle)

    fun loudnessCreate(): Pointer? = loudnessCreateFn.invokePointer(emptyArray())
    fun loudnessDestroy(handle: Pointer) = loudnessDestroyFn.invokeVoid(arrayOf(handle))
    fun loudnessReserve(handle: Pointer, frameCount: Int): Int = loudnessReserveFn.invokeInt(arrayOf(handle, frameCount))
    fun loudnessAppend(handle: Pointer, samples: Pointer, frameCount: Int): Int = loudnessAppendFn.invokeInt(arrayOf(handle, samples, frameCount))
    fun loudnessAnalyze(handle: Pointer): Int = loudnessAnalyzeFn.invokeInt(arrayOf(handle))
    fun loudnessIntegratedLufs(handle: Pointer): Float =
        (loudnessIntegratedFn.invoke(Float::class.javaPrimitiveType, arrayOf(handle)) as Number).toFloat()
    fun loudnessRangeLu(handle: Pointer): Float =
        (loudnessRangeFn.invoke(Float::class.javaPrimitiveType, arrayOf(handle)) as Number).toFloat()
    fun loudnessLastError(handle: Pointer): String = readNativeString(loudnessLastErrorFn, handle)

    private fun readNativeString(function: com.sun.jna.Function, handle: Pointer): String {
        var capacity = 256
        repeat(3) {
            val memory = Memory(capacity.toLong())
            val status = function.invokeInt(arrayOf(handle, memory, capacity))
            if (status >= 0) return memory.getString(0, Charsets.UTF_8.name())
            if (status != STATUS_BUFFER_TOO_SMALL) return ""
            capacity *= 4
        }
        return ""
    }

    companion object {
        private const val EXPECTED_ABI = 1
        private const val STATUS_BUFFER_TOO_SMALL = -5
        private const val LIBRARY_FILE = "archivetune-audio-analysis.dll"

        fun load(explicitPath: Path?): AudioAnalysisNative {
            require(Platform.isWindows()) { "Desktop audio analysis currently supports Windows x64 only" }

            val candidates = buildList {
                explicitPath?.let(::add)
                System.getProperty("archivetune.audioAnalysis.library")
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(Path.of(it)) }
                System.getenv("ARCHIVETUNE_AUDIO_ANALYSIS_LIBRARY")
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(Path.of(it)) }
                System.getProperty("archivetune.app.dir")
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(Path.of(it, "native", "windows-x64", "audio-analysis", LIBRARY_FILE)) }
                if (System.getProperty("archivetune.app.dir").isNullOrBlank()) {
                    val codeSource = runCatching {
                        Path.of(AudioAnalysisNative::class.java.protectionDomain.codeSource.location.toURI())
                    }.getOrNull()
                    codeSource?.let { jarOrClasses ->
                        val base = if (Files.isRegularFile(jarOrClasses)) jarOrClasses.parent else jarOrClasses
                        add(base.resolve("resources/native/windows-x64/audio-analysis/$LIBRARY_FILE"))
                    }
                }
                add(Path.of("desktop-audio-analysis", "build", "native", "windows-x64", LIBRARY_FILE))
                add(Path.of("build", "native", "windows-x64", LIBRARY_FILE))
            }

            val existing = candidates.firstOrNull(Path::exists)
            if (existing != null) return AudioAnalysisNative(NativeLibrary.getInstance(existing.toAbsolutePath().normalize().absolutePathString()))

            return try {
                AudioAnalysisNative(NativeLibrary.getInstance("archivetune-audio-analysis"))
            } catch (error: UnsatisfiedLinkError) {
                val searched = candidates.joinToString(separator = File.pathSeparator) { it.toAbsolutePath().normalize().toString() }
                throw UnsatisfiedLinkError("Could not locate $LIBRARY_FILE. Searched: $searched").apply { initCause(error) }
            }
        }
    }
}

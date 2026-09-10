/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modelruntime

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import java.io.File
import java.util.EnumSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

enum class AndroidOnnxProvider {
    QNN_HTP,
    NNAPI,
    XNNPACK,
}

data class AndroidOnnxProviderAttempt(
    val provider: AndroidOnnxProvider,
    val available: Boolean,
    val selected: Boolean,
    val message: String,
    val durationMs: Long = 0L,
)

data class AndroidOnnxDiagnostics(
    val ortVersion: String?,
    val abi: String,
    val availableProviders: Set<String>,
    val attempts: List<AndroidOnnxProviderAttempt>,
    val selectedProvider: AndroidOnnxProvider?,
    val sessionCreateMs: Long? = null,
    val lastRunMs: Long? = null,
    val runCount: Long = 0L,
    val totalRunMs: Long = 0L,
) {
    fun summary(): String = buildString {
        append("ORT=").append(ortVersion ?: "unavailable")
        append(" ABI=").append(abi)
        append(" selected=").append(selectedProvider ?: "none")
        attempts.forEach { attempt ->
            append("; ").append(attempt.provider).append('=').append(attempt.message)
        }
    }
}

/** Optional graph contract check kept outside provider selection so this runtime is reusable. */
data class AndroidOnnxModelContract(
    val expectedInputs: Set<String> = emptySet(),
    val expectedOutputs: Set<String> = emptySet(),
)

class AndroidOnnxNoAcceleratorException(
    val diagnostics: AndroidOnnxDiagnostics,
) : IllegalStateException("No permitted Android ONNX execution provider could load the model. ${diagnostics.summary()}")

class AndroidOnnxSession internal constructor(
    val session: OrtSession,
    val diagnostics: AndroidOnnxDiagnostics,
) : AutoCloseable {
    fun run(inputs: Map<String, out OnnxTensorLike>): OrtSession.Result {
        val started = System.nanoTime()
        return try {
            session.run(inputs)
        } finally {
            AndroidOnnxRuntime.recordRun(diagnostics.selectedProvider, elapsedMs(started))
        }
    }

    override fun close() = session.close()
}

/**
 * Accelerated-only ORT session factory. CPU EP fallback is disabled at the ORT session level for
 * every provider, in addition to NNAPI's own CPU_DISABLED flag. This is deliberate: if QNN/NNAPI/
 * XNNPACK cannot own the complete model we report that truthfully instead of silently running the
 * expensive HTDemucs graph through generic ORT CPU kernels.
 */
class AndroidOnnxRuntime {
    fun createAcceleratedSession(
        model: File,
        contract: AndroidOnnxModelContract? = null,
    ): AndroidOnnxSession {
        require(model.isFile) { "ONNX model does not exist: ${model.absolutePath}" }
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val environment = OrtEnvironment.getEnvironment()
        val available = OrtEnvironment.getAvailableProviders()
        val attempts = mutableListOf<AndroidOnnxProviderAttempt>()
        val totalStarted = System.nanoTime()
        for (provider in providerOrder()) {
            val ortProvider = provider.ortProvider()
            val platformReason = provider.platformRejectionReason(abi, Build.VERSION.SDK_INT)
            val providerAvailable = ortProvider in available && platformReason == null
            if (!providerAvailable) {
                attempts +=
                    AndroidOnnxProviderAttempt(
                        provider = provider,
                        available = false,
                        selected = false,
                        message = if (ortProvider !in available) "not present in packaged ORT" else checkNotNull(platformReason),
                    )
                publishDiagnostics(environment.version, abi, available, attempts, null, totalStarted)
                continue
            }
            val options = OrtSession.SessionOptions()
            val attemptStarted = System.nanoTime()
            try {
                configureCommonOptions(options)
                configureProvider(options, provider)
                val session = environment.createSession(model.absolutePath, options)
                val missingInputs = contract?.expectedInputs.orEmpty() - session.inputNames
                val missingOutputs = contract?.expectedOutputs.orEmpty() - session.outputNames
                if (missingInputs.isNotEmpty() || missingOutputs.isNotEmpty()) {
                    session.close()
                    error("Unexpected ONNX graph contract; missing inputs=$missingInputs outputs=$missingOutputs")
                }
                val durationMs = elapsedMs(attemptStarted)
                attempts += AndroidOnnxProviderAttempt(provider, true, true, "loaded", durationMs)
                val diagnostics =
                    AndroidOnnxDiagnostics(
                        ortVersion = environment.version,
                        abi = abi.ifBlank { "unknown" },
                        availableProviders = available.mapTo(linkedSetOf()) { it.name },
                        attempts = attempts.toList(),
                        selectedProvider = provider,
                        sessionCreateMs = elapsedMs(totalStarted),
                    )
                _diagnostics.value = diagnostics
                Timber.tag(LOG_TAG).i("provider selected in %d ms: %s", diagnostics.sessionCreateMs, diagnostics.summary())
                return AndroidOnnxSession(session, diagnostics)
            } catch (error: Throwable) {
                attempts +=
                    AndroidOnnxProviderAttempt(
                        provider = provider,
                        available = true,
                        selected = false,
                        message = error.rootMessage(),
                        durationMs = elapsedMs(attemptStarted),
                    )
                publishDiagnostics(environment.version, abi, available, attempts, null, totalStarted)
                Timber.tag(LOG_TAG).w(error, "provider %s rejected model after %d ms", provider, attempts.last().durationMs)
            } finally {
                options.close()
            }
        }
        val diagnostics = publishDiagnostics(environment.version, abi, available, attempts, null, totalStarted)
        throw AndroidOnnxNoAcceleratorException(diagnostics)
    }

    private fun publishDiagnostics(
        version: String?,
        abi: String,
        available: Set<OrtProvider>,
        attempts: List<AndroidOnnxProviderAttempt>,
        selected: AndroidOnnxProvider?,
        startedNanos: Long,
    ): AndroidOnnxDiagnostics =
        AndroidOnnxDiagnostics(
            ortVersion = version,
            abi = abi.ifBlank { "unknown" },
            availableProviders = available.mapTo(linkedSetOf()) { it.name },
            attempts = attempts.toList(),
            selectedProvider = selected,
            sessionCreateMs = elapsedMs(startedNanos),
        ).also { _diagnostics.value = it }

    private fun configureCommonOptions(options: OrtSession.SessionOptions) {
        options.setMemoryPatternOptimization(false)
        options.setCPUArenaAllocator(false)
        options.setInterOpNumThreads(1)
        options.setIntraOpNumThreads(1)
        options.addConfigEntry(DISABLE_CPU_FALLBACK_KEY, "1")
        options.addConfigEntry(INTRA_OP_SPINNING_KEY, "0")
    }

    private fun configureProvider(
        options: OrtSession.SessionOptions,
        provider: AndroidOnnxProvider,
    ) {
        when (provider) {
            AndroidOnnxProvider.QNN_HTP ->
                options.addQnn(
                    linkedMapOf(
                        "backend_path" to "libQnnHtp.so",
                        "htp_performance_mode" to "burst",
                        "enable_htp_fp16_precision" to "1",
                        // Keep graph I/O quantization away from CPU EP as well.
                        "offload_graph_io_quantization" to "0",
                    ),
                )

            AndroidOnnxProvider.NNAPI -> options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            AndroidOnnxProvider.XNNPACK ->
                options.addXnnpack(
                    mapOf(
                        "intra_op_num_threads" to Runtime.getRuntime().availableProcessors().coerceAtLeast(1).toString(),
                    ),
                )
        }
    }

    companion object {
        private const val LOG_TAG = "OnnxRuntime"
        const val DISABLE_CPU_FALLBACK_KEY = "session.disable_cpu_ep_fallback"
        const val INTRA_OP_SPINNING_KEY = "session.intra_op.allow_spinning"

        private val _diagnostics =
            MutableStateFlow(
                AndroidOnnxDiagnostics(
                    ortVersion = null,
                    abi = "uninitialized",
                    availableProviders = emptySet(),
                    attempts = emptyList(),
                    selectedProvider = null,
                ),
            )
        val diagnostics: StateFlow<AndroidOnnxDiagnostics> = _diagnostics.asStateFlow()

        internal fun recordRun(
            provider: AndroidOnnxProvider?,
            durationMs: Long,
        ) {
            _diagnostics.update { current ->
                if (provider == null || current.selectedProvider != provider) current else {
                    current.copy(
                        lastRunMs = durationMs,
                        runCount = current.runCount + 1L,
                        totalRunMs = current.totalRunMs + durationMs,
                    )
                }
            }
            Timber.tag(LOG_TAG).d("inference provider=%s duration=%d ms", provider, durationMs)
        }

        fun providerOrder(): List<AndroidOnnxProvider> =
            listOf(AndroidOnnxProvider.QNN_HTP, AndroidOnnxProvider.NNAPI, AndroidOnnxProvider.XNNPACK)

        /** Pure policy helper used by diagnostics/tests without loading ORT native libraries. */
        fun permittedProviderOrder(
            abi: String,
            sdkInt: Int,
            availableProviderNames: Set<String>,
        ): List<AndroidOnnxProvider> =
            providerOrder().filter { provider ->
                provider.ortProvider().name in availableProviderNames && provider.platformRejectionReason(abi, sdkInt) == null
            }
    }
}

private fun AndroidOnnxProvider.ortProvider(): OrtProvider =
    when (this) {
        AndroidOnnxProvider.QNN_HTP -> OrtProvider.QNN
        AndroidOnnxProvider.NNAPI -> OrtProvider.NNAPI
        AndroidOnnxProvider.XNNPACK -> OrtProvider.XNNPACK
    }

private fun AndroidOnnxProvider.platformRejectionReason(
    abi: String,
    sdkInt: Int,
): String? =
    when {
        this == AndroidOnnxProvider.QNN_HTP && abi != "arm64-v8a" -> "QNN HTP requires arm64-v8a"
        this == AndroidOnnxProvider.NNAPI && sdkInt < 29 -> "NNAPI CPU_DISABLED requires API 29+"
        else -> null
    }

private fun elapsedMs(startedNanos: Long): Long =
    ((System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L)

private fun Throwable.rootMessage(): String {
    var current = this
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current.message?.lineSequence()?.firstOrNull()?.take(280) ?: current.javaClass.simpleName
}

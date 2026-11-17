/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StemQueueState(
    val pending: Int = 0,
    val activeTrackId: String? = null,
    val activeTitle: String? = null,
    val activeProgress: Float? = null,
    val activeMessage: String? = null,
    val completed: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
) {
    val active: Boolean get() = pending > 0 || activeTrackId != null
}

data class StemBatchProgress(
    val fraction: Float,
    val message: String,
)

/** Single-consumer album/playlist queue; the actual separator remains the one shared runtime. */
class StemBatchQueue<R>(
    private val scope: CoroutineScope,
    private val trackIdOf: (R) -> String,
    private val titleOf: (R) -> String?,
    private val isReady: (R) -> Boolean,
    private val generate: suspend (R, (StemBatchProgress) -> Unit) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val pending = ArrayDeque<R>()
    private val _state = MutableStateFlow(StemQueueState())
    val state = _state.asStateFlow()

    private var workerJob: Job? = null
    private var activeRequest: R? = null
    private var closed = false

    fun enqueue(requests: List<R>): Int = synchronized(lock) {
        if (closed) return@synchronized 0
        val knownIds = buildSet {
            activeRequest?.let { add(trackIdOf(it)) }
            pending.forEach { add(trackIdOf(it)) }
        }
        val accepted =
            requests
                .asSequence()
                .distinctBy(trackIdOf)
                .filterNot { trackIdOf(it) in knownIds }
                .toList()
        if (accepted.isEmpty()) return@synchronized 0

        if (workerJob?.isActive != true && activeRequest == null && pending.isEmpty()) {
            _state.value = StemQueueState()
        }
        accepted.forEach(pending::addLast)
        publishLocked()
        ensureWorkerLocked()
        accepted.size
    }

    fun cancel() = synchronized(lock) {
        pending.clear()
        workerJob?.cancel()
        workerJob = null
        activeRequest = null
        _state.value = _state.value.copy(
            pending = 0,
            activeTrackId = null,
            activeTitle = null,
            activeProgress = null,
            activeMessage = null,
        )
    }

    fun runningJob(): Job? = synchronized(lock) { workerJob }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        cancel()
    }

    private fun ensureWorkerLocked() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch { runQueue() }
    }

    private suspend fun runQueue() {
        try {
            while (true) {
                val request = synchronized(lock) {
                    val next = pending.pollFirst() ?: run {
                        workerJob = null
                        publishLocked()
                        return
                    }
                    activeRequest = next
                    publishLocked()
                    next
                }
                val requestId = trackIdOf(request)

                var failure: Throwable? = null
                var generatedNow = false
                try {
                    if (!isReady(request)) {
                        generate(request) { progress ->
                            synchronized(lock) {
                                if (activeRequest?.let(trackIdOf) == requestId) {
                                    _state.value = _state.value.copy(
                                        activeProgress = progress.fraction.coerceIn(0f, 1f),
                                        activeMessage = progress.message,
                                    )
                                }
                            }
                        }
                        generatedNow = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failure = error
                }

                synchronized(lock) {
                    if (activeRequest?.let(trackIdOf) == requestId) {
                        activeRequest = null
                        val previous = _state.value
                        _state.value = previous.copy(
                            pending = pending.size,
                            activeTrackId = null,
                            activeTitle = null,
                            activeProgress = null,
                            activeMessage = null,
                            completed = previous.completed + if (failure == null && generatedNow) 1 else 0,
                            failed = previous.failed + if (failure != null) 1 else 0,
                            lastError = failure?.message?.takeIf(String::isNotBlank) ?: previous.lastError,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                activeRequest = null
                if (workerJob?.isCancelled == true) workerJob = null
                publishLocked()
            }
            throw cancelled
        }
    }

    private fun publishLocked() {
        val previous = _state.value
        val active = activeRequest
        _state.value = previous.copy(
            pending = pending.size,
            activeTrackId = active?.let(trackIdOf),
            activeTitle = active?.let(titleOf),
            activeProgress = if (active == null) null else previous.activeProgress,
            activeMessage = if (active == null) null else previous.activeMessage,
        )
    }
}

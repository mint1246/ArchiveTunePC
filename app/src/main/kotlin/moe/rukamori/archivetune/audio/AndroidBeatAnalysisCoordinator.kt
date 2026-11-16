/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audio

import android.content.Context
import androidx.media3.common.MediaItem
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.sdk.audio.BeatAnalysis


/** Owns cancellation/generation fencing for current-track analysis and one-track-ahead prefetch. */
internal class AndroidBeatAnalysisCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    resolveInput: suspend (MediaItem) -> AndroidBeatAnalysisInput?,
) : AutoCloseable {
    private val repository = AndroidBeatAnalysisRepository(context, resolveInput)
    private val generation = AtomicLong(0L)
    private val nextGeneration = AtomicLong(0L)
    private var currentIdentity: String? = null
    private var nextIdentity: String? = null
    private var currentJob: Job? = null
    private var nextJob: Job? = null
    private val _currentAnalysis = MutableStateFlow<AndroidBeatGrid?>(null)
    val currentAnalysis = _currentAnalysis.asStateFlow()

    fun update(current: AndroidBeatTrack?, next: AndroidBeatTrack?) {
        if (current?.identity != currentIdentity) {
            currentIdentity = current?.identity
            val token = generation.incrementAndGet()
            currentJob?.cancel()
            currentJob = null
            _currentAnalysis.value = null
            if (current != null) {
                currentJob =
                    scope.launch {
                        val analysis = repository.analyze(current)
                        if (generation.get() == token && currentIdentity == current.identity && analysis != null) {
                            _currentAnalysis.value = AndroidBeatGrid(current.identity, analysis)
                        }
                    }
            }
        }

        val effectiveNext = next?.takeUnless { it.identity == current?.identity }
        if (effectiveNext?.identity != nextIdentity) {
            nextIdentity = effectiveNext?.identity
            val token = nextGeneration.incrementAndGet()
            nextJob?.cancel()
            nextJob = null
            if (effectiveNext != null) {
                nextJob =
                    scope.launch {
                        repository.analyze(effectiveNext)
                        // The result intentionally stays in the repository cache. The token check is
                        // still useful: it prevents stale work from being treated as the active
                        // prefetch if this queue position changed while decoding.
                        if (nextGeneration.get() != token || nextIdentity != effectiveNext.identity) return@launch
                    }
            }
        }
    }

    override fun close() {
        generation.incrementAndGet()
        nextGeneration.incrementAndGet()
        currentJob?.cancel()
        nextJob?.cancel()
        currentJob = null
        nextJob = null
        currentIdentity = null
        nextIdentity = null
        _currentAnalysis.value = null
    }
}

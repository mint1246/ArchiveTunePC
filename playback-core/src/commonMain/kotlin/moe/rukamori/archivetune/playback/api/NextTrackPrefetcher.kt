package moe.rukamori.archivetune.playback.api

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.internal.PlaybackAtomicBoolean
import moe.rukamori.archivetune.playback.internal.PlaybackAtomicLong

/**
 * Resolves exactly one deferred queue occurrence ahead as soon as playback has a next item. It never
 * prepares a second VLC audio pipeline; the normal playback/crossfade resolver consumes the same
 * signed-source cache populated by [resolve]. Call [onSnapshot] and [close] from a serialized owner.
 */
class NextTrackPrefetcher(
    private val scope: CoroutineScope,
    private val resolve: suspend (PlaybackItem, cacheAllowed: () -> Boolean) -> Result<ResolvedPlaybackSource>,
) : AutoCloseable {
    private data class Candidate(
        val currentOccurrenceId: Long,
        val nextOccurrenceId: Long,
        val item: PlaybackItem,
    )

    private val closed = PlaybackAtomicBoolean(false)
    private val generation = PlaybackAtomicLong(0L)
    private var queueEntriesIdentity: List<PlaybackQueueEntry>? = null
    @Volatile private var activeCandidate: Candidate? = null
    private var attempted = false
    private var job: Job? = null

    /** Called from the playback-state collector; position-only ticks are intentionally cheap. */
    fun onSnapshot(snapshot: PlaybackSnapshot) {
        if (closed.get()) return
        val candidate = snapshot.nextDeferredCandidate()
        val queueChanged = queueEntriesIdentity !== snapshot.queueEntries
        val targetChanged = !sameOccurrence(activeCandidate, candidate)
        if (queueChanged || targetChanged) {
            invalidateAttempt()
            queueEntriesIdentity = snapshot.queueEntries
            activeCandidate = candidate
        }

        if (candidate == null || !snapshot.isPlaying) return
        if (attempted) return

        attempted = true
        val attemptGeneration = generation.incrementAndGet()
        val attemptCandidate = candidate
        job = scope.launch {
            try {
                resolve(attemptCandidate.item) {
                    !closed.get() &&
                        generation.get() == attemptGeneration &&
                        sameOccurrence(activeCandidate, attemptCandidate)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Prefetch is an optimization only; playback performs its normal resolve on transition.
            }
        }
    }

    private fun invalidateAttempt(keepCandidate: Boolean = false) {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        attempted = false
        if (!keepCandidate) activeCandidate = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invalidateAttempt()
        queueEntriesIdentity = null
    }

    private fun sameOccurrence(first: Candidate?, second: Candidate?): Boolean =
        when {
            first == null || second == null -> first === second
            else ->
                first.currentOccurrenceId == second.currentOccurrenceId &&
                    first.nextOccurrenceId == second.nextOccurrenceId
        }

    private fun PlaybackSnapshot.nextDeferredCandidate(): Candidate? {
        if (queueEntries.isEmpty()) return null
        val currentVisualIndex = queueEntries.indexOfFirst(PlaybackQueueEntry::isCurrent)
        if (currentVisualIndex < 0) return null
        val current = queueEntries[currentVisualIndex]
        val next = when {
            currentVisualIndex < queueEntries.lastIndex -> queueEntries[currentVisualIndex + 1]
            repeatMode == RepeatMode.ALL -> queueEntries.firstOrNull()
            else -> null
        } ?: return null
        if (next.id == current.id || next.item.source !is PlaybackSource.Deferred) return null
        return Candidate(current.id, next.id, next.item)
    }

}

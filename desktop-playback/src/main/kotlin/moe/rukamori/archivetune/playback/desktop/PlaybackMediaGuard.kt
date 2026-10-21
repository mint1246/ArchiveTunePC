/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori/archivetune
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.playback.desktop

/**
 * Identity of the media item a native libVLC callback belongs to.
 *
 * The same [uk.co.caprica.vlcj.player.base.MediaPlayer] instance is reused for every queue item,
 * so the player reference alone cannot tell the media of an old callback from the media of the
 * current one.
 */
internal data class PlaybackMediaIdentity(
    val generation: Long,
    val seekRevision: Long,
)

/**
 * Tracks the media that is currently loaded, the user's seek intent, and the decoded length.
 *
 * libVLC posts media callbacks on its own event thread while ArchiveTune handles them on a
 * coroutine, so a callback can be handled after the media that produced it was already replaced.
 * The controller captures a [PlaybackMediaIdentity] when a callback arrives and drops events that
 * no longer describe the active media instead of treating them as a fresh end of stream.
 *
 * @param nowMs monotonic millisecond clock used to bound the seek acknowledgement window.
 */
internal class PlaybackMediaGuard(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()

    private var seekRevision: Long = 0L
    private var lastUserSeekPositionMs: Long = 0L
    private var pendingSeekGeneration: Long? = null
    private var pendingSeekPositionMs: Long = 0L
    private var decodedDurationGeneration: Long? = null
    private var decodedDurationMs: Long = 0L
    private var startedGeneration: Long? = null
    private var acknowledgedNativePositionMs: Long = 0L
    private var seekTargetMs: Long = 0L
    private var seekOriginMs: Long = 0L
    private var seekDeadlineMs: Long = 0L
    private var seekAcknowledgementPending: Boolean = false
    private var endOfStreamGeneration: Long? = null

    /** Monotonic counter of the user-initiated seeks seen so far. */
    fun seekRevision(): Long = synchronized(lock) { seekRevision }

    /** The position of the most recent user-initiated seek. */
    fun lastUserSeekPositionMs(): Long = synchronized(lock) { lastUserSeekPositionMs }

    /**
     * Record a user-initiated seek.
     *
     * Every user seek invalidates the terminal events that were already in flight: the user took
     * control of the position, so a pending reload must not restore the older position.
     */
    fun noteUserSeek(positionMs: Long): Long = synchronized(lock) {
        seekRevision++
        lastUserSeekPositionMs = positionMs.coerceAtLeast(0L)
        armSeekAcknowledgementLocked(lastUserSeekPositionMs, acknowledgedNativePositionMs)
        seekRevision
    }

    /**
     * Arm the acknowledgement window for a seek that is about to be applied to the engine.
     *
     * The engine applies a seek asynchronously and can report the previous position once more
     * afterwards. Until the engine acknowledges the new position, the reported position must not
     * fall back to the previous one.
     */
    fun armSeekAcknowledgement(targetMs: Long) = synchronized(lock) {
        armSeekAcknowledgementLocked(targetMs.coerceAtLeast(0L), acknowledgedNativePositionMs)
    }

    private fun armSeekAcknowledgementLocked(targetMs: Long, originMs: Long) {
        seekTargetMs = targetMs
        seekOriginMs = originMs
        seekDeadlineMs = nowMs() + SEEK_ACKNOWLEDGEMENT_TIMEOUT_MS
        seekAcknowledgementPending = true
    }

    /** Remember the position a pending load has to start from. */
    fun queuePendingSeek(generation: Long, positionMs: Long) = synchronized(lock) {
        pendingSeekGeneration = generation
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    }

    /** The queued start position for [generation], or null when the load owns a different one. */
    fun pendingSeekFor(generation: Long): Long? = synchronized(lock) {
        if (pendingSeekGeneration == generation) pendingSeekPositionMs else null
    }

    /** Take the queued start position for [generation] and clear it. */
    fun consumePendingSeek(generation: Long): Long? = synchronized(lock) {
        val position = if (pendingSeekGeneration == generation) pendingSeekPositionMs else null
        pendingSeekGeneration = null
        pendingSeekPositionMs = 0L
        position
    }

    /** Drop a queued start position, for example because its load generation is obsolete. */
    fun clearPendingSeek() = synchronized(lock) {
        pendingSeekGeneration = null
        pendingSeekPositionMs = 0L
    }

    /** Remember the length libVLC decoded for the media of [generation]. */
    fun recordDecodedDuration(generation: Long, durationMs: Long) = synchronized(lock) {
        if (durationMs <= 0L) return@synchronized
        decodedDurationGeneration = generation
        decodedDurationMs = durationMs
    }

    /**
     * The decoded length of the media of [generation], or null when the engine never reported one.
     *
     * The catalog duration hint is not a substitute: it describes the library entry, while this
     * value describes the audio libVLC actually decoded.
     */
    fun decodedDurationMs(generation: Long): Long? = synchronized(lock) {
        if (decodedDurationGeneration == generation) decodedDurationMs.takeIf { it > 0L } else null
    }

    /** Note that the media of [generation] started playing. */
    fun noteMediaStarted(generation: Long) = synchronized(lock) {
        startedGeneration = generation
    }

    /** True when the media of [generation] already started playing. */
    fun hasStarted(generation: Long): Boolean = synchronized(lock) {
        startedGeneration == generation
    }

    /** Note that libVLC announced the end of the media of [generation]. */
    fun noteEndOfStreamArrived(generation: Long) = synchronized(lock) {
        endOfStreamGeneration = generation
    }

    /**
     * Take the pending end-of-stream announcement of [generation].
     *
     * libVLC emits `stopped` right after the natural `finished` event. The end-of-stream handler
     * owns that transition, so the trailing `stopped` event must not clear the queue state. The
     * announcement is scoped to its generation so that the end of a replaced media can never
     * suppress the stopped event of the media that replaced it.
     */
    fun consumeEndOfStreamArrival(generation: Long?): Boolean = synchronized(lock) {
        val arrived = generation != null && endOfStreamGeneration == generation
        if (arrived) endOfStreamGeneration = null
        arrived
    }

    /** Forget everything that belongs to the media that was just torn down. */
    fun resetMedia() = synchronized(lock) {
        decodedDurationGeneration = null
        decodedDurationMs = 0L
        startedGeneration = null
        acknowledgedNativePositionMs = 0L
        seekTargetMs = 0L
        seekOriginMs = 0L
        seekDeadlineMs = 0L
        seekAcknowledgementPending = false
        endOfStreamGeneration = null
    }

    /**
     * Filter the position libVLC reports.
     *
     * A seek is applied asynchronously, so the engine can report the position it had before the
     * seek once more afterwards. Until the engine acknowledges the requested position, that stale
     * value is dropped in both directions, so the reported position cannot jump away from the
     * position the user asked for. The window is bounded: when the engine does not acknowledge the
     * seek in time the real native clock is followed again.
     *
     * @return the accepted position, or null when the reported position is still the stale one.
     */
    fun acceptNativePosition(positionMs: Long): Long? = synchronized(lock) {
        if (seekAcknowledgementPending) {
            if (nowMs() > seekDeadlineMs) {
                // The engine never acknowledged the seek. Release the window and follow the
                // real native clock again instead of freezing the reported position.
                seekAcknowledgementPending = false
            } else {
                val forward = seekTargetMs >= seekOriginMs
                val reached = if (forward) {
                    positionMs >= seekTargetMs - SEEK_ACKNOWLEDGEMENT_TOLERANCE_MS
                } else {
                    positionMs <= seekTargetMs + SEEK_ACKNOWLEDGEMENT_TOLERANCE_MS
                }
                if (!reached) return@synchronized null
                seekAcknowledgementPending = false
            }
        }
        acknowledgedNativePositionMs = positionMs
        positionMs
    }

    /** Capture the identity of the media that is active right now. */
    fun captureIdentity(generation: Long?): PlaybackMediaIdentity? = synchronized(lock) {
        generation?.let { PlaybackMediaIdentity(it, seekRevision) }
    }

    /** True when [identity] still describes the media that is loaded right now. */
    fun describesActiveMedia(
        identity: PlaybackMediaIdentity,
        currentGeneration: Long,
        currentActiveGeneration: Long?,
    ): Boolean = synchronized(lock) {
        currentActiveGeneration == identity.generation &&
            currentGeneration == identity.generation
    }

    /** True when the user seeked after [identity] was captured. */
    fun supersededByUserSeek(identity: PlaybackMediaIdentity): Boolean = synchronized(lock) {
        seekRevision != identity.seekRevision
    }

    private companion object {
        /**
         * The engine may report the position slightly behind the requested one while the seek is
         * still being applied. Accept that as "the engine caught up".
         */
        const val SEEK_ACKNOWLEDGEMENT_TOLERANCE_MS = 500L

        /** Upper bound for the window in which the engine may still report the old position. */
        const val SEEK_ACKNOWLEDGEMENT_TIMEOUT_MS = 2_000L
    }
}

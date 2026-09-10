/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal data class NextTrackPreResolutionCandidate(
    val currentIndex: Int,
    val currentMediaId: String,
    val nextIndex: Int,
    val nextMediaId: String,
    val nextUri: String,
)

internal data class NextTrackPreResolutionAttempt internal constructor(
    internal val generation: Long,
    val candidate: NextTrackPreResolutionCandidate,
)

internal data class NextTrackPreResolutionDecision(
    val candidateChanged: Boolean,
    val attempt: NextTrackPreResolutionAttempt?,
)

internal class NextTrackPreResolutionPolicy {
    private var generation = 0L
    private var activeCandidate: NextTrackPreResolutionCandidate? = null
    private var attempted = false

    @Synchronized
    fun update(
        candidate: NextTrackPreResolutionCandidate?,
        isPlaying: Boolean,
    ): NextTrackPreResolutionDecision {
        val changed = activeCandidate != candidate
        if (changed) {
            generation += 1L
            activeCandidate = candidate
            attempted = false
        }

        val attempt =
            if (candidate != null && isPlaying && !attempted) {
                attempted = true
                NextTrackPreResolutionAttempt(generation, candidate)
            } else {
                null
            }
        return NextTrackPreResolutionDecision(changed, attempt)
    }

    @Synchronized
    fun isCurrent(attempt: NextTrackPreResolutionAttempt): Boolean =
        attempt.generation == generation && attempt.candidate == activeCandidate

    @Synchronized
    fun invalidate(): Boolean {
        val hadCandidate = activeCandidate != null || attempted
        generation += 1L
        activeCandidate = null
        attempted = false
        return hadCandidate
    }
}

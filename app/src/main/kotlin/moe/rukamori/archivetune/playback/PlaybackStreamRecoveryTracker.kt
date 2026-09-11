/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal class PlaybackStreamRecoveryTracker(
    private val maxRetryAttemptsPerMedia: Int = 4,
) {
    private var attemptedMediaId: String? = null
    private var retryAttempts = 0

    init {
        require(maxRetryAttemptsPerMedia > 0)
    }

    fun registerRetryAttempt(mediaId: String): Boolean {
        if (attemptedMediaId != mediaId) {
            attemptedMediaId = mediaId
            retryAttempts = 0
        }
        if (retryAttempts >= maxRetryAttemptsPerMedia) return false
        retryAttempts += 1
        return true
    }

    fun onPlaybackRecovered(mediaId: String?) {
        if (mediaId != null && attemptedMediaId == mediaId) {
            attemptedMediaId = null
            retryAttempts = 0
        }
    }

    fun onMediaItemChanged(currentMediaId: String?) {
        if (attemptedMediaId != currentMediaId) {
            attemptedMediaId = null
            retryAttempts = 0
        }
    }
}

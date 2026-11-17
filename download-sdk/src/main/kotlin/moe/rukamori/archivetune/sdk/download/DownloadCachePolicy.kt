/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.download

/** Policies shared by the Android Media3 download engine and its headless tests. */
object DownloadCachePolicy {
    /**
     * A complete playback-cache entry may only bypass fresh stream resolution when no partial
     * download bytes exist for the same media id; otherwise the two representations could be
     * mixed into one file.
     */
    fun shouldReuseCompletePlaybackCache(
        hasPartialDownload: Boolean,
        playbackContentLength: Long?,
        fullyCached: Boolean,
    ): Boolean =
        !hasPartialDownload && playbackContentLength != null && playbackContentLength > 0L && fullyCached

    /**
     * When resuming a partially downloaded file, pin the format recorded in cache metadata (or the
     * persisted database format as a fallback). Fresh downloads must never pin a stale format.
     */
    fun resolvePinnedDownloadFormatId(
        hasPartialDownload: Boolean,
        metadataFormatId: Int?,
        databaseFormatId: Int?,
    ): Int? =
        if (hasPartialDownload) {
            metadataFormatId ?: databaseFormatId
        } else {
            null
        }
}

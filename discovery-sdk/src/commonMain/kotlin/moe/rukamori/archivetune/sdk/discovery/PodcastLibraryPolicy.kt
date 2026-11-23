/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.discovery

/**
 * Pure policies for podcast save-state reconciliation and library membership projection.
 */
object PodcastLibraryPolicy {
    fun shouldSyncPodcastSaveRemotely(
        save: Boolean,
        hasRemoteSavedTimestamp: Boolean,
        playlistId: String?,
    ): String? {
        if (!save && !hasRemoteSavedTimestamp) return null
        return playlistId?.trim()?.takeIf(String::isNotBlank)
    }

    fun <TS> reconcileRemoteSavedFlag(
        isRemotelySaved: Boolean,
        existingRemoteSavedAt: TS?,
        now: TS,
    ): TS? = if (isRemotelySaved) existingRemoteSavedAt ?: now else null

    fun <T> savedEpisodeIds(
        episodes: Iterable<T>,
        isInLibrary: (T) -> Boolean,
        idOf: (T) -> String,
    ): Set<String> =
        episodes
            .asSequence()
            .filter(isInLibrary)
            .map(idOf)
            .toSet()
}

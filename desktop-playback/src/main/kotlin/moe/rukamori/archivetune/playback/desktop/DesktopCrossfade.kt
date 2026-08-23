/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

/** Desktop-only overlap configuration kept out of the platform-neutral playback API. */
data class DesktopCrossfadeConfiguration(
    val enabled: Boolean = false,
    val durationMs: Long = 5_000L,
    val preserveGaplessAlbumTransitions: Boolean = true,
)

interface DesktopCrossfadeControl {
    suspend fun setCrossfadeConfiguration(configuration: DesktopCrossfadeConfiguration)
}

internal fun DesktopCrossfadeConfiguration.sanitized(): DesktopCrossfadeConfiguration = copy(
    durationMs = durationMs.coerceIn(0L, MAX_DESKTOP_CROSSFADE_DURATION_MS),
)

internal const val MAX_DESKTOP_CROSSFADE_DURATION_MS = 10_000L
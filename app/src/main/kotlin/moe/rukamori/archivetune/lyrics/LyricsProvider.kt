/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context

/**
 * Android platform provider. It keeps the `isEnabled(Context)` check in the application while
 * implementing the headless [LyricsFetchProvider] contract consumed by [LyricsFetchCoordinator].
 */
interface LyricsProvider : LyricsFetchProvider {
    fun isEnabled(context: Context): Boolean
}

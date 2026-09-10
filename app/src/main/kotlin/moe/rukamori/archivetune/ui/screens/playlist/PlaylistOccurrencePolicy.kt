/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.playlist

/** Resolves a selected playlist occurrence against the unfiltered queue order. */
internal fun playlistOccurrenceStartIndex(
    allOccurrenceIds: List<Int>,
    selectedOccurrenceId: Int,
): Int = allOccurrenceIds.indexOf(selectedOccurrenceId)

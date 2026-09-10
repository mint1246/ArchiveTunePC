/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOccurrencePolicyTest {
    @Test
    fun `filtered playlist selection keeps original occurrence index`() {
        val fullPlaylistMapIds = listOf(101, 102, 103, 104)

        assertEquals(3, playlistOccurrenceStartIndex(fullPlaylistMapIds, selectedOccurrenceId = 104))
    }

    @Test
    fun `duplicate songs remain independently addressable by map occurrence`() {
        val duplicateSongOccurrenceMapIds = listOf(201, 202, 203)

        assertEquals(0, playlistOccurrenceStartIndex(duplicateSongOccurrenceMapIds, selectedOccurrenceId = 201))
        assertEquals(2, playlistOccurrenceStartIndex(duplicateSongOccurrenceMapIds, selectedOccurrenceId = 203))
    }
}

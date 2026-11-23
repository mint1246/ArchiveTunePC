/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import moe.rukamori.archivetune.lyrics.LyricsTimelineParser

/** Delegates YouLyPlus v2 syllable timeline decoding to `lyrics-sdk`. */
internal fun decodeYouLyPlusTimeline(body: String): List<LyricsLine> =
    LyricsTimelineParser.decodeYouLyPlusTimeline(body)
        ?: throw LyricsException(LyricsError.INVALID_RESPONSE)

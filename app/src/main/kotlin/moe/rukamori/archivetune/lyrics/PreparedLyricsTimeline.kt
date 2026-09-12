/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

/** Desktop-compatible timeline semantics for prepared Android lyrics. */
val PreparedLyrics.isSynchronized: Boolean
    get() = syncType != LyricsSyncType.PLAIN

/**
 * Returns the last currently active vocal line, matching the desktop timeline behavior for
 * overlaps. Plain lyrics and synthetic instrumental gaps intentionally have no active line.
 */
fun PreparedLyrics.activeLineIndex(positionMs: Long): Int {
    if (!isSynchronized) return -1
    return lines.indexOfLast { line ->
        !line.isInstrumental &&
            line.startMs >= 0L &&
            line.startMs <= positionMs &&
            positionMs < line.endMs
    }
}

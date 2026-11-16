/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import androidx.compose.runtime.Immutable

@Immutable
data class LyricsRomanizationPreferences(
    val romanizeJapanese: Boolean,
    val romanizeKorean: Boolean,
    val romanizeChinese: Boolean,
    val romanizeHindi: Boolean,
    val romanizeOther: Boolean,
) {
    val isEnabled: Boolean
        get() = romanizeJapanese || romanizeKorean || romanizeChinese || romanizeHindi || romanizeOther
}

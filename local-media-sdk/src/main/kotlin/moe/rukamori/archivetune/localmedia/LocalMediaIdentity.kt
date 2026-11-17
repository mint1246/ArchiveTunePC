/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import java.text.Normalizer
import java.util.Locale

/**
 * Headless identity normalization for local media.
 *
 * This is the single source of truth for the normalization that stable local artist/album ids,
 * scan folder matching and duplicate artist detection rely on. The Android database object
 * delegates here so persisted ids do not change when the policy is consumed outside the app.
 */
object LocalMediaIdentity {
    private val whitespace = Regex("\\s+")

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .replace(whitespace, " ")
            .lowercase(Locale.ROOT)
}

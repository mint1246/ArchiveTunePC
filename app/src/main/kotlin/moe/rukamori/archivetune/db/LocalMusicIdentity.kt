/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.db

import moe.rukamori.archivetune.localmedia.LocalMediaIdentity

/**
 * Android-facing alias for the headless local-media identity policy. Existing callers and the
 * persisted local ids keep the same behavior; the implementation now lives in `local-media-sdk`.
 */
object LocalMusicIdentity {
    fun normalize(value: String): String = LocalMediaIdentity.normalize(value)
}

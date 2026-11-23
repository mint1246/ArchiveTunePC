/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

internal expect fun socialSessionEpochMillis(): Long

internal expect fun randomSocialDeviceId(): String

internal expect class SocialSessionLock() {
    fun <T> withLock(block: () -> T): T
}

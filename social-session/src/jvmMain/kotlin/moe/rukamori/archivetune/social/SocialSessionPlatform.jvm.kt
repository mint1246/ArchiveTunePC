/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import java.util.UUID

internal actual fun socialSessionEpochMillis(): Long = System.currentTimeMillis()

internal actual fun randomSocialDeviceId(): String = UUID.randomUUID().toString()

internal actual class SocialSessionLock actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}

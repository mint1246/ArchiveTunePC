/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

internal actual fun socialSessionEpochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun randomSocialDeviceId(): String = NSUUID().UUIDString

internal actual class SocialSessionLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

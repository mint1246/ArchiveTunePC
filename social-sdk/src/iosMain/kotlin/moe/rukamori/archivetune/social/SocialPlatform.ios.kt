/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun socialEpochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

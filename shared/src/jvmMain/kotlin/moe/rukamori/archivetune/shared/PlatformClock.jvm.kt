/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import java.time.Instant
import java.time.ZoneId

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()

internal actual fun currentLocalDateTimeParts(): LocalDateTimeParts {
    val nowMs = System.currentTimeMillis()
    val zoned = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
    return LocalDateTimeParts(
        epochMs = nowMs,
        dayOfWeek = (zoned.dayOfWeek.value - 1).coerceIn(0, 6),
        hourOfDay = zoned.hour.coerceIn(0, 23),
    )
}

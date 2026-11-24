/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

internal data class LocalDateTimeParts(
    val epochMs: Long,
    val dayOfWeek: Int, // 0=Monday .. 6=Sunday
    val hourOfDay: Int, // 0..23
)

internal expect fun currentEpochMillis(): Long
internal expect fun currentLocalDateTimeParts(): LocalDateTimeParts

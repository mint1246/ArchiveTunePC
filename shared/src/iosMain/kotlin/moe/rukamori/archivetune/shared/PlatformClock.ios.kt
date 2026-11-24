/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitWeekday
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun currentEpochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun currentLocalDateTimeParts(): LocalDateTimeParts {
    val now = NSDate()
    val epochMs = (now.timeIntervalSince1970 * 1000.0).toLong()
    val calendar = NSCalendar.currentCalendar
    val weekday = calendar.component(NSCalendarUnitWeekday, fromDate = now).toInt() // 1=Sun, 2=Mon .. 7=Sat
    val mondayZero = ((weekday + 5) % 7).coerceIn(0, 6)
    val hour = calendar.component(NSCalendarUnitHour, fromDate = now).toInt().coerceIn(0, 23)
    return LocalDateTimeParts(epochMs = epochMs, dayOfWeek = mondayZero, hourOfDay = hour)
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup.sdk

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScheduledBackupPolicyTest {
    @Test
    fun `nextRunAt computes expected dates and honors disabled or past custom dates`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 17, 14, 30, 0, 0, zone)

        assertNull(
            ScheduledBackupScheduleCalculator.nextRunAt(
                ScheduledBackupConfig(enabled = false, directoryUriString = "content://dir"),
                now,
            ),
        )
        assertNull(
            ScheduledBackupScheduleCalculator.nextRunAt(
                ScheduledBackupConfig(enabled = true, directoryUriString = null),
                now,
            ),
        )

        val daily =
            ScheduledBackupScheduleCalculator.nextRunAt(
                ScheduledBackupConfig(
                    enabled = true,
                    frequency = ScheduledBackupFrequency.DAILY,
                    directoryUriString = "content://dir",
                ),
                now,
            )
        assertEquals(ZonedDateTime.of(2026, 11, 18, 2, 0, 0, 0, zone), daily)

        val weekly =
            ScheduledBackupScheduleCalculator.nextRunAt(
                ScheduledBackupConfig(
                    enabled = true,
                    frequency = ScheduledBackupFrequency.WEEKLY,
                    directoryUriString = "content://dir",
                ),
                now,
            )
        assertEquals(ZonedDateTime.of(2026, 11, 24, 2, 0, 0, 0, zone), weekly)

        val monthly =
            ScheduledBackupScheduleCalculator.nextRunAt(
                ScheduledBackupConfig(
                    enabled = true,
                    frequency = ScheduledBackupFrequency.MONTHLY,
                    directoryUriString = "content://dir",
                ),
                now,
            )
        assertEquals(ZonedDateTime.of(2026, 12, 17, 2, 0, 0, 0, zone), monthly)

        val pastCustom =
            ScheduledBackupScheduleCalculator.nextRunAt(
                ScheduledBackupConfig(
                    enabled = true,
                    frequency = ScheduledBackupFrequency.CUSTOM,
                    customDateEpochDay = LocalDate.of(2026, 11, 16).toEpochDay(),
                    directoryUriString = "content://dir",
                ),
                now,
            )
        assertNull(pastCustom)

        val futureCustom =
            ScheduledBackupScheduleCalculator.nextRunAt(
                ScheduledBackupConfig(
                    enabled = true,
                    frequency = ScheduledBackupFrequency.CUSTOM,
                    customDateEpochDay = LocalDate.of(2026, 11, 20).toEpochDay(),
                    directoryUriString = "content://dir",
                ),
                now,
            )
        assertEquals(ZonedDateTime.of(2026, 11, 20, 2, 0, 0, 0, zone), futureCustom)
    }

    @Test
    fun `initialDelayMillis clamps minimum delay and validateCustomDate rejects past days`() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 11, 17, 14, 30, 0, 0, zone)
        assertEquals(1_000L, ScheduledBackupScheduleCalculator.initialDelayMillis(now.minusMinutes(5), now))

        val today = LocalDate.of(2026, 11, 17)
        ScheduledBackupScheduleCalculator.validateCustomDate(today.toEpochDay(), today)
        assertFailsWith<IllegalArgumentException> {
            ScheduledBackupScheduleCalculator.validateCustomDate(today.minusDays(1).toEpochDay(), today)
        }
    }

    @Test
    fun `buildBackupFileName preserves overwrite and timestamp naming`() {
        val timestamp = LocalDateTime.of(2026, 11, 17, 2, 5, 9)
        assertEquals(
            "ArchiveTune.backup",
            ScheduledBackupScheduleCalculator.buildBackupFileName("ArchiveTune", overwriteExisting = true, now = timestamp),
        )
        assertEquals(
            "ArchiveTune_20261117020509.backup",
            ScheduledBackupScheduleCalculator.buildBackupFileName("ArchiveTune", overwriteExisting = false, now = timestamp),
        )
    }
}

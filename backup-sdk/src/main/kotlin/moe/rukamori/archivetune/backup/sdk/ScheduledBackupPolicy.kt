/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup.sdk

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class ScheduledBackupFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    CUSTOM,
}

data class ScheduledBackupConfig(
    val enabled: Boolean = false,
    val frequency: ScheduledBackupFrequency = ScheduledBackupFrequency.WEEKLY,
    val customDateEpochDay: Long? = null,
    val directoryUriString: String? = null,
    val directoryName: String? = null,
    val overwriteExisting: Boolean = false,
)

object ScheduledBackupScheduleCalculator {
    const val BACKUP_HOUR = 2
    const val MINIMUM_DELAY_MS = 1_000L
    private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun nextRunAt(
        config: ScheduledBackupConfig,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ZonedDateTime? {
        if (!config.enabled || config.directoryUriString == null) return null
        val nextDate =
            when (config.frequency) {
                ScheduledBackupFrequency.DAILY -> now.toLocalDate().plusDays(1)
                ScheduledBackupFrequency.WEEKLY -> now.toLocalDate().plusWeeks(1)
                ScheduledBackupFrequency.MONTHLY -> now.toLocalDate().plusMonths(1)
                ScheduledBackupFrequency.CUSTOM -> {
                    val epochDay = config.customDateEpochDay ?: return null
                    LocalDate.ofEpochDay(epochDay).takeUnless { it.isBefore(now.toLocalDate()) } ?: return null
                }
            }
        return nextDate.atTime(BACKUP_HOUR, 0).atZone(now.zone)
    }

    fun initialDelayMillis(
        runAt: ZonedDateTime,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Long = Duration.between(now, runAt).toMillis().coerceAtLeast(MINIMUM_DELAY_MS)

    fun validateCustomDate(
        epochDay: Long,
        today: LocalDate = LocalDate.now(),
    ) {
        require(epochDay >= today.toEpochDay()) { "Backup date cannot be in the past" }
    }

    fun buildBackupFileName(
        appName: String,
        overwriteExisting: Boolean,
        now: LocalDateTime = LocalDateTime.now(),
    ): String =
        if (overwriteExisting) {
            "$appName.backup"
        } else {
            val timestamp = now.format(FILE_TIMESTAMP_FORMATTER)
            "${appName}_$timestamp.backup"
        }
}

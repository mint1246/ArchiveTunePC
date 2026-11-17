/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import moe.rukamori.archivetune.backup.sdk.ScheduledBackupScheduleCalculator
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledBackupScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun replace(settings: ScheduledBackupSettings) {
            val workManager = WorkManager.getInstance(context)
            val runAt =
                nextRunAt(settings) ?: run {
                    workManager.cancelUniqueWork(WORK_NAME)
                    return
                }
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildRequest(runAt),
            )
        }

        fun appendNext(settings: ScheduledBackupSettings) {
            if (settings.frequency == ScheduledBackupFrequency.CUSTOM) return
            val runAt = nextRunAt(settings) ?: return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(runAt),
            )
        }

        private fun buildRequest(runAt: ZonedDateTime) =
            OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                ).setInitialDelay(
                    ScheduledBackupScheduleCalculator.initialDelayMillis(runAt),
                    TimeUnit.MILLISECONDS,
                ).build()

        private fun nextRunAt(settings: ScheduledBackupSettings): ZonedDateTime? =
            ScheduledBackupScheduleCalculator.nextRunAt(settings.toSdkConfig())

        companion object {
            private const val WORK_NAME = "scheduled_auto_backup"
        }
    }

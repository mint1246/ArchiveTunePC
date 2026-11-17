/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

import android.net.Uri
import moe.rukamori.archivetune.backup.sdk.ScheduledBackupConfig
import moe.rukamori.archivetune.backup.sdk.ScheduledBackupFrequency as SdkScheduledBackupFrequency

typealias ScheduledBackupFrequency = SdkScheduledBackupFrequency

data class ScheduledBackupSettings(
    val enabled: Boolean = false,
    val frequency: ScheduledBackupFrequency = ScheduledBackupFrequency.WEEKLY,
    val customDateEpochDay: Long? = null,
    val directoryUri: Uri? = null,
    val directoryName: String? = null,
    val overwriteExisting: Boolean = false,
) {
    fun toSdkConfig(): ScheduledBackupConfig =
        ScheduledBackupConfig(
            enabled = enabled,
            frequency = frequency,
            customDateEpochDay = customDateEpochDay,
            directoryUriString = directoryUri?.toString(),
            directoryName = directoryName,
            overwriteExisting = overwriteExisting,
        )
}

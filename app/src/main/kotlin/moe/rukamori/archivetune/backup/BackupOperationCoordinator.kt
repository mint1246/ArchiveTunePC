/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

import moe.rukamori.archivetune.backup.sdk.BackupOperationCoordinator as SdkBackupOperationCoordinator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupOperationCoordinator @Inject constructor() {
    internal val sdkCoordinator = SdkBackupOperationCoordinator()

    suspend fun <T> withLock(operation: suspend () -> T): T = sdkCoordinator.withLock(operation)
}

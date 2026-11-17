/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.backup.sdk.BackupArchiveCategory as SdkBackupArchiveCategory
import moe.rukamori.archivetune.backup.sdk.BackupArchiveConstants
import moe.rukamori.archivetune.backup.sdk.BackupArchiveService
import moe.rukamori.archivetune.backup.sdk.BackupArchiveStep as SdkBackupArchiveStep
import moe.rukamori.archivetune.backup.sdk.BackupDatabaseSource
import moe.rukamori.archivetune.backup.sdk.BackupPreferenceValue
import moe.rukamori.archivetune.backup.sdk.BackupRestoreTarget
import moe.rukamori.archivetune.backup.sdk.BackupSettingsSnapshot
import moe.rukamori.archivetune.backup.sdk.BackupValidationResult as SdkBackupValidationResult
import moe.rukamori.archivetune.backup.sdk.RestoreArchiveProgress
import moe.rukamori.archivetune.constants.RedownloadOnRestoreKey
import moe.rukamori.archivetune.db.InternalDatabase
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.div
import moe.rukamori.archivetune.playback.MusicService
import moe.rukamori.archivetune.playback.MusicService.Companion.PERSISTENT_QUEUE_FILE
import moe.rukamori.archivetune.utils.dataStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupArchiveStep {
    EXPORT_SETTINGS,
    CHECKPOINT_DATABASE,
    COPY_DATABASE_FILE,
}

data class BackupArchiveProgress(
    val step: BackupArchiveStep,
    val fileName: String? = null,
    val percent: Int,
    val indeterminate: Boolean,
)

@Singleton
class BackupArchiveRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val operationCoordinator: BackupOperationCoordinator,
    ) {
        private val archiveService = BackupArchiveService(operationCoordinator.sdkCoordinator)

        suspend fun createBackup(
            uri: Uri,
            categories: Set<BackupArchiveCategory>,
            onProgress: (BackupArchiveProgress) -> Unit = {},
        ) {
            val dbFile = context.getDatabasePath(InternalDatabase.DB_NAME)
            val databaseSource =
                object : BackupDatabaseSource {
                    override fun existingDatabaseFiles(): List<File> =
                        listOf(
                            dbFile,
                            dbFile.resolveSibling("${InternalDatabase.DB_NAME}-wal"),
                        ).filter { it.exists() }

                    override suspend fun checkpointAndRead(block: suspend (List<File>) -> Unit) {
                        database.awaitIdle()
                        database.checkpoint()
                        val connection = database.openHelper.writableDatabase
                        connection.beginTransactionNonExclusive()
                        try {
                            block(existingDatabaseFiles())
                        } finally {
                            connection.endTransaction()
                        }
                    }
                }

            archiveService.createBackup(
                categories = categories.mapTo(linkedSetOf()) { it.toSdkCategory() },
                openOutputStream = {
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("Failed to open backup destination")
                },
                preferencesProvider = { readPreferencesSnapshot() },
                databaseSource = databaseSource,
                onProgress = { progress ->
                    onProgress(
                        BackupArchiveProgress(
                            step = progress.step.toAppStep(),
                            fileName = progress.fileName,
                            percent = progress.percent,
                            indeterminate = progress.indeterminate,
                        ),
                    )
                },
            )
        }

        suspend fun validateBackup(
            uri: Uri,
            onError: (Exception) -> Unit = {},
        ): SdkBackupValidationResult =
            archiveService.validateBackup(
                dbName = InternalDatabase.DB_NAME,
                openInputStream = { context.applicationContext.contentResolver.openInputStream(uri) },
                onError = onError,
            )

        suspend fun restoreBackup(
            uri: Uri,
            categories: Set<BackupArchiveCategory>,
            onProgress: (RestoreArchiveProgress) -> Unit = {},
            onBeforeRestart: suspend () -> Unit = {},
            restartApplication: () -> Unit = {},
        ) {
            val restoreTarget =
                object : BackupRestoreTarget {
                    override suspend fun prepareDatabaseRestore() {
                        context.stopService(Intent(context, MusicService::class.java))
                        database.awaitIdle()
                        database.checkpoint()
                        database.close()
                        BackupArchiveConstants.databaseSidecarNames(InternalDatabase.DB_NAME).forEach { sidecarName ->
                            val sidecar = context.getDatabasePath(sidecarName)
                            check(!sidecar.exists() || sidecar.delete()) { "Failed to clear database sidecar" }
                        }
                    }

                    override suspend fun applySettings(snapshot: BackupSettingsSnapshot) {
                        context.dataStore.edit { prefs ->
                            snapshot.booleans.forEach { (k, v) -> prefs[booleanPreferencesKey(k)] = v }
                            snapshot.ints.forEach { (k, v) -> prefs[intPreferencesKey(k)] = v }
                            snapshot.longs.forEach { (k, v) -> prefs[longPreferencesKey(k)] = v }
                            snapshot.floats.forEach { (k, v) -> prefs[floatPreferencesKey(k)] = v }
                            snapshot.strings.forEach { (k, v) -> prefs[stringPreferencesKey(k)] = v }
                            snapshot.stringSets.forEach { (k, v) -> prefs[stringSetPreferencesKey(k)] = v }
                        }
                    }

                    override suspend fun writeLegacyPreferencesPb(inputStream: InputStream) {
                        val settingsDir = context.filesDir / "datastore"
                        if (!settingsDir.exists()) settingsDir.mkdirs()
                        (settingsDir / BackupArchiveConstants.SETTINGS_PB_FILENAME).outputStream().use { out ->
                            inputStream.copyTo(out)
                        }
                    }

                    override suspend fun writeDatabaseFile(
                        entryName: String,
                        inputStream: InputStream,
                    ) {
                        val dbFile = context.getDatabasePath(entryName)
                        if (dbFile.exists()) {
                            dbFile.delete()
                        }
                        FileOutputStream(dbFile).use { out ->
                            inputStream.copyTo(out)
                        }
                    }

                    override suspend fun finalizeRestore(includeDownloads: Boolean) {
                        onBeforeRestart()
                        try {
                            context.filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
                        } catch (_: Exception) {
                        }
                        if (includeDownloads) {
                            context.dataStore.edit { prefs ->
                                prefs[RedownloadOnRestoreKey] = true
                            }
                        }
                        restartApplication()
                    }
                }

            archiveService.restoreBackup(
                categories = categories.mapTo(linkedSetOf()) { it.toSdkCategory() },
                dbName = InternalDatabase.DB_NAME,
                openInputStream = { context.applicationContext.contentResolver.openInputStream(uri) },
                restoreTarget = restoreTarget,
                onProgress = onProgress,
            )
        }

        private suspend fun readPreferencesSnapshot(): Map<String, BackupPreferenceValue> {
            val preferences = context.dataStore.data.first().asMap()
            val mapped = LinkedHashMap<String, BackupPreferenceValue>(preferences.size)
            preferences.forEach { (key, value) ->
                val converted =
                    when (value) {
                        is Boolean -> BackupPreferenceValue.BooleanValue(value)
                        is Int -> BackupPreferenceValue.IntValue(value)
                        is Long -> BackupPreferenceValue.LongValue(value)
                        is Float -> BackupPreferenceValue.FloatValue(value)
                        is String -> BackupPreferenceValue.StringValue(value)
                        is Set<*> -> BackupPreferenceValue.StringSetValue(value.mapTo(linkedSetOf()) { it.toString() })
                        else -> null
                    }
                if (converted != null) {
                    mapped[key.name] = converted
                }
            }
            return mapped
        }

        private fun BackupArchiveCategory.toSdkCategory(): SdkBackupArchiveCategory =
            when (this) {
                BackupArchiveCategory.LIBRARY -> SdkBackupArchiveCategory.LIBRARY
                BackupArchiveCategory.ACCOUNT -> SdkBackupArchiveCategory.ACCOUNT
                BackupArchiveCategory.SETTINGS -> SdkBackupArchiveCategory.SETTINGS
                BackupArchiveCategory.DOWNLOADS -> SdkBackupArchiveCategory.DOWNLOADS
            }

        private fun SdkBackupArchiveStep.toAppStep(): BackupArchiveStep =
            when (this) {
                SdkBackupArchiveStep.EXPORT_SETTINGS -> BackupArchiveStep.EXPORT_SETTINGS
                SdkBackupArchiveStep.CHECKPOINT_DATABASE -> BackupArchiveStep.CHECKPOINT_DATABASE
                SdkBackupArchiveStep.COPY_DATABASE_FILE -> BackupArchiveStep.COPY_DATABASE_FILE
            }

        companion object {
            const val SETTINGS_XML_FILENAME = BackupArchiveConstants.SETTINGS_XML_FILENAME

            val ACCOUNT_PREFERENCE_KEYS: Set<String> = BackupArchiveConstants.ACCOUNT_PREFERENCE_KEYS
        }
    }

enum class BackupArchiveCategory {
    LIBRARY,
    ACCOUNT,
    SETTINGS,
    DOWNLOADS,
}

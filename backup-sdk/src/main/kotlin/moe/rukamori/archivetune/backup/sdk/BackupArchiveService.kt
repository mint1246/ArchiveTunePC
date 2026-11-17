/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

interface BackupDatabaseSource {
    fun existingDatabaseFiles(): List<File>

    suspend fun checkpointAndRead(block: suspend (List<File>) -> Unit)
}

interface BackupRestoreTarget {
    suspend fun prepareDatabaseRestore()

    suspend fun applySettings(snapshot: BackupSettingsSnapshot)

    suspend fun writeLegacyPreferencesPb(inputStream: InputStream)

    suspend fun writeDatabaseFile(
        entryName: String,
        inputStream: InputStream,
    )

    suspend fun finalizeRestore(includeDownloads: Boolean)
}

class BackupArchiveService(
    private val operationCoordinator: BackupOperationCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun createBackup(
        categories: Set<BackupArchiveCategory>,
        openOutputStream: () -> OutputStream,
        preferencesProvider: suspend () -> Map<String, BackupPreferenceValue>,
        databaseSource: BackupDatabaseSource? = null,
        onProgress: (BackupArchiveProgress) -> Unit = {},
    ) = withContext(ioDispatcher) {
        operationCoordinator.withLock {
            require(categories.isNotEmpty()) { "At least one backup category is required" }

            val includeSettings = BackupArchiveCategory.SETTINGS in categories
            val includeAccount = BackupArchiveCategory.ACCOUNT in categories
            val includeLibrary = BackupArchiveCategory.LIBRARY in categories
            val settingsExcludedKeys =
                if (includeAccount) emptySet() else BackupArchiveConstants.ACCOUNT_PREFERENCE_KEYS

            val dbFiles =
                if (includeLibrary) {
                    requireNotNull(databaseSource) { "Database source is required when library backup is selected" }
                        .existingDatabaseFiles()
                        .filter { it.exists() }
                } else {
                    emptyList()
                }

            val totalUnits = (if (includeSettings) 1 else 0) + (if (includeLibrary) 1 else 0) + dbFiles.size
            val unitSpan = 100f / totalUnits.coerceAtLeast(1)
            var completedUnits = 0
            var lastProgress: BackupArchiveProgress? = null

            fun emit(
                step: BackupArchiveStep,
                fileName: String? = null,
                unitFraction: Float = 0f,
                indeterminate: Boolean = false,
            ) {
                val progress =
                    BackupArchiveProgress(
                        step = step,
                        fileName = fileName,
                        percent =
                            ((completedUnits + unitFraction.coerceIn(0f, 1f)) * unitSpan)
                                .roundToInt()
                                .coerceIn(0, 100),
                        indeterminate = indeterminate,
                    )
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress(progress)
                }
            }

            currentCoroutineContext().ensureActive()
            ZipOutputStream(openOutputStream().buffered()).use { zipStream ->
                if (includeSettings) {
                    currentCoroutineContext().ensureActive()
                    emit(BackupArchiveStep.EXPORT_SETTINGS, indeterminate = true)
                    val preferences = preferencesProvider()
                    zipStream.putNextEntry(ZipEntry(BackupArchiveConstants.SETTINGS_XML_FILENAME))
                    BackupSettingsXmlCodec.write(zipStream, preferences, settingsExcludedKeys)
                    zipStream.closeEntry()
                    completedUnits++
                }

                if (includeLibrary) {
                    currentCoroutineContext().ensureActive()
                    emit(BackupArchiveStep.CHECKPOINT_DATABASE, indeterminate = true)
                    val source = requireNotNull(databaseSource)
                    val buffer = ByteArray(BackupArchiveConstants.BUFFER_SIZE)
                    source.checkpointAndRead { files ->
                        completedUnits++
                        files
                            .filter { it.exists() }
                            .forEach { file ->
                                val fileSize = file.length().coerceAtLeast(1L)
                                var bytesCopied = 0L
                                emit(BackupArchiveStep.COPY_DATABASE_FILE, file.name)
                                zipStream.putNextEntry(ZipEntry(file.name))
                                FileInputStream(file).use { input ->
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        zipStream.write(buffer, 0, read)
                                        bytesCopied += read
                                        emit(
                                            step = BackupArchiveStep.COPY_DATABASE_FILE,
                                            fileName = file.name,
                                            unitFraction = bytesCopied.toFloat() / fileSize.toFloat(),
                                        )
                                    }
                                }
                                zipStream.closeEntry()
                                completedUnits++
                            }
                    }
                }
            }
        }
    }

    suspend fun validateBackup(
        dbName: String,
        onError: (Exception) -> Unit = {},
        openInputStream: () -> InputStream?,
    ): BackupValidationResult =
        withContext(ioDispatcher) {
            try {
                val stream =
                    openInputStream()
                        ?: return@withContext BackupValidationResult(
                            isValid = false,
                            availableCategories = emptySet(),
                            error = BackupValidationError.SOURCE_NOT_FOUND,
                        )
                stream.use { inputStream ->
                    val entryNames = mutableSetOf<String>()
                    ZipInputStream(inputStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            currentCoroutineContext().ensureActive()
                            entryNames.add(entry.name)
                            entry = zip.nextEntry
                        }
                    }
                    if (entryNames.isEmpty()) {
                        return@withContext BackupValidationResult(
                            isValid = false,
                            availableCategories = emptySet(),
                            error = BackupValidationError.EMPTY_ARCHIVE,
                        )
                    }
                    val categories = mutableSetOf<BackupArchiveCategory>()
                    val hasSettings =
                        BackupArchiveConstants.SETTINGS_XML_FILENAME in entryNames ||
                            BackupArchiveConstants.SETTINGS_PB_FILENAME in entryNames
                    val hasDb = entryNames.any { it.startsWith(dbName) }
                    if (hasSettings) {
                        categories.add(BackupArchiveCategory.SETTINGS)
                        categories.add(BackupArchiveCategory.ACCOUNT)
                    }
                    if (hasDb) {
                        categories.add(BackupArchiveCategory.LIBRARY)
                        categories.add(BackupArchiveCategory.DOWNLOADS)
                    }
                    if (categories.isEmpty()) {
                        return@withContext BackupValidationResult(
                            isValid = false,
                            availableCategories = emptySet(),
                            error = BackupValidationError.MISSING_CONTENT,
                        )
                    }
                    BackupValidationResult(
                        isValid = true,
                        availableCategories = categories,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                onError(e)
                BackupValidationResult(
                    isValid = false,
                    availableCategories = emptySet(),
                    error = BackupValidationError.CORRUPTED,
                )
            }
        }

    suspend fun restoreBackup(
        categories: Set<BackupArchiveCategory>,
        dbName: String,
        openInputStream: () -> InputStream?,
        restoreTarget: BackupRestoreTarget,
        onProgress: (RestoreArchiveProgress) -> Unit = {},
    ) = withContext(ioDispatcher) {
        operationCoordinator.withLock {
            val includeSettings = BackupArchiveCategory.SETTINGS in categories
            val includeAccount = BackupArchiveCategory.ACCOUNT in categories
            val includeLibrary = BackupArchiveCategory.LIBRARY in categories
            val includeDownloads = BackupArchiveCategory.DOWNLOADS in categories
            val settingsExcludedKeys =
                if (includeAccount) emptySet() else BackupArchiveConstants.ACCOUNT_PREFERENCE_KEYS

            onProgress(
                RestoreArchiveProgress(
                    step = RestoreArchiveStep.VERIFYING,
                    percent = 0,
                    indeterminate = true,
                ),
            )

            val entryNames = ArrayList<String>()
            var hasDb = false
            openInputStream()?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        entryNames.add(entry.name)
                        if (entry.name == dbName) hasDb = true
                        entry = zip.nextEntry
                    }
                }
            }
            if (includeLibrary && !hasDb) throw IllegalStateException("Backup missing database")

            val dbEntryNames = BackupArchiveConstants.databaseEntryNames(dbName)
            val restoreEntries =
                entryNames.filter { name ->
                    (
                        includeSettings && (
                            name == BackupArchiveConstants.SETTINGS_XML_FILENAME ||
                                name == BackupArchiveConstants.SETTINGS_PB_FILENAME
                        )
                    ) ||
                        (includeLibrary && name in dbEntryNames)
                }

            val totalUnits = 1 + (if (includeLibrary) 1 else 0) + restoreEntries.size
            val unitSpan = 100f / totalUnits.coerceAtLeast(1)
            var completedUnits = 0

            fun emit(
                step: RestoreArchiveStep,
                fileName: String? = null,
                indeterminate: Boolean = true,
            ) {
                val p = (completedUnits * unitSpan).roundToInt().coerceIn(0, 100)
                onProgress(
                    RestoreArchiveProgress(
                        step = step,
                        fileName = fileName,
                        percent = p,
                        indeterminate = indeterminate,
                    ),
                )
            }

            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                completedUnits++
                if (includeLibrary) {
                    emit(RestoreArchiveStep.STOPPING_PLAYBACK, indeterminate = true)
                    restoreTarget.prepareDatabaseRestore()
                    completedUnits++
                }

                openInputStream()?.use { stream ->
                    ZipInputStream(stream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (name !in restoreEntries) {
                                entry = zip.nextEntry
                                continue
                            }
                            when {
                                name == BackupArchiveConstants.SETTINGS_XML_FILENAME -> {
                                    emit(RestoreArchiveStep.RESTORING_SETTINGS, indeterminate = true)
                                    val snapshot = BackupSettingsXmlCodec.read(zip, settingsExcludedKeys)
                                    if (!snapshot.isEmpty()) {
                                        restoreTarget.applySettings(snapshot)
                                    }
                                }

                                name == BackupArchiveConstants.SETTINGS_PB_FILENAME -> {
                                    emit(RestoreArchiveStep.RESTORING_SETTINGS, indeterminate = true)
                                    restoreTarget.writeLegacyPreferencesPb(zip)
                                }

                                name in dbEntryNames -> {
                                    emit(RestoreArchiveStep.RESTORING_FILE, fileName = name, indeterminate = true)
                                    restoreTarget.writeDatabaseFile(name, zip)
                                }
                            }
                            completedUnits++
                            entry = zip.nextEntry
                        }
                    }
                }

                onProgress(
                    RestoreArchiveProgress(
                        step = RestoreArchiveStep.RESTARTING,
                        percent = 100,
                        indeterminate = true,
                    ),
                )

                restoreTarget.finalizeRestore(includeDownloads = includeDownloads)
            }
        }
    }
}

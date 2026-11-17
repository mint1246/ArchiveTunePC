/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupArchiveServiceTest {
    @Test
    fun `createBackup writes settings and database files and excludes account keys when omitted`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service = BackupArchiveService(BackupOperationCoordinator(), dispatcher)
            val tempDir = Files.createTempDirectory("backup-sdk-test").toFile()
            try {
                val dbFile = File(tempDir, "song.db").apply { writeBytes("db-bytes".toByteArray()) }
                val walFile = File(tempDir, "song.db-wal").apply { writeBytes("wal-bytes".toByteArray()) }
                val output = ByteArrayOutputStream()
                val progressEvents = mutableListOf<BackupArchiveProgress>()

                val databaseSource =
                    object : BackupDatabaseSource {
                        override fun existingDatabaseFiles(): List<File> = listOf(dbFile, walFile)

                        override suspend fun checkpointAndRead(block: suspend (List<File>) -> Unit) {
                            block(existingDatabaseFiles())
                        }
                    }

                service.createBackup(
                    categories = setOf(BackupArchiveCategory.SETTINGS, BackupArchiveCategory.LIBRARY),
                    openOutputStream = { output },
                    preferencesProvider = {
                        mapOf(
                            "innerTubeCookie" to BackupPreferenceValue.StringValue("secret"),
                            "theme" to BackupPreferenceValue.StringValue("dark"),
                        )
                    },
                    databaseSource = databaseSource,
                    onProgress = { progressEvents.add(it) },
                )

                val entries = linkedMapOf<String, ByteArray>()
                ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entries[entry.name] = zip.readBytes()
                        entry = zip.nextEntry
                    }
                }

                assertEquals(setOf("settings.xml", "song.db", "song.db-wal"), entries.keys)
                assertContentEquals("db-bytes".toByteArray(), entries["song.db"])
                assertContentEquals("wal-bytes".toByteArray(), entries["song.db-wal"])

                val settings = BackupSettingsXmlCodec.read(ByteArrayInputStream(entries.getValue("settings.xml")))
                assertNull(settings.strings["innerTubeCookie"])
                assertEquals("dark", settings.strings["theme"])

                assertTrue(progressEvents.any { it.step == BackupArchiveStep.EXPORT_SETTINGS })
                assertTrue(progressEvents.any { it.step == BackupArchiveStep.CHECKPOINT_DATABASE })
                assertTrue(progressEvents.any { it.step == BackupArchiveStep.COPY_DATABASE_FILE && it.fileName == "song.db" })
                assertEquals(100, progressEvents.last().percent)
            } finally {
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `validateBackup reports valid categories and error cases`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service = BackupArchiveService(BackupOperationCoordinator(), dispatcher)

            val missing = service.validateBackup("song.db") { null }
            assertFalse(missing.isValid)
            assertEquals(BackupValidationError.SOURCE_NOT_FOUND, missing.error)

            val emptyZipBytes = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
            val empty = service.validateBackup("song.db") { ByteArrayInputStream(emptyZipBytes) }
            assertFalse(empty.isValid)
            assertEquals(BackupValidationError.EMPTY_ARCHIVE, empty.error)

            val unrelatedBytes =
                ByteArrayOutputStream()
                    .also { baos ->
                        ZipOutputStream(baos).use { zip ->
                            zip.putNextEntry(ZipEntry("readme.txt"))
                            zip.write("hi".toByteArray())
                            zip.closeEntry()
                        }
                    }.toByteArray()
            val missingContent = service.validateBackup("song.db") { ByteArrayInputStream(unrelatedBytes) }
            assertFalse(missingContent.isValid)
            assertEquals(BackupValidationError.MISSING_CONTENT, missingContent.error)

            val validBytes =
                ByteArrayOutputStream()
                    .also { baos ->
                        ZipOutputStream(baos).use { zip ->
                            zip.putNextEntry(ZipEntry(BackupArchiveConstants.SETTINGS_PB_FILENAME))
                            zip.write(byteArrayOf(1, 2, 3))
                            zip.closeEntry()
                            zip.putNextEntry(ZipEntry("song.db"))
                            zip.write(byteArrayOf(4, 5, 6))
                            zip.closeEntry()
                        }
                    }.toByteArray()
            val valid = service.validateBackup("song.db") { ByteArrayInputStream(validBytes) }
            assertTrue(valid.isValid)
            assertEquals(BackupArchiveCategory.entries.toSet(), valid.availableCategories)
            assertNull(valid.error)

            var reportedError: Exception? = null
            val corrupt =
                service.validateBackup(
                    dbName = "song.db",
                    openInputStream = { throw IllegalStateException("corrupt zip stream") },
                    onError = { reportedError = it },
                )
            assertFalse(corrupt.isValid)
            assertEquals(BackupValidationError.CORRUPTED, corrupt.error)
            assertTrue(reportedError is IllegalStateException)

            var cancellationReported = false
            assertFailsWith<CancellationException> {
                service.validateBackup(
                    dbName = "song.db",
                    openInputStream = { throw CancellationException("cancelled") },
                    onError = { cancellationReported = true },
                )
            }
            assertFalse(cancellationReported)
        }

    @Test
    fun `restoreBackup restores filtered settings and database files and invokes lifecycle hooks`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service = BackupArchiveService(BackupOperationCoordinator(), dispatcher)

            val settingsOut = ByteArrayOutputStream()
            BackupSettingsXmlCodec.write(
                settingsOut,
                mapOf(
                    "innerTubeCookie" to BackupPreferenceValue.StringValue("secret"),
                    "theme" to BackupPreferenceValue.StringValue("light"),
                ),
            )
            val zipBytes =
                ByteArrayOutputStream()
                    .also { baos ->
                        ZipOutputStream(baos).use { zip ->
                            zip.putNextEntry(ZipEntry(BackupArchiveConstants.SETTINGS_XML_FILENAME))
                            zip.write(settingsOut.toByteArray())
                            zip.closeEntry()
                            zip.putNextEntry(ZipEntry("song.db"))
                            zip.write("restored-db".toByteArray())
                            zip.closeEntry()
                        }
                    }.toByteArray()

            var preparedDb = false
            var appliedSnapshot: BackupSettingsSnapshot? = null
            val restoredFiles = linkedMapOf<String, ByteArray>()
            var finalizedDownloads: Boolean? = null
            val steps = mutableListOf<RestoreArchiveStep>()

            val target =
                object : BackupRestoreTarget {
                    override suspend fun prepareDatabaseRestore() {
                        preparedDb = true
                    }

                    override suspend fun applySettings(snapshot: BackupSettingsSnapshot) {
                        appliedSnapshot = snapshot
                    }

                    override suspend fun writeLegacyPreferencesPb(inputStream: InputStream) = Unit

                    override suspend fun writeDatabaseFile(
                        entryName: String,
                        inputStream: InputStream,
                    ) {
                        restoredFiles[entryName] = inputStream.readBytes()
                    }

                    override suspend fun finalizeRestore(includeDownloads: Boolean) {
                        finalizedDownloads = includeDownloads
                    }
                }

            service.restoreBackup(
                categories = setOf(BackupArchiveCategory.SETTINGS, BackupArchiveCategory.LIBRARY, BackupArchiveCategory.DOWNLOADS),
                dbName = "song.db",
                openInputStream = { ByteArrayInputStream(zipBytes) },
                restoreTarget = target,
                onProgress = { steps.add(it.step) },
            )

            assertTrue(preparedDb)
            assertEquals("light", appliedSnapshot?.strings?.get("theme"))
            assertNull(appliedSnapshot?.strings?.get("innerTubeCookie"))
            assertContentEquals("restored-db".toByteArray(), restoredFiles["song.db"])
            assertEquals(true, finalizedDownloads)
            assertEquals(RestoreArchiveStep.VERIFYING, steps.first())
            assertEquals(RestoreArchiveStep.RESTARTING, steps.last())
        }

    @Test
    fun `restoreBackup fails when library requested without database entry`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service = BackupArchiveService(BackupOperationCoordinator(), dispatcher)
            val zipBytes =
                ByteArrayOutputStream()
                    .also { baos ->
                        ZipOutputStream(baos).use { zip ->
                            zip.putNextEntry(ZipEntry("settings.xml"))
                            zip.write("<ArchiveTuneBackup><Settings/></ArchiveTuneBackup>".toByteArray())
                            zip.closeEntry()
                        }
                    }.toByteArray()

            val noopTarget =
                object : BackupRestoreTarget {
                    override suspend fun prepareDatabaseRestore() = Unit
                    override suspend fun applySettings(snapshot: BackupSettingsSnapshot) = Unit
                    override suspend fun writeLegacyPreferencesPb(inputStream: InputStream) = Unit
                    override suspend fun writeDatabaseFile(entryName: String, inputStream: InputStream) = Unit
                    override suspend fun finalizeRestore(includeDownloads: Boolean) = Unit
                }

            assertFailsWith<IllegalStateException> {
                service.restoreBackup(
                    categories = setOf(BackupArchiveCategory.LIBRARY),
                    dbName = "song.db",
                    openInputStream = { ByteArrayInputStream(zipBytes) },
                    restoreTarget = noopTarget,
                )
            }
        }

    @Test
    fun `cancellation during createBackup propagates CancellationException and coordinator serializes operations`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = BackupOperationCoordinator()
            val service = BackupArchiveService(coordinator, dispatcher)
            val started = CompletableDeferred<Unit>()

            val job =
                launch {
                    service.createBackup(
                        categories = setOf(BackupArchiveCategory.SETTINGS),
                        openOutputStream = { ByteArrayOutputStream() },
                        preferencesProvider = {
                            started.complete(Unit)
                            throw CancellationException("cancelled")
                        },
                    )
                }
            started.await()
            job.cancelAndJoin()

            val secondResult =
                async {
                    coordinator.withLock { "acquired" }
                }
            assertEquals("acquired", secondResult.await())
        }
}

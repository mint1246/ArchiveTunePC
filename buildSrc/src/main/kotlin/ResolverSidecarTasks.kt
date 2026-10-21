/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Extracts exactly one verified executable from a pinned upstream ZIP archive.
 *
 * The task never extracts a ZIP tree and never trusts entry paths: it streams
 * the single expected member into a caller-selected output file, so a malicious
 * archive cannot write outside the build directory (ZIP slip) or create a
 * symlink. Every central-directory name is validated first, and the archive may
 * contain nothing except that member and its directory parents.
 *
 * The extracted executable is hashed and compared with a committed digest, so
 * verifying the downloaded ZIP checksum and the extracted binary checksum are
 * separate, auditable steps.
 */
@DisableCachingByDefault(because = "Extraction materializes a verified executable in the build directory.")
abstract class ExtractVerifiedArchiveEntryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceArchive: RegularFileProperty

    @get:Input
    abstract val archiveMember: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val archive = sourceArchive.get().asFile
        if (!archive.isFile || archive.length() == 0L) {
            throw GradleException("Resolver archive is missing or empty: ${archive.absolutePath}")
        }

        val member = archiveMember.get()
        validateMemberName(member)
        val memberSegments = member.split('/')
        val allowedDirectories = memberSegments.dropLast(1)
            .indices
            .map { index -> memberSegments.take(index + 1).joinToString("/") + "/" }
            .toSet()

        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        val temporary = destination.resolveSibling("${destination.name}.part")
        temporary.delete()

        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                if (entries.isEmpty()) {
                    throw GradleException("Resolver archive has no entries: ${archive.absolutePath}")
                }
                if (entries.size > MAX_ARCHIVE_ENTRIES) {
                    throw GradleException(
                        "Resolver archive has ${entries.size} entries, more than the $MAX_ARCHIVE_ENTRIES-entry " +
                            "limit: ${archive.absolutePath}",
                    )
                }
                var memberEntries = 0
                val seenNames = mutableSetOf<String>()
                entries.forEach { entry ->
                    validateEntryName(entry.name)
                    if (!seenNames.add(entry.name)) {
                        throw GradleException(
                            "Resolver archive contains duplicate entry '${entry.name}' in ${archive.absolutePath}",
                        )
                    }
                    when {
                        entry.name == member -> {
                            if (entry.isDirectory) {
                                throw GradleException(
                                    "Resolver archive member '$member' is a directory in ${archive.absolutePath}",
                                )
                            }
                            memberEntries += 1
                        }

                        entry.name in allowedDirectories -> {
                            if (!entry.isDirectory) {
                                throw GradleException(
                                    "Resolver archive entry '${entry.name}' must be a directory in ${archive.absolutePath}",
                                )
                            }
                        }

                        else -> throw GradleException(
                            "Resolver archive contains unexpected entry '${entry.name}' in ${archive.absolutePath}; " +
                                "only '$member' and its parent directories are allowed",
                        )
                    }
                }
                if (memberEntries != 1) {
                    throw GradleException(
                        "Resolver archive must contain '$member' exactly once, found $memberEntries in " +
                            "${archive.absolutePath}",
                    )
                }

                val entry = zip.getEntry(member)
                if (entry.size > MAX_EXTRACTED_BYTES) {
                    throw GradleException(
                        "Resolver archive member '$member' is too large (${entry.size} bytes) in ${archive.absolutePath}",
                    )
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                zip.getInputStream(entry).use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_EXTRACTED_BYTES) {
                                throw GradleException(
                                    "Resolver archive member '$member' exceeds the ${MAX_EXTRACTED_BYTES}-byte limit " +
                                        "in ${archive.absolutePath}",
                                )
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (written == 0L) {
                    throw GradleException("Resolver archive member '$member' is empty in ${archive.absolutePath}")
                }

                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = expectedSha256.get().lowercase()
                if (actual != expected) {
                    throw GradleException(
                        "Extracted resolver executable checksum mismatch for '$member': expected $expected, " +
                            "got $actual (${archive.absolutePath})",
                    )
                }

                try {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    // Some build file systems do not support atomic renames. The
                    // output is still published in one replace operation.
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
            setPermissions(destination, executable = true)
            destination.setLastModified(REPRODUCIBLE_TIMESTAMP_MILLIS)
        } finally {
            temporary.delete()
        }
    }

    private fun validateMemberName(member: String) {
        if (member.isBlank() || member.endsWith('/')) {
            throw GradleException("Resolver archive member must name one regular file, got '$member'")
        }
        validateEntryName(member)
    }

    private fun setPermissions(file: File, executable: Boolean) {
        val view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) ?: return
        Files.setPosixFilePermissions(
            file.toPath(),
            PosixFilePermissions.fromString(if (executable) "rwxr-xr-x" else "rw-r--r--"),
        )
    }

    private fun validateEntryName(name: String) {
        val directoryEntry = name.endsWith('/')
        val normalized = if (directoryEntry) name.dropLast(1) else name
        val segments = normalized.split('/')
        if (normalized.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains(':') ||
            name.any { it.code < 0x20 || it.code == 0x7f } ||
            segments.any { it.isEmpty() || it == "." || it == ".." }
        ) {
            throw GradleException("Unsafe path in resolver archive: '$name'")
        }
    }

    private companion object {
        const val MAX_EXTRACTED_BYTES = 256L * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 1_024
        const val REPRODUCIBLE_TIMESTAMP_MILLIS = 946_684_800_000L
    }
}

/**
 * Stages the checksum-verified yt-dlp and Bun executables, their notices, and a
 * machine-readable manifest into a distribution `resolver` directory.
 *
 * The task owns the whole destination directory: files from a previous platform
 * are removed before staging, so a Linux app image can never keep a stale
 * `yt-dlp.exe` (and vice versa), and a checked-out tree that still carries the
 * retired QuickJS sidecar cannot leak it into a package.
 */
@DisableCachingByDefault(
    because = "Staging writes executable sidecars and a small manifest into the application image.",
)
abstract class StageResolverSidecarsTask : DefaultTask() {
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ytDlpBinary: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bunBinary: RegularFileProperty

    @get:Input
    abstract val platformId: Property<String>

    @get:Input
    abstract val ytDlpVersion: Property<String>

    @get:Input
    abstract val ytDlpUrl: Property<String>

    @get:Input
    abstract val ytDlpSha256: Property<String>

    @get:Input
    abstract val bundledEjsVersion: Property<String>

    @get:Input
    abstract val bunVersion: Property<String>

    @get:Input
    abstract val bunUrl: Property<String>

    @get:Input
    abstract val bunSha256: Property<String>

    @get:Input
    abstract val bunBinarySha256: Property<String>

    @get:Optional
    @get:Input
    abstract val unsupportedReason: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licensesDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val destinationDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        if (!ytDlpBinary.isPresent || !bunBinary.isPresent) {
            throw GradleException(
                unsupportedReason.orNull ?: "Resolver sidecar binaries were not provided for this build.",
            )
        }

        val destination = destinationDirectory.get().asFile
        replaceDirectory(destination)

        val platform = platformId.get()
        val windows = platform.startsWith("windows-")
        copyFile(
            source = ytDlpBinary.get().asFile,
            target = destination.resolve(if (windows) "yt-dlp.exe" else "yt-dlp"),
            executable = !windows,
        )
        copyFile(
            source = bunBinary.get().asFile,
            target = destination.resolve(if (windows) "bun.exe" else "bun"),
            executable = !windows,
        )
        copyFile(
            source = thirdPartyNotices.get().asFile,
            target = destination.resolve("THIRD_PARTY_NOTICES.md"),
            executable = false,
        )
        copyDirectory(
            source = licensesDirectory.get().asFile,
            target = destination.resolve("licenses"),
            executable = false,
        )

        destination.resolve("manifest.properties").writeText(
            buildManifest(platform, windows),
            Charsets.UTF_8,
        )
        normalizeTree(destination)
    }

    private fun buildManifest(platform: String, windows: Boolean): String = buildString {
        appendLine("format=archivetune-resolver-sidecars")
        appendLine("format.version=1")
        appendLine("platform=$platform")
        appendLine("yt-dlp.version=${ytDlpVersion.get()}")
        appendLine("yt-dlp.file=${if (windows) "yt-dlp.exe" else "yt-dlp"}")
        appendLine("yt-dlp.url=${ytDlpUrl.get()}")
        appendLine("yt-dlp.sha256=${ytDlpSha256.get().lowercase()}")
        appendLine("yt-dlp.bundled-ejs.version=${bundledEjsVersion.get()}")
        appendLine("bun.version=${bunVersion.get()}")
        appendLine("bun.file=${if (windows) "bun.exe" else "bun"}")
        appendLine("bun.url=${bunUrl.get()}")
        appendLine("bun.sha256=${bunSha256.get().lowercase()}")
        appendLine("bun.binary-sha256=${bunBinarySha256.get().lowercase()}")
    }

    private fun replaceDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw GradleException("Unable to replace staged resolver directory at ${directory.absolutePath}")
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw GradleException("Unable to create staged resolver directory at ${directory.absolutePath}")
        }
    }

    private fun copyFile(source: File, target: File, executable: Boolean) {
        if (!source.isFile) {
            throw GradleException("Resolver sidecar source is missing: ${source.absolutePath}")
        }
        target.parentFile.mkdirs()
        Files.copy(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        setPermissions(target, executable)
        normalizeTimestamp(target)
    }

    private fun copyDirectory(source: File, target: File, executable: Boolean) {
        if (!source.isDirectory) {
            throw GradleException("Resolver license directory is missing: ${source.absolutePath}")
        }
        Files.walk(source.toPath()).use { paths ->
            paths.sorted().forEach { entry ->
                val output = target.toPath().resolve(source.toPath().relativize(entry).toString())
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(output)
                    normalizeTimestamp(output.toFile())
                } else {
                    Files.createDirectories(output.parent)
                    Files.copy(
                        entry,
                        output,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    setPermissions(output.toFile(), executable)
                    normalizeTimestamp(output.toFile())
                }
            }
        }
    }

    private fun setPermissions(file: File, executable: Boolean) {
        val view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java) ?: return
        Files.setPosixFilePermissions(
            file.toPath(),
            PosixFilePermissions.fromString(if (executable) "rwxr-xr-x" else "rw-r--r--"),
        )
    }

    private fun normalizeTimestamp(file: File) {
        // Keep staged payloads byte-for-byte reproducible. Downloads otherwise inherit
        // the wall-clock time at which the build fetched them.
        file.setLastModified(REPRODUCIBLE_TIMESTAMP_MILLIS)
    }

    private fun normalizeTree(root: File) {
        // Deepest entries first so rewriting a child does not bump its parent's mtime.
        root.walkTopDown()
            .toList()
            .sortedByDescending { it.path.length }
            .forEach { normalizeTimestamp(it) }
    }

    private companion object {
        const val REPRODUCIBLE_TIMESTAMP_MILLIS = 946_684_800_000L
    }
}

/**
 * Verifies a staged `resolver` directory without executing the sidecars, so the
 * check also works when resources for another platform are staged on a
 * cross-platform build host.
 */
@DisableCachingByDefault(because = "Verification only inspects the staged sidecar directory.")
abstract class VerifyResolverSidecarsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sidecarDirectory: DirectoryProperty

    @get:Input
    abstract val platformId: Property<String>

    @get:Input
    abstract val ytDlpSha256: Property<String>

    @get:Input
    abstract val bunSha256: Property<String>

    @get:Input
    abstract val bunBinarySha256: Property<String>

    @get:Optional
    @get:Input
    abstract val unsupportedReason: Property<String>

    @TaskAction
    fun verify() {
        val platform = platformId.get()
        if (platform !in ResolverSidecars.supportedPlatforms) {
            throw GradleException(
                unsupportedReason.orNull ?: "Cannot verify resolver sidecars for unsupported platform '$platform'.",
            )
        }

        val directory = sidecarDirectory.get().asFile
        val windows = platform.startsWith("windows-")
        val expectedFiles = setOf(
            if (windows) "yt-dlp.exe" else "yt-dlp",
            if (windows) "bun.exe" else "bun",
            "manifest.properties",
            "THIRD_PARTY_NOTICES.md",
        )
        val ytDlp = directory.resolve(if (windows) "yt-dlp.exe" else "yt-dlp")
        val bun = directory.resolve(if (windows) "bun.exe" else "bun")

        expectedFiles.forEach { name ->
            val file = directory.resolve(name)
            if (!file.isFile || file.length() == 0L) {
                throw GradleException("Packaged resolver sidecar '$name' is missing or empty under ${directory.absolutePath}")
            }
        }
        verifyChecksum(ytDlp, ytDlpSha256.get(), "yt-dlp")
        // The staged file is the executable extracted from the verified Bun ZIP,
        // so this compares against the binary digest, not the archive digest.
        verifyChecksum(bun, bunBinarySha256.get(), "bun")

        if (!windows) {
            verifyExecutable(ytDlp)
            verifyExecutable(bun)
        }

        val licenses = directory.resolve("licenses")
        if (!licenses.isDirectory || licenses.listFiles().isNullOrEmpty()) {
            throw GradleException("Packaged resolver licenses are missing under ${licenses.absolutePath}")
        }
        val requiredLicenses = listOf(
            "yt-dlp-UNLICENSE.txt",
            "bun-LICENSE.md",
            "bun-LGPL-2.0.txt",
            "bun-LGPL-2.1.txt",
            "bun-WEBKIT-NOTICE.md",
            "yt-dlp-THIRD_PARTY_LICENSES.txt",
            "yt-dlp-ejs-UNLICENSE.txt",
        )
        requiredLicenses.forEach { name ->
            if (!licenses.resolve(name).isFile) {
                throw GradleException("Packaged resolver license '$name' is missing under ${licenses.absolutePath}")
            }
        }
        val pyInstallerLicense = licenses.listFiles().orEmpty()
            .firstOrNull { it.isFile && it.name.startsWith("pyinstaller-") && it.name.endsWith("-COPYING.txt") }
            ?: throw GradleException(
                "Packaged resolver licenses do not contain the PyInstaller COPYING text under ${licenses.absolutePath}",
            )
        if (pyInstallerLicense.length() == 0L) {
            throw GradleException("Packaged PyInstaller license is empty: ${pyInstallerLicense.absolutePath}")
        }

        val manifest = readManifest(directory.resolve("manifest.properties"))
        val expectedManifest = mapOf(
            "format" to "archivetune-resolver-sidecars",
            "format.version" to "1",
            "platform" to platform,
            "yt-dlp.sha256" to ytDlpSha256.get().lowercase(),
            "bun.sha256" to bunSha256.get().lowercase(),
            "bun.binary-sha256" to bunBinarySha256.get().lowercase(),
        )
        expectedManifest.forEach { (key, value) ->
            if (manifest[key] != value) {
                throw GradleException(
                    "Packaged resolver manifest has $key='${manifest[key]}', expected '$value' under " +
                        "${directory.absolutePath}",
                )
            }
        }

        val unexpected = directory.listFiles().orEmpty()
            .filterNot { it.name in expectedFiles || it.name == "licenses" }
            .map { it.name }
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Unexpected files in packaged resolver directory ${directory.absolutePath}: " +
                    unexpected.sorted().joinToString(),
            )
        }
    }

    private fun verifyChecksum(file: File, expectedSha256: String, label: String) {
        val actual = MessageDigest.getInstance("SHA-256").run {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    update(buffer, 0, read)
                }
            }
            digest().joinToString("") { "%02x".format(it) }
        }
        if (actual != expectedSha256.lowercase()) {
            throw GradleException(
                "Packaged $label checksum mismatch: expected $expectedSha256, got $actual (${file.absolutePath})",
            )
        }
    }

    private fun verifyExecutable(file: File) {
        val view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
            ?: throw GradleException(
                "Cannot verify the executable bit for ${file.absolutePath}: the staging file system has no POSIX permissions",
            )
        val permissions = view.readAttributes().permissions()
        if (!permissions.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)) {
            throw GradleException("Packaged resolver sidecar is not executable: ${file.absolutePath}")
        }
    }

    private fun readManifest(file: File): Map<String, String> =
        file.readLines(Charsets.UTF_8)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    throw GradleException("Malformed resolver manifest line '$line' in ${file.absolutePath}")
                }
                line.substring(0, separator) to line.substring(separator + 1)
            }
}

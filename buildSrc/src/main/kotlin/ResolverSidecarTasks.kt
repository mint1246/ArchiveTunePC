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

/**
 * Stages the checksum-verified yt-dlp and quickjs-ng executables, their notices,
 * and a machine-readable manifest into a distribution `resolver` directory.
 *
 * The task owns the whole destination directory: files from a previous platform
 * are removed before staging, so a Linux app image can never keep a stale
 * `yt-dlp.exe` (and vice versa).
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
    abstract val quickJsBinary: RegularFileProperty

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
    abstract val quickJsVersion: Property<String>

    @get:Input
    abstract val quickJsUrl: Property<String>

    @get:Input
    abstract val quickJsSha256: Property<String>

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
        if (!ytDlpBinary.isPresent || !quickJsBinary.isPresent) {
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
            source = quickJsBinary.get().asFile,
            target = destination.resolve(if (windows) "qjs.exe" else "qjs"),
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
        appendLine("quickjs-ng.version=${quickJsVersion.get()}")
        appendLine("quickjs-ng.file=${if (windows) "qjs.exe" else "qjs"}")
        appendLine("quickjs-ng.url=${quickJsUrl.get()}")
        appendLine("quickjs-ng.sha256=${quickJsSha256.get().lowercase()}")
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
        if (executable) {
            setExecutable(target)
        }
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
                    if (executable) {
                        setExecutable(output.toFile())
                    }
                    normalizeTimestamp(output.toFile())
                }
            }
        }
    }

    private fun setExecutable(file: File) {
        val view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
        if (view != null) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
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
    abstract val quickJsSha256: Property<String>

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
            if (windows) "qjs.exe" else "qjs",
            "manifest.properties",
            "THIRD_PARTY_NOTICES.md",
        )
        val ytDlp = directory.resolve(if (windows) "yt-dlp.exe" else "yt-dlp")
        val quickJs = directory.resolve(if (windows) "qjs.exe" else "qjs")

        expectedFiles.forEach { name ->
            val file = directory.resolve(name)
            if (!file.isFile || file.length() == 0L) {
                throw GradleException("Packaged resolver sidecar '$name' is missing or empty under ${directory.absolutePath}")
            }
        }
        verifyChecksum(ytDlp, ytDlpSha256.get(), "yt-dlp")
        verifyChecksum(quickJs, quickJsSha256.get(), "quickjs-ng")

        if (!windows) {
            verifyExecutable(ytDlp)
            verifyExecutable(quickJs)
        }

        val licenses = directory.resolve("licenses")
        if (!licenses.isDirectory || licenses.listFiles().isNullOrEmpty()) {
            throw GradleException("Packaged resolver licenses are missing under ${licenses.absolutePath}")
        }
        val requiredLicenses = listOf(
            "yt-dlp-UNLICENSE.txt",
            "quickjs-ng-MIT.txt",
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
            "quickjs-ng.sha256" to quickJsSha256.get().lowercase(),
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

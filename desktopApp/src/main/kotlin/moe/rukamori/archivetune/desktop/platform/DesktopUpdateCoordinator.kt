package moe.rukamori.archivetune.desktop.platform

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.api.PlaybackController
import org.json.JSONObject

sealed interface DesktopUpdateState {
    data object Disabled : DesktopUpdateState
    data object Checking : DesktopUpdateState
    data object UpToDate : DesktopUpdateState
    data class Downloading(val version: String) : DesktopUpdateState
    data class Ready(val version: String, val msi: Path) : DesktopUpdateState
    data class Failed(val message: String) : DesktopUpdateState
}

/**
 * Downloads an MSI completely in the background, then waits for a playback-safe moment before
 * handing installation to a detached PowerShell process. The app itself never tries to overwrite
 * files that are still mapped by the running process.
 */
class DesktopUpdateCoordinator(
    private val runtime: DesktopRuntime,
    private val playback: PlaybackController,
) {
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val updateDirectory = runtime.paths.cache.resolve("updates")
    private val _state = MutableStateFlow<DesktopUpdateState>(DesktopUpdateState.Disabled)
    val state = _state.asStateFlow()
    private val _restartRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartRequests = _restartRequests.asSharedFlow()

    @Volatile
    private var staged: DesktopUpdateState.Ready? = null
    private val runningExecutableSha256: String? by lazy {
        currentExecutable()?.takeIf { Files.isRegularFile(it) }?.let(::sha256)
    }

    fun start() {
        val baseUrl = DesktopBuildInfo.UPDATE_BASE_URL.trim().trimEnd('/')
        val installedExecutable = installedExecutable()
        val currentExecutable = currentExecutable()
        if (
            !isWindows() ||
            baseUrl.isBlank() ||
            installedExecutable == null ||
            currentExecutable == null ||
            currentExecutable != installedExecutable
        ) {
            if (currentExecutable != null && installedExecutable != null && currentExecutable != installedExecutable) {
                runtime.logger.info("Automatic updates disabled for non-installed build: $currentExecutable")
            }
            _state.value = DesktopUpdateState.Disabled
            return
        }

        runtime.applicationScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { checkAndStage(baseUrl) }
                    .onFailure { failure ->
                        runtime.logger.warning("Update check failed: ${failure.message}")
                        _state.value = DesktopUpdateState.Failed(failure.message ?: "Update check failed")
                    }

                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun launchInstallerHandoff(): Boolean = withContext(Dispatchers.IO) {
        val ready = staged ?: return@withContext false
        val executable = installedExecutable() ?: return@withContext false
        Files.createDirectories(updateDirectory)
        val script = updateDirectory.resolve("apply-${sanitizeVersion(ready.version)}.ps1")
        Files.writeString(script, handoffScript(ready.msi, executable, ProcessHandle.current().pid()))

        runCatching {
            ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toAbsolutePath().toString(),
            )
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrElse { failure ->
            runtime.logger.warning("Could not launch updater handoff: ${failure.message}")
            false
        }
    }

    private suspend fun checkAndStage(baseUrl: String) {
        _state.value = DesktopUpdateState.Checking
        val manifestUri = URI.create("$baseUrl/latest.json")
        val manifest = fetchJson(manifestUri)
        val version = manifest.getString("version").trim()
        val expectedExecutableSha256 = manifest.optString("exeSha256")
            .trim()
            .lowercase()
            .takeIf(String::isNotBlank)
        val currentExecutableSha256 = expectedExecutableSha256?.let {
            runningExecutableSha256 ?: error("Could not hash the running ArchiveTune executable")
        }
        if (!shouldStageDesktopUpdate(
                manifestVersion = version,
                currentVersion = DesktopBuildInfo.VERSION,
                expectedExecutableSha256 = expectedExecutableSha256,
                currentExecutableSha256 = currentExecutableSha256,
            )
        ) {
            _state.value = DesktopUpdateState.UpToDate
            return
        }

        val downloadValue = manifest.optString("url").takeIf(String::isNotBlank)
            ?: manifest.optString("msi").takeIf(String::isNotBlank)
            ?: error("latest.json is missing url/msi")
        val downloadUri = manifestUri.resolve(downloadValue)
        val expectedSha256 = manifest.getString("sha256").trim().lowercase()

        Files.createDirectories(updateDirectory)
        val destination = updateDirectory.resolve("ArchiveTune-${sanitizeVersion(version)}.msi")
        if (!Files.isRegularFile(destination) || sha256(destination) != expectedSha256) {
            _state.value = DesktopUpdateState.Downloading(version)
            download(downloadUri, destination)
            val actualSha256 = sha256(destination)
            check(actualSha256 == expectedSha256) {
                "Downloaded update failed SHA-256 verification"
            }
        }

        val ready = DesktopUpdateState.Ready(version, destination)
        staged = ready
        _state.value = ready
        waitForSafeRestart()
    }

    private suspend fun waitForSafeRestart() {
        val baseline = playback.state.value
        if (!baseline.isPlaying || baseline.currentItem == null) {
            _restartRequests.emit(Unit)
            return
        }

        val baselineIndex = baseline.currentIndex
        val baselineId = baseline.currentItem?.metadata?.id
        playback.state.first { snapshot ->
            val trackChanged =
                snapshot.currentIndex != baselineIndex || snapshot.currentItem?.metadata?.id != baselineId
            val playbackStopped = !snapshot.isPlaying && snapshot.currentItem == null
            trackChanged || playbackStopped
        }
        _restartRequests.emit(Unit)
    }

    private fun fetchJson(uri: URI): JSONObject {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Update manifest HTTP ${response.statusCode()}" }
        return JSONObject(response.body())
    }

    private fun download(uri: URI, destination: Path) {
        val temporary = destination.resolveSibling("${destination.fileName}.part")
        Files.deleteIfExists(temporary)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofFile(temporary))
        check(response.statusCode() in 200..299) { "Update download HTTP ${response.statusCode()}" }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun handoffScript(msi: Path, executable: Path, pid: Long): String {
        val msiPath = powershellSingleQuoted(msi.toAbsolutePath().toString())
        val executablePath = powershellSingleQuoted(executable.toAbsolutePath().toString())
        return """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            Wait-Process -Id $pid
            ${'$'}installer = Start-Process -FilePath "${'$'}env:SystemRoot\System32\msiexec.exe" -ArgumentList @('/i', '$msiPath', '/qn', '/norestart') -Wait -PassThru
            Start-Process -FilePath '$executablePath'
            Remove-Item -LiteralPath ${'$'}MyInvocation.MyCommand.Path -Force
        """.trimIndent() + "\n"
    }

    private fun currentExecutable(): Path? =
        ProcessHandle.current().info().command().orElse(null)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()

    private fun installedExecutable(): Path? =
        System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.resolve("ArchiveTune App")
            ?.resolve("ArchiveTune.exe")
            ?.toAbsolutePath()
            ?.normalize()

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeVersion(version: String): String = version.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun powershellSingleQuoted(value: String): String = value.replace("'", "''")

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private companion object {
        const val UPDATE_CHECK_INTERVAL_MS = 30L * 60L * 1000L
    }
}

internal fun compareVersions(left: String, right: String): Int {
    val leftParts = normalizedVersionParts(left)
    val rightParts = normalizedVersionParts(right)
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val a = leftParts.getOrElse(index) { 0 }
        val b = rightParts.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

internal fun shouldStageDesktopUpdate(
    manifestVersion: String,
    currentVersion: String,
    expectedExecutableSha256: String?,
    currentExecutableSha256: String?,
): Boolean {
    val expected = expectedExecutableSha256?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    if (expected != null) {
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "latest.json contains an invalid exeSha256" }
        val current = currentExecutableSha256?.trim()?.lowercase()
            ?: error("Current executable SHA-256 is unavailable")
        require(current.matches(Regex("[0-9a-f]{64}"))) { "Current executable SHA-256 is invalid" }
        return current != expected
    }
    return compareVersions(manifestVersion, currentVersion) > 0
}

private fun normalizedVersionParts(version: String): List<Int> =
    version.trim().removePrefix("v").split('.', '-', '+')
        .map { token -> token.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

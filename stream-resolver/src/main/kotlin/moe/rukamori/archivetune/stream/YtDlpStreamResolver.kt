/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/** Error messages intentionally carry no subprocess output, URLs, headers, cookies or proxy credentials. */
enum class YtDlpFailure {
    TOOLCHAIN, TIMEOUT, AUTH_REQUIRED, UNAVAILABLE, NETWORK, NO_AUDIO, INVALID_OUTPUT, EXTRACTION,
}

class YtDlpException(val kind: YtDlpFailure) : IllegalStateException(
    when (kind) {
        YtDlpFailure.TOOLCHAIN -> "Install compatible yt-dlp with EJS and quickjs-ng, or repair the bundled resolver"
        YtDlpFailure.TIMEOUT -> "YouTube stream resolution timed out"
        YtDlpFailure.AUTH_REQUIRED -> "Sign in is required for this YouTube track"
        YtDlpFailure.UNAVAILABLE -> "This YouTube video is unavailable"
        YtDlpFailure.NETWORK -> "Could not reach YouTube"
        YtDlpFailure.NO_AUDIO -> "YouTube returned no direct audio format"
        YtDlpFailure.INVALID_OUTPUT -> "yt-dlp returned an invalid audio response"
        YtDlpFailure.EXTRACTION -> "yt-dlp could not resolve this YouTube track"
    },
)

data class YtDlpToolchain(val ytDlpVersion: String, val quickJsVersion: String, val ejsVersion: String)

data class YtDlpConfiguration(
    val executable: String = resolverExecutable("yt-dlp", "ARCHIVETUNE_YTDLP"),
    val quickJsExecutable: String = resolverExecutable("qjs", "ARCHIVETUNE_QUICKJS"),
    val timeoutMs: Long = 45_000,
    val temporaryRoot: Path = Path.of(System.getProperty("java.io.tmpdir")),
) {
    init { require(timeoutMs > 0) }
}

data class YtDlpAudioStream(
    val videoId: String,
    val url: String,
    val format: PlayerResponse.StreamingData.Format,
    val videoDetails: PlayerResponse.VideoDetails,
    val headers: Map<String, String>,
    val expiresAtEpochMillis: Long,
)

/** One yt-dlp process per request. Only the successful capability check is retained in memory. */
class YtDlpStreamResolver(private val configuration: YtDlpConfiguration = YtDlpConfiguration()) {
    private val verificationMutex = Mutex()
    @Volatile private var toolchain: YtDlpToolchain? = null

    suspend fun verifyToolchain(): YtDlpToolchain = verificationMutex.withLock {
        toolchain ?: bounded {
            inTemporaryDirectory { directory ->
                // Unsupported local scheme prints startup diagnostics without making a network request.
                // --list-extractors/--version exit before JS and EJS initialization and cannot verify them.
                val output = runResolverProcess(
                    commonArguments() + listOf("--verbose", "--simulate", "--", "archivetune-runtime-check:"),
                    directory,
                )
                parseToolchain(output.stderr).also { toolchain = it }
            }
        }
    }

    suspend fun resolve(
        videoId: String,
        quality: StreamAudioQuality = StreamAudioQuality.HIGH,
        cookie: String? = null,
        proxyUrl: String? = null,
    ): YtDlpAudioStream = bounded {
        require(VIDEO_ID.matches(videoId)) { "A YouTube video ID is required" }
        verifyToolchain()
        inTemporaryDirectory { directory ->
            val arguments = commonArguments().toMutableList()
            if (!cookie.isNullOrBlank()) {
                val cookieFile = directory.resolve("cookies.txt")
                Files.writeString(cookieFile, netscapeCookies(cookie))
                arguments += listOf("--cookies", cookieFile.toString())
            }
            // Keep proxy credentials out of argv/process listings as well as logs.
            if (proxyUrl != null) {
                require(proxyUrl.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid proxy" }
                val configFile = directory.resolve("request.conf")
                Files.writeString(configFile, "--proxy '" + proxyUrl.replace("'", "'\"'\"'") + "'\n")
                arguments += listOf("--config-locations", configFile.toString())
            }
            arguments += listOf(
                "--no-playlist", "--no-progress", "--no-cache-dir", "--socket-timeout", "12",
                "--retries", "1", "--extractor-retries", "1", "--skip-download", "--dump-single-json",
                "--format", formatSelector(quality), "--", "https://www.youtube.com/watch?v=$videoId",
            )
            val output = runResolverProcess(arguments, directory)
            if (output.exitCode != 0) throw YtDlpException(classifyYtDlpError(output.stderr))
            parseYtDlpAudio(output.stdout, videoId)
        }
    }

    private fun commonArguments() = listOf(
        configuration.executable, "--ignore-config", "--no-plugin-dirs", "--no-remote-components",
        "--no-js-runtimes", "--js-runtimes", "quickjs:${configuration.quickJsExecutable}",
    )

    private suspend fun <T> bounded(block: suspend () -> T): T =
        (withTimeoutOrNull(configuration.timeoutMs) { Completed(block()) }
            ?: throw YtDlpException(YtDlpFailure.TIMEOUT)).value

    private data class Completed<T>(val value: T)

    private suspend fun <T> inTemporaryDirectory(block: suspend (Path) -> T): T = withContext(Dispatchers.IO) {
        val attributes = if (Files.getFileStore(configuration.temporaryRoot).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else emptyArray()
        val directory = Files.createTempDirectory(configuration.temporaryRoot, "archivetune-resolver-", *attributes)
        try {
            block(directory)
        } finally {
            // Windows uses the user temp directory's inherited ACL. No credentials go into shared caches.
            directory.toFile().deleteRecursively()
        }
    }
}

internal fun parseToolchain(diagnostics: String): YtDlpToolchain {
    val version = Regex("yt-dlp version [^@\\r\\n]+@([0-9.]+)").find(diagnostics)?.groupValues?.get(1)
    val quickJs = Regex("JS runtimes:.*?quickjs-ng-([0-9.]+)").find(diagnostics)?.groupValues?.get(1)
    val ejs = Regex("yt_dlp_ejs-([0-9.]+)").find(diagnostics)?.groupValues?.get(1)
    if (version == null || quickJs == null || ejs == null) throw YtDlpException(YtDlpFailure.TOOLCHAIN)
    return YtDlpToolchain(version, quickJs, ejs)
}

internal fun formatSelector(quality: StreamAudioQuality): String {
    // Progressive HTTP audio only: downloads/cache range requests cannot consume HLS/DASH manifests.
    val direct = "bestaudio[protocol=https]/bestaudio[protocol=http]"
    val cap = when (quality) { StreamAudioQuality.LOW -> 70; StreamAudioQuality.HIGH -> 160; else -> return direct }
    return "bestaudio[protocol=https][abr<=$cap]/bestaudio[protocol=http][abr<=$cap]/$direct"
}

internal fun netscapeCookies(cookie: String): String = buildString {
    append("# Netscape HTTP Cookie File\n")
    cookie.split(';').forEach { pair ->
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && '=' in pair &&
            value.none { it == '\t' || it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid session cookie" }
        append(".youtube.com\tTRUE\t/\tTRUE\t0\t$name\t$value\n")
    }
}

internal fun classifyYtDlpError(diagnostic: String): YtDlpFailure {
    val text = diagnostic.lowercase(Locale.ROOT)
    return when {
        "sign in" in text || "login required" in text || "private video" in text || "confirm your age" in text -> YtDlpFailure.AUTH_REQUIRED
        "video unavailable" in text || "video is unavailable" in text || "has been removed" in text || "not available in your country" in text -> YtDlpFailure.UNAVAILABLE
        "timed out" in text || "timeout" in text -> YtDlpFailure.TIMEOUT
        "unable to download" in text || "name resolution" in text || "connection refused" in text -> YtDlpFailure.NETWORK
        "requested format is not available" in text -> YtDlpFailure.NO_AUDIO
        "javascript runtime" in text || "ejs" in text || "no such option" in text -> YtDlpFailure.TOOLCHAIN
        else -> YtDlpFailure.EXTRACTION
    }
}

internal fun parseYtDlpAudio(json: String, videoId: String, nowMs: Long = System.currentTimeMillis()): YtDlpAudioStream = try {
    val info = JSONObject(json)
    require(info.getString("id") == videoId)
    require(info.optString("_type", "video") == "video")
    require(!info.has("requested_formats") && !info.has("entries"))
    val url = info.getString("url")
    val uri = URI(url)
    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    require(info.getString("protocol") in setOf("https", "http"))
    require(info.getString("vcodec") == "none")
    val codec = info.getString("acodec").also { require(it.isNotBlank() && it != "none") }
    val extension = info.getString("ext")
    val mime = when (extension) {
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        "ogg", "opus" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        else -> throw YtDlpException(YtDlpFailure.NO_AUDIO)
    }
    val headers = buildMap {
        val raw = info.optJSONObject("http_headers") ?: JSONObject()
        raw.keys().forEach { name ->
            val value = raw.getString(name)
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it == '\r' || it == '\n' || it == '\u0000' })
            if (name.lowercase(Locale.ROOT) !in setOf("cookie", "authorization", "proxy-authorization", "host", "connection", "range")) put(name, value)
        }
    }
    val durationMs = info.numberOrNull("duration")?.times(1000)?.toLong()
    val bitrate = ((info.numberOrNull("abr") ?: info.numberOrNull("tbr") ?: 0.0) * 1000).toInt().coerceAtLeast(0)
    val format = PlayerResponse.StreamingData.Format(
        itag = info.getString("format_id").toIntOrNull() ?: 0, url = url, mimeType = "$mime; codecs=\"$codec\"",
        bitrate = bitrate, width = null, height = null, contentLength = info.numberOrNull("filesize")?.toLong(),
        quality = info.optString("format_note", "audio"), fps = null, qualityLabel = null, averageBitrate = bitrate,
        audioQuality = null, approxDurationMs = durationMs?.toString(), audioSampleRate = info.numberOrNull("asr")?.toInt(),
        audioChannels = info.numberOrNull("audio_channels")?.toInt(), loudnessDb = null,
        lastModified = null, signatureCipher = null, cipher = null,
    )
    val expiry = PlaybackStreamResolver.extractExpiryTimestampMs(url) ?: (nowMs + 300_000)
    require(expiry > nowMs + PlaybackStreamResolver.STREAM_URL_EXPIRY_SAFETY_MS)
    YtDlpAudioStream(
        videoId, url, format,
        PlayerResponse.VideoDetails(videoId = videoId, title = info.optString("title"), author = info.optString("uploader"),
            channelId = info.optString("channel_id"), lengthSeconds = durationMs?.div(1000)?.toString()),
        headers, expiry,
    )
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: YtDlpException) {
    throw failure
} catch (_: Exception) {
    throw YtDlpException(YtDlpFailure.INVALID_OUTPUT)
}

private fun JSONObject.numberOrNull(key: String): Double? = opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

private fun resolverExecutable(name: String, environmentKey: String): String {
    System.getProperty("archivetune.${if (name == "qjs") "quickjs" else "ytdlp"}")?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv(environmentKey)?.takeIf { it.isNotBlank() }?.let { return it }
    val executable = name + if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
    val commandDirectory = ProcessHandle.current().info().command().orElse(null)?.let { File(it).parentFile }
    val roots = listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let(::File),
        commandDirectory, commandDirectory?.parentFile,
        File(System.getProperty("user.dir"), ".tools"),
    )
    return roots.map { File(it, "resolver/$executable") }.firstOrNull { it.isFile }?.absolutePath ?: executable
}

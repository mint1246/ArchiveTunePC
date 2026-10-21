/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ResolverTemporaryFileException : java.io.IOException("Could not manage resolver temporary files")

/** Error messages intentionally carry no subprocess output, URLs, headers, cookies or proxy credentials. */
enum class YtDlpFailure {
    TOOLCHAIN, TIMEOUT, AUTH_REQUIRED, UNAVAILABLE, NETWORK, NO_AUDIO, INVALID_OUTPUT, EXTRACTION, EXPIRED_STREAM,
}

class YtDlpException(val kind: YtDlpFailure) : IllegalStateException(
    when (kind) {
        YtDlpFailure.TOOLCHAIN -> "Install compatible yt-dlp with EJS and Bun 1.4.3+, or repair the bundled resolver"
        YtDlpFailure.TIMEOUT -> "YouTube stream resolution timed out"
        YtDlpFailure.AUTH_REQUIRED -> "Sign in is required for this YouTube track"
        YtDlpFailure.UNAVAILABLE -> "This YouTube video is unavailable"
        YtDlpFailure.NETWORK -> "Could not reach YouTube"
        YtDlpFailure.NO_AUDIO -> "YouTube returned no direct audio format"
        YtDlpFailure.INVALID_OUTPUT -> "yt-dlp returned an invalid audio response"
        YtDlpFailure.EXTRACTION -> "yt-dlp could not resolve this YouTube track"
        YtDlpFailure.EXPIRED_STREAM -> "The resolved YouTube stream expired before playback"
    },
)

data class YtDlpToolchain(val ytDlpVersion: String, val bunVersion: String, val ejsVersion: String)

data class YtDlpConfiguration(
    val executable: String = resolverExecutable("yt-dlp", "ARCHIVETUNE_YTDLP"),
    val bunExecutable: String = resolverExecutable("bun", "ARCHIVETUNE_BUN"),
    val timeoutMs: Long = 35_000,
    val temporaryRoot: Path = Path.of(System.getProperty("java.io.tmpdir")),
    /** Public signature/player cache managed by yt-dlp; cookies and signed URLs stay request-local. */
    val cacheDirectory: Path? = null,
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
    val formatId: String = format.itag.toString(),
)

/** One yt-dlp process per request. Only the successful capability check is retained in memory. */
class YtDlpStreamResolver(private val configuration: YtDlpConfiguration = YtDlpConfiguration()) {
    private val verificationMutex = Mutex()
    @Volatile private var toolchain: YtDlpToolchain? = null
    private val hasSweptTemporaryFiles = AtomicBoolean(false)

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
        dataSyncId: String? = null,
        excludedFormatIds: Set<String> = emptySet(),
    ): YtDlpAudioStream = bounded {
        require(VIDEO_ID.matches(videoId)) { "A YouTube video ID is required" }
        verifyToolchain()
        inTemporaryDirectory { directory ->
            val arguments = commonArguments().toMutableList()
            configuration.cacheDirectory?.let { cache ->
                Files.createDirectories(cache)
                arguments += listOf("--cache-dir", cache.toAbsolutePath().toString())
            }
            if (!cookie.isNullOrBlank()) {
                val cookieFile = directory.resolve("cookies.txt")
                Files.writeString(cookieFile, netscapeCookies(cookie))
                arguments += listOf("--cookies", cookieFile.toString())
            }
            // Keep proxy credentials and the selected account context out of argv/process listings.
            val requestOptions = buildList {
                // Only progressive HTTP(S) audio is accepted by formatSelector. Fetching HLS/DASH
                // manifests cannot contribute a playable format here. Keep yt-dlp's webpage and
                // cookie-aware client selection intact; both are needed for some public videos.
                val youtubeOptions = mutableListOf("skip=hls,dash")
                if (proxyUrl != null) {
                    require(proxyUrl.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid proxy" }
                    add("--proxy ${quoteConfigValue(proxyUrl)}")
                }
                if (!cookie.isNullOrBlank() && !dataSyncId.isNullOrBlank()) {
                    if (dataSyncId.any { it in "\r\n\u0000;,=" }) throw YtDlpException(YtDlpFailure.AUTH_REQUIRED)
                    youtubeOptions += "data_sync_id=$dataSyncId"
                }
                add("--extractor-args ${quoteConfigValue("youtube:" + youtubeOptions.joinToString(";"))}")
            }
            if (requestOptions.isNotEmpty()) {
                val configFile = directory.resolve("request.conf")
                Files.writeString(configFile, requestOptions.joinToString("\n", postfix = "\n"))
                arguments += listOf("--config-locations", configFile.toString())
            }
            arguments += listOf(
                "--no-playlist", "--no-progress", "--socket-timeout", "12",
                "--retries", "1", "--extractor-retries", "1", "--skip-download", "--dump-single-json",
                "--format", formatSelector(quality, excludedFormatIds), "--", "https://www.youtube.com/watch?v=$videoId",
            )
            val output = runResolverProcess(arguments, directory)
            // yt-dlp may exit successfully with fewer formats when a previously verified runtime
            // disappears or its EJS components become unusable. Do not accept that silent downgrade.
            if (hasYtDlpToolchainFailure(output.stderr)) {
                toolchain = null
                throw YtDlpException(YtDlpFailure.TOOLCHAIN)
            }
            if (output.exitCode != 0) throw YtDlpException(classifyYtDlpError(output.stderr))
            parseYtDlpAudio(output.stdout, videoId)
        }
    }

    private fun commonArguments() = listOf(
        absoluteExecutable(configuration.executable), "--ignore-config", "--no-plugin-dirs", "--no-remote-components",
        "--no-js-runtimes", "--js-runtimes", "bun:${absoluteExecutable(configuration.bunExecutable)}",
        "--no-cache-dir",
    )

    private suspend fun <T> bounded(block: suspend () -> T): T =
        (withTimeoutOrNull(configuration.timeoutMs) { Completed(block()) }
            ?: throw YtDlpException(YtDlpFailure.TIMEOUT)).value

    private data class Completed<T>(val value: T)

    private suspend fun <T> inTemporaryDirectory(block: suspend (Path) -> T): T = withContext(Dispatchers.IO) {
        val root = configuration.temporaryRoot.toAbsolutePath().normalize()
        val attributes = if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else emptyArray()
        val directory = Files.createTempDirectory(root, "archivetune-resolver-", *attributes)
        var requestFailure: Throwable? = null
        try {
            val ownerChannel = try {
                FileChannel.open(directory.resolve(".owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (_: Exception) { throw ResolverTemporaryFileException() }
            ownerChannel.use { channel ->
                val ownerLock = try { channel.lock() }
                    catch (_: Exception) { throw ResolverTemporaryFileException() }
                ownerLock.use {
                    if (hasSweptTemporaryFiles.compareAndSet(false, true)) sweepAbandonedRequests(root, directory)
                    block(directory)
                }
            }
        } catch (failure: Throwable) {
            requestFailure = failure
            throw failure
        } finally {
            // Windows uses the user temp directory's inherited ACL. No credentials go into shared caches.
            val removed = withContext(NonCancellable) {
                var deleted = deleteRequestDirectory(directory)
                repeat(5) {
                    if (!deleted) {
                        delay(50) // A forcibly stopped Windows child can briefly retain a file handle.
                        deleted = deleteRequestDirectory(directory)
                    }
                }
                deleted
            }
            if (!removed) {
                val cleanupFailure = ResolverTemporaryFileException()
                requestFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }
}

internal fun parseToolchain(diagnostics: String): YtDlpToolchain {
    val version = Regex("yt-dlp version [^@\\r\\n]+@([0-9.]+)").find(diagnostics)?.groupValues?.get(1)
    val bun = Regex("JS runtimes:.*?\\bbun-([0-9]+\\.[0-9]+\\.[0-9]+)(?:[ ,\\r\\n]|$)").find(diagnostics)?.groupValues?.get(1)
    val ejs = Regex("yt_dlp_ejs-([0-9.]+)").find(diagnostics)?.groupValues?.get(1)
    if (version == null || bun == null || !isSupportedBunVersion(bun) || ejs == null) throw YtDlpException(YtDlpFailure.TOOLCHAIN)
    return YtDlpToolchain(version, bun, ejs)
}

internal fun formatSelector(quality: StreamAudioQuality, excludedFormatIds: Set<String> = emptySet()): String {
    // Progressive HTTP audio only: downloads/cache range requests cannot consume HLS/DASH manifests.
    val direct = "bestaudio[protocol=https]/bestaudio[protocol=http]"
    val cap = when (quality) { StreamAudioQuality.LOW -> 70; StreamAudioQuality.HIGH -> 160; else -> null }
    val preferred = if (cap == null) direct else "bestaudio[protocol=https][abr<=$cap]/bestaudio[protocol=http][abr<=$cap]/$direct"
    if (excludedFormatIds.any { !it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }) throw YtDlpException(YtDlpFailure.INVALID_OUTPUT)
    val exclusions = excludedFormatIds.sorted().joinToString("") { "[format_id!=$it]" }
    return preferred.split('/').joinToString("/") { it + exclusions }
}

internal fun netscapeCookies(cookie: String): String = buildString {
    append("# Netscape HTTP Cookie File\n")
    cookie.split(';').forEach { pair ->
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        if (!(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && '=' in pair &&
            value.none { it == '\t' || it == '\r' || it == '\n' || it == '\u0000' })) throw YtDlpException(YtDlpFailure.AUTH_REQUIRED)
        append(".youtube.com\tTRUE\t/\tTRUE\t0\t$name\t$value\n")
    }
}

internal fun classifyYtDlpError(diagnostic: String): YtDlpFailure {
    val text = diagnostic.lowercase(Locale.ROOT).replace('’', '\'')
    return when {
        listOf("sign in to", "sign-in required", "login required", "private video", "confirm your age", "members-only",
            "join this channel", "members only", "not a bot", "age-restricted").any { it in text } -> YtDlpFailure.AUTH_REQUIRED
        listOf("video unavailable", "video is unavailable", "has been removed", "not available in your country",
            "content isn't available", "content is not available").any { it in text } -> YtDlpFailure.UNAVAILABLE
        "timed out" in text || "timeout" in text -> YtDlpFailure.TIMEOUT
        Regex("""http error\s+\d{3}""").containsMatchIn(text) -> YtDlpFailure.NETWORK
        "unable to download" in text || "name resolution" in text || "connection refused" in text -> YtDlpFailure.NETWORK
        "requested format is not available" in text -> YtDlpFailure.NO_AUDIO
        hasYtDlpToolchainFailure(text) || "no module named 'yt_dlp_ejs'" in text || "no such option" in text -> YtDlpFailure.TOOLCHAIN
        else -> YtDlpFailure.EXTRACTION
    }
}

internal fun hasYtDlpToolchainFailure(diagnostic: String): Boolean {
    val text = diagnostic.lowercase(Locale.ROOT)
    return listOf("no supported javascript runtime", "no usable challenge solver", "failed to load challenge solver",
        "hash mismatch on challenge solver", "error running bun process").any { it in text }
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
        itag = info.getString("format_id").substringBefore('-').toIntOrNull() ?: 0, url = url, mimeType = "$mime; codecs=\"$codec\"",
        bitrate = bitrate, width = null, height = null, contentLength = info.numberOrNull("filesize")?.toLong(),
        quality = info.optString("format_note", "audio"), fps = null, qualityLabel = null, averageBitrate = bitrate,
        audioQuality = null, approxDurationMs = durationMs?.toString(), audioSampleRate = info.numberOrNull("asr")?.toInt(),
        audioChannels = info.numberOrNull("audio_channels")?.toInt(), loudnessDb = null,
        lastModified = null, signatureCipher = null, cipher = null,
    )
    val expiry = PlaybackStreamResolver.extractExpiryTimestampMs(url) ?: (nowMs + 300_000)
    if (expiry <= nowMs + PlaybackStreamResolver.STREAM_URL_EXPIRY_SAFETY_MS) throw YtDlpException(YtDlpFailure.EXPIRED_STREAM)
    YtDlpAudioStream(
        videoId, url, format,
        PlayerResponse.VideoDetails(videoId = videoId, title = info.optString("title"), author = info.optString("uploader"),
            channelId = info.optString("channel_id"), lengthSeconds = durationMs?.div(1000)?.toString()),
        headers, expiry, info.getString("format_id"),
    )
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: YtDlpException) {
    throw failure
} catch (_: Exception) {
    throw YtDlpException(YtDlpFailure.INVALID_OUTPUT)
}

private fun JSONObject.numberOrNull(key: String): Double? = opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
private fun quoteConfigValue(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
private fun deleteRequestDirectory(directory: Path): Boolean = runCatching {
    // Files.walk does not follow symbolic links; a sidecar cannot make cleanup traverse outside this request.
    Files.walk(directory).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    true
}.getOrDefault(false)

private fun sweepAbandonedRequests(root: Path, current: Path) {
    runCatching {
        val owner = Files.getOwner(current, LinkOption.NOFOLLOW_LINKS)
        Files.list(root).use { paths -> paths.forEach { directory ->
            if (directory != current && directory.fileName.toString().startsWith("archivetune-resolver-") &&
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) runCatching {
                if (Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS) != owner) return@runCatching
                // A peer may have created the marker but not acquired its lock yet.
                if (Files.getLastModifiedTime(directory).toMillis() > System.currentTimeMillis() - 60_000) return@runCatching
                val lockPath = directory.resolve(".owner.lock")
                if (Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    // A live JVM holds this lock across extraction. A crash releases it, allowing
                    // the next resolver instance to discard cookies without touching active peers.
                    val abandoned = FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
                        channel.tryLock()?.use { true } ?: false
                    }
                    if (abandoned) deleteRequestDirectory(directory)
                } else if (Files.getLastModifiedTime(directory).toMillis() < System.currentTimeMillis() - 86_400_000) {
                    // Older versions had no lock. Do not race a just-created request directory.
                    deleteRequestDirectory(directory)
                }
            }
        } }
    }
}
// Bun 1.4 is the Rust rewrite; older PATH installations must not silently select the old runtime.
private fun isSupportedBunVersion(version: String): Boolean {
    val parts = version.split('.').map { it.toIntOrNull() ?: return false }
    return parts.size == 3 && (parts[0] > 1 || parts[0] == 1 && (parts[1] > 4 || parts[1] == 4 && parts[2] >= 3))
}

// Preserve explicit relative overrides when the child starts in its private request directory.
private fun absoluteExecutable(path: String): String =
    if ('/' in path || '\\' in path) File(path).absolutePath else path

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

private fun resolverExecutable(name: String, environmentKey: String): String {
    System.getProperty("archivetune.${if (name == "bun") "bun" else "ytdlp"}")?.takeIf { it.isNotBlank() }?.let { return it }
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

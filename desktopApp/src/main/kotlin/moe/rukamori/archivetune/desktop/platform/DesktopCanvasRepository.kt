/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.ArchiveTuneCanvas
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.canvas.SpotifyCanvasProvider
import moe.rukamori.archivetune.canvas.TidalCanvasProvider
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.canvas.models.matchesSongIdentity
import moe.rukamori.archivetune.desktop.settings.DesktopCanvasSource
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.spotify.SpotifyAuth
import moe.rukamori.archivetune.spotify.models.SpotifyInternalToken
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/** Canvas artwork currently selected for the playing item. A null video URL means use its poster. */
data class DesktopCanvasState(
    val mediaId: String? = null,
    val artwork: CanvasArtwork? = null,
    val videoUri: String? = null,
    val isLoading: Boolean = false,
    val failed: Boolean = false,
)

/** Test seam for provider lookup; production lookup uses the upstream Canvas providers. */
fun interface DesktopCanvasArtworkResolver {
    suspend fun resolve(title: String, artist: String, source: DesktopCanvasSource): CanvasArtwork?
}

/**
 * Desktop Canvas lookup and video cache. Provider calls and video transfers run off the UI thread;
 * stale lookups are cancelled when the playing item or Canvas settings change.
 */
class DesktopCanvasRepository(
    private val settings: DesktopSettingsStore,
    cacheDirectory: Path,
    private val scope: CoroutineScope,
    private val artworkResolver: DesktopCanvasArtworkResolver? = null,
) : AutoCloseable {
    private val cacheDirectory = cacheDirectory.toAbsolutePath().normalize()
    private val mutableState = MutableStateFlow(DesktopCanvasState())
    val state = mutableState.asStateFlow()

    private val generation = AtomicLong()
    private var lookupJob: Job? = null
    private var videoCacheJob: Job? = null
    private var currentRequest: CanvasRequest? = null
    private var spotifyToken: SpotifyInternalToken? = null

    /**
     * Begin resolving Canvas for the current track. Call again when the track changes or when the
     * user changes the Canvas enabled/provider/cache settings.
     */
    fun load(
        mediaId: String,
        title: String,
        artist: String,
        fallbackArtworkUri: String? = null,
    ) {
        val request = CanvasRequest(
            mediaId = mediaId.trim(),
            title = title.trim(),
            artist = artist.trim(),
            fallbackArtworkUri = fallbackArtworkUri,
        )
        currentRequest = request
        lookupJob?.cancel()
        videoCacheJob?.cancel()
        val version = generation.incrementAndGet()
        val configuration = settings.snapshot()

        if (!configuration.desktopCanvasEnabled || request.mediaId.isBlank() || request.title.isBlank()) {
            mutableState.value = DesktopCanvasState(mediaId = request.mediaId.takeIf(String::isNotBlank))
            return
        }

        mutableState.value = DesktopCanvasState(mediaId = request.mediaId, isLoading = true)
        lookupJob = scope.launch(Dispatchers.IO) {
            try {
                pruneCache(configuration.desktopCanvasCacheLimitMb)
                val cached = readCachedArtwork(request, configuration.desktopCanvasSource)
                val artwork = cached?.artwork ?: if (artworkResolver != null) {
                    artworkResolver.resolve(request.title, request.artist, configuration.desktopCanvasSource)
                } else {
                    resolveArtwork(request, configuration.desktopCanvasSource)
                }
                if (generation.get() != version) return@launch
                if (artwork == null) {
                    mutableState.value = DesktopCanvasState(mediaId = request.mediaId, failed = true)
                    return@launch
                }

                val cachedVideo = cached?.video?.takeIf { Files.isRegularFile(it) }
                val remoteVideo = if (cachedVideo != null) null else artwork.preferredAnimationUrl
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                if (cachedVideo == null && remoteVideo == null) {
                    mutableState.value = DesktopCanvasState(mediaId = request.mediaId, failed = true)
                    return@launch
                }

                val selectedVideoUri = cachedVideo?.toUriString() ?: requireNotNull(remoteVideo)
                val selectedArtwork = artwork.withVideoUri(selectedVideoUri)
                mutableState.value = DesktopCanvasState(
                    mediaId = request.mediaId,
                    artwork = selectedArtwork,
                    videoUri = selectedVideoUri,
                )
                if (cachedVideo != null) {
                    touch(cachedVideo)
                    touch(metadataFile(request.mediaId))
                } else if (remoteVideo != null) {
                    videoCacheJob = scope.launch(Dispatchers.IO) {
                        val downloaded = downloadVideo(request, artwork, remoteVideo, configuration.desktopCanvasCacheLimitMb)
                        if (downloaded != null && generation.get() == version) {
                            persistArtworkMetadata(request, artwork, downloaded)
                            mutableState.value = mutableState.value.takeIf { it.mediaId == request.mediaId }
                                ?.copy(artwork = artwork.withVideoUri(downloaded.toUriString()), videoUri = downloaded.toUriString())
                                ?: mutableState.value
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.fine("Desktop Canvas lookup failed (${error.javaClass.simpleName})")
                if (generation.get() == version) {
                    mutableState.value = DesktopCanvasState(mediaId = request.mediaId, failed = true)
                }
            }
        }
    }

    /** Refresh the current track after a settings change without waiting for a track transition. */
    fun refresh() {
        val request = currentRequest ?: return
        load(request.mediaId, request.title, request.artist, request.fallbackArtworkUri)
    }

    fun clear() {
        generation.incrementAndGet()
        lookupJob?.cancel()
        videoCacheJob?.cancel()
        currentRequest = null
        mutableState.value = DesktopCanvasState()
    }

    override fun close() {
        generation.incrementAndGet()
        lookupJob?.cancel()
        videoCacheJob?.cancel()
        currentRequest = null
        mutableState.value = DesktopCanvasState()
    }

    private suspend fun resolveArtwork(request: CanvasRequest, selected: DesktopCanvasSource): CanvasArtwork? {
        val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
        val sourceOrder = when (selected) {
            DesktopCanvasSource.ALL -> listOf(
                CanvasSource.BETTER_LYRICS,
                CanvasSource.APPLE_MUSIC,
                CanvasSource.TIDAL,
                CanvasSource.SPOTIFY,
            )
            DesktopCanvasSource.BETTER_LYRICS -> listOf(CanvasSource.BETTER_LYRICS)
            DesktopCanvasSource.APPLE_MUSIC -> listOf(CanvasSource.APPLE_MUSIC)
            DesktopCanvasSource.TIDAL -> listOf(CanvasSource.TIDAL)
            DesktopCanvasSource.SPOTIFY -> listOf(CanvasSource.SPOTIFY)
        }
        val titleCandidates = linkedSetOf(request.title, cleanSongTitle(request.title))
            .filter(String::isNotBlank)
        val artistCandidates = linkedSetOf(request.artist, request.artist.substringBefore(',').trim())
            .filter(String::isNotBlank)

        for (source in sourceOrder) {
            for (candidateTitle in titleCandidates) {
                for (candidateArtist in artistCandidates) {
                    val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        when (source) {
                            CanvasSource.BETTER_LYRICS -> ArchiveTuneCanvas.getBySongArtist(
                                song = candidateTitle,
                                artist = candidateArtist,
                                storefront = storefront,
                                source = CanvasSource.BETTER_LYRICS,
                            )
                            CanvasSource.APPLE_MUSIC -> AppleMusicProvider.getBySongArtist(
                                song = candidateTitle,
                                artist = candidateArtist,
                                storefront = storefront,
                            )?.copy(source = CanvasSource.APPLE_MUSIC)
                            CanvasSource.TIDAL -> TidalCanvasProvider.getBySongArtist(
                                song = candidateTitle,
                                artist = candidateArtist,
                                storefront = storefront,
                            )
                            CanvasSource.SPOTIFY -> {
                                val token = getSpotifyToken()
                                SpotifyCanvasProvider.getBySongArtist(
                                    song = candidateTitle,
                                    artist = candidateArtist,
                                    accessToken = token.accessToken,
                                    clientId = token.clientId,
                                )
                            }
                            CanvasSource.ALL -> null
                        }
                    }
                    if (result != null && result.matchesSongIdentity(request.title, request.artist) &&
                        !result.preferredAnimationUrl.isNullOrBlank()
                    ) return result
                }
            }
        }
        return null
    }

    private suspend fun getSpotifyToken(): SpotifyInternalToken {
        val current = spotifyToken
        if (current != null && current.accessTokenExpirationTimestampMs > System.currentTimeMillis() + 30_000L) {
            return current
        }
        return SpotifyAuth.fetchAnonymousAccessToken().getOrThrow().also { spotifyToken = it }
    }

    private fun readCachedArtwork(request: CanvasRequest, selected: DesktopCanvasSource): CachedCanvas? {
        val metadataFile = metadataFile(request.mediaId)
        if (!Files.isRegularFile(metadataFile)) return null
        return runCatching {
            val properties = Properties().apply { Files.newInputStream(metadataFile).use(::load) }
            if (properties.getProperty("cachedAt")?.toLongOrNull() == null) return null
            val source = properties.getProperty("source")?.let { runCatching { CanvasSource.valueOf(it) }.getOrNull() }
                ?: return null
            if (selected != DesktopCanvasSource.ALL && source != selected.toCanvasSource()) return null
            val name = properties.getProperty("name").orEmpty()
            val artist = properties.getProperty("artist").orEmpty()
            if (!CanvasArtwork(source = source, name = name, artist = artist)
                    .matchesSongIdentity(request.title, request.artist)
            ) return null
            val videoName = properties.getProperty("videoFile") ?: return null
            val videoFile = cacheDirectory.resolve(videoName).normalize()
            if (videoFile.parent != cacheDirectory || !Files.isRegularFile(videoFile)) return null
            val artwork = CanvasArtwork(
                source = source,
                name = name,
                artist = artist,
                albumId = properties.getProperty("albumId"),
                albumName = properties.getProperty("albumName"),
                animated = videoFile.toUri().toString(),
            )
            CachedCanvas(artwork, videoFile)
        }.getOrNull()
    }

    private fun persistArtworkMetadata(request: CanvasRequest, artwork: CanvasArtwork, localVideo: Path) {
        val file = metadataFile(request.mediaId)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        runCatching {
            Files.createDirectories(cacheDirectory)
            val properties = Properties().apply {
                setProperty("cachedAt", System.currentTimeMillis().toString())
                setProperty("source", artwork.source?.name ?: CanvasSource.ALL.name)
                setIfNotNull("name", artwork.name)
                setIfNotNull("artist", artwork.artist)
                setIfNotNull("albumId", artwork.albumId)
                setIfNotNull("albumName", artwork.albumName)
                setProperty("videoFile", localVideo.fileName.toString())
            }
            Files.newOutputStream(temporary).use { output -> properties.store(output, null) }
            moveAtomically(temporary, file)
            pruneCache(settings.snapshot().desktopCanvasCacheLimitMb)
        }.onFailure { logger.fine("Could not persist desktop Canvas metadata (${it.javaClass.simpleName})") }
    }

    private suspend fun downloadVideo(
        request: CanvasRequest,
        artwork: CanvasArtwork,
        remoteUri: String,
        limitMb: Int,
    ): Path? = withContext(Dispatchers.IO) {
        val boundedLimitMb = if (limitMb < 0) MAX_CACHE_LIMIT_MB else limitMb.coerceAtMost(MAX_CACHE_LIMIT_MB)
        val destination = videoFile(request.mediaId, artwork.source?.name.orEmpty())
        if (Files.isRegularFile(destination) && runCatching { Files.size(destination) }.getOrDefault(0L) > 0L) {
            touch(destination)
            return@withContext destination
        }
        if (boundedLimitMb == 0 || !remoteUri.isCacheableCanvasVideo()) return@withContext null

        var connection: HttpURLConnection? = null
        val temporary = destination.resolveSibling("${destination.fileName}.part")
        try {
            Files.createDirectories(cacheDirectory)
            val opened = URI(remoteUri).toURL().openConnection() as? HttpURLConnection
                ?: return@withContext null
            connection = opened
            currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause is CancellationException) opened.disconnect()
            }
            opened.connectTimeout = CONNECT_TIMEOUT_MS
            opened.readTimeout = READ_TIMEOUT_MS
            opened.instanceFollowRedirects = true
            opened.setRequestProperty("User-Agent", CANVAS_USER_AGENT)
            val status = opened.responseCode
            if (status !in 200..299) throw IOException("Canvas video download returned HTTP $status")
            val type = opened.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            if (type.isNotEmpty() && type !in ACCEPTED_VIDEO_TYPES) return@withContext null
            val contentLength = opened.contentLengthLong
            if (contentLength > MAX_CANVAS_VIDEO_BYTES || contentLength > boundedLimitMb.toLong() * MEGABYTE) {
                return@withContext null
            }
            opened.inputStream.buffered().use { input ->
                BufferedOutputStream(Files.newOutputStream(temporary)).use { output ->
                    val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_CANVAS_VIDEO_BYTES || total > boundedLimitMb.toLong() * MEGABYTE) {
                            throw IOException("Canvas video exceeded its cache allowance")
                        }
                        output.write(buffer, 0, count)
                    }
                    if (total == 0L) throw IOException("Canvas video download was empty")
                }
            }
            moveAtomically(temporary, destination)
            pruneCache(boundedLimitMb, destination)
            destination
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.fine("Could not cache desktop Canvas video (${error.javaClass.simpleName})")
            null
        } finally {
            connection?.disconnect()
            Files.deleteIfExists(temporary)
        }
    }

    private fun pruneCache(limitMb: Int, keep: Path? = null) {
        Files.createDirectories(cacheDirectory)
        val files = Files.list(cacheDirectory).use { entries ->
            entries.filter { Files.isRegularFile(it) && (it.fileName.toString().endsWith(".mp4") || it.fileName.toString().endsWith(".properties")) }
                .map { path -> path to runCatching { Files.size(path) }.getOrDefault(0L) }
                .toList()
                .sortedBy { (path, _) -> runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L) }
        }
        var total = files.sumOf { it.second }
        // Older settings used -1 for unlimited; keep that value bounded for safety.
        val boundedLimitMb = if (limitMb < 0) MAX_CACHE_LIMIT_MB else limitMb.coerceAtMost(MAX_CACHE_LIMIT_MB)
        val maximumBytes = boundedLimitMb.toLong() * MEGABYTE
        for ((path, size) in files) {
            if (total <= maximumBytes) break
            if (path == keep) continue
            if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) total -= size
        }
    }

    private fun metadataFile(mediaId: String): Path = cacheDirectory.resolve("${hash(mediaId)}.properties")

    private fun videoFile(mediaId: String, source: String): Path =
        cacheDirectory.resolve("${hash("$mediaId|$source")}.mp4")

    private fun touch(file: Path) {
        runCatching { Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
    }

    private fun moveAtomically(source: Path, destination: Path) {
        runCatching { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun CanvasArtwork.withVideoUri(uri: String): CanvasArtwork = copy(videoUrl = uri, animated = uri)

    private fun String.isCacheableCanvasVideo(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        return (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            path.endsWith(".mp4") && !path.endsWith(".m3u8")
    }

    private fun Path.toUriString(): String = toAbsolutePath().normalize().toUri().toString()

    private fun Properties.setIfNotNull(key: String, value: String?) {
        if (value != null) setProperty(key, value)
    }

    private fun DesktopCanvasSource.toCanvasSource(): CanvasSource = when (this) {
        DesktopCanvasSource.ALL -> CanvasSource.ALL
        DesktopCanvasSource.BETTER_LYRICS -> CanvasSource.BETTER_LYRICS
        DesktopCanvasSource.APPLE_MUSIC -> CanvasSource.APPLE_MUSIC
        DesktopCanvasSource.TIDAL -> CanvasSource.TIDAL
        DesktopCanvasSource.SPOTIFY -> CanvasSource.SPOTIFY
    }

    private fun cleanSongTitle(value: String): String = value
        .replace(Regex("\\s*\\[[^]]*]"), "")
        .replace(Regex("\\s*\\((?:official\\s*)?(?:music\\s*)?(?:video|lyrics?|audio|live|remaster(?:ed)?|version)[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private data class CanvasRequest(
        val mediaId: String,
        val title: String,
        val artist: String,
        val fallbackArtworkUri: String?,
    )

    private data class CachedCanvas(val artwork: CanvasArtwork, val video: Path)

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 18_000L
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 25_000
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        const val MAX_CANVAS_VIDEO_BYTES = 128L * 1024 * 1024
        const val MAX_CACHE_LIMIT_MB = 8_192
        const val MEGABYTE = 1024L * 1024L
        val ACCEPTED_VIDEO_TYPES = setOf("video/mp4", "application/octet-stream", "video/quicktime")
        const val CANVAS_USER_AGENT = "ArchiveTune Desktop/15"
        val logger: Logger = Logger.getLogger("ArchiveTune.Canvas")
    }
}

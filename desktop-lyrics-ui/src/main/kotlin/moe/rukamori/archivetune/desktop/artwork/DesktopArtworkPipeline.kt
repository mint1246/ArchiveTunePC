package moe.rukamori.archivetune.desktop.artwork

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Small decoded artwork shared by desktop dynamic-colour consumers. */
data class DesktopArtworkImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(argb.size == width * height)
    }

    fun toBufferedImage(): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
            image.setRGB(0, 0, width, height, argb, 0, width)
        }
}

internal data class DesktopArtworkSource(
    val cacheKey: String,
    val location: String,
    val kind: Kind,
) {
    enum class Kind { HTTP, FILE }
}

/**
 * Bounded app-lifetime cache for the small decoded artwork used by dynamic palette/background.
 * In-flight work is single-flighted by normalized artwork identity, so simultaneous consumers do
 * not perform duplicate downloads or source decodes.
 */
internal class DesktopArtworkPipeline(
    private val maxEntries: Int = DEFAULT_CACHE_ENTRIES,
    private val loader: suspend (DesktopArtworkSource) -> DesktopArtworkImage? = ::loadDesktopArtworkSource,
) : AutoCloseable {
    private data class InFlight(
        val deferred: Deferred<DesktopArtworkImage?>,
        var waiters: Int = 0,
    )

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, DesktopArtworkImage>(maxEntries.coerceAtLeast(1), 0.75f, true)
    private val inFlight = HashMap<String, InFlight>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun load(artworkUri: String?): DesktopArtworkImage? {
        val source = resolveDesktopArtworkSource(artworkUri) ?: return null
        var cached: DesktopArtworkImage? = null
        lateinit var flight: InFlight
        mutex.withLock {
            cached = cache[source.cacheKey]
            if (cached == null) {
                flight = inFlight[source.cacheKey] ?: InFlight(scope.async { loader(source) }).also {
                    inFlight[source.cacheKey] = it
                }
                flight.waiters++
            }
        }
        cached?.let { return it }

        return try {
            val result = flight.deferred.await()
            if (result != null) {
                mutex.withLock {
                    cache[source.cacheKey] = result
                    while (cache.size > maxEntries.coerceAtLeast(1)) {
                        val eldest = cache.entries.iterator()
                        if (!eldest.hasNext()) break
                        eldest.next()
                        eldest.remove()
                    }
                }
            }
            result
        } finally {
            mutex.withLock {
                if (inFlight[source.cacheKey] === flight) {
                    flight.waiters--
                    if (flight.deferred.isCompleted) {
                        inFlight.remove(source.cacheKey)
                    } else if (flight.waiters <= 0) {
                        // No UI consumer still needs this request. Cancelling the shared deferred
                        // propagates into HttpClient's future via awaitCancellable().
                        inFlight.remove(source.cacheKey)
                        flight.deferred.cancel()
                    }
                }
            }
        }
    }

    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

private val sharedDesktopArtworkPipeline = DesktopArtworkPipeline()

suspend fun loadDesktopArtwork(artworkUri: String?): DesktopArtworkImage? =
    sharedDesktopArtworkPipeline.load(artworkUri)

fun desktopArtworkCacheKey(artworkUri: String?): String? =
    resolveDesktopArtworkSource(artworkUri)?.cacheKey

internal fun resolveDesktopArtworkSource(artworkUri: String?): DesktopArtworkSource? {
    val raw = artworkUri?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (raw.startsWith("spotify:image:")) {
        val location = "https://i.scdn.co/image/" + raw.removePrefix("spotify:image:")
        return DesktopArtworkSource(location, location, DesktopArtworkSource.Kind.HTTP)
    }
    if (raw.startsWith("http://") || raw.startsWith("https://")) {
        return DesktopArtworkSource(
            cacheKey = normalizeRemoteArtworkKey(raw),
            location = canonicalRemoteArtworkLocation(raw),
            kind = DesktopArtworkSource.Kind.HTTP,
        )
    }
    val path = runCatching {
        if (raw.startsWith("file:")) Path.of(URI(raw)) else Path.of(raw)
    }.getOrNull() ?: return null
    val absolute = path.toAbsolutePath().normalize()
    return DesktopArtworkSource("file:$absolute", absolute.toString(), DesktopArtworkSource.Kind.FILE)
}

internal fun normalizeRemoteArtworkKey(uri: String): String {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return uri
    val host = parsed.host?.lowercase() ?: return uri
    if (!isGoogleArtworkHost(host)) return uri

    // Google artwork CDNs encode requested dimensions in the path. Preserve every other token
    // (crop/source family/signature) and normalize only the size component.
    return uri
        .replace(GOOGLE_WIDTH_HEIGHT, "w#-h#")
        .replace(GOOGLE_SQUARE_SIZE, "=s#")
}

internal fun canonicalRemoteArtworkLocation(uri: String): String {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return uri
    val host = parsed.host?.lowercase() ?: return uri
    if (!isGoogleArtworkHost(host)) return uri
    return uri
        .replace(GOOGLE_WIDTH_HEIGHT, "w$CANONICAL_REMOTE_SIZE-h$CANONICAL_REMOTE_SIZE")
        .replace(GOOGLE_SQUARE_SIZE, "=s$CANONICAL_REMOTE_SIZE")
}

private fun isGoogleArtworkHost(host: String): Boolean =
    host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") ||
        host == "ggpht.com" || host.endsWith(".ggpht.com")

private suspend fun loadDesktopArtworkSource(source: DesktopArtworkSource): DesktopArtworkImage? =
    try {
        val encoded = when (source.kind) {
            DesktopArtworkSource.Kind.HTTP -> fetchRemoteArtwork(source.location)
            DesktopArtworkSource.Kind.FILE -> fetchFileArtwork(Path.of(source.location))
        } ?: return null
        decodeArtworkSmall(encoded)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

private suspend fun fetchRemoteArtwork(uri: String): ByteArray? {
    val request = HttpRequest.newBuilder(URI(uri))
        .timeout(Duration.ofSeconds(20))
        .header("User-Agent", "ArchiveTune/desktop-artwork")
        .GET()
        .build()
    val future = ARTWORK_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
    val response = future.awaitCancellable()
    if (response.statusCode() !in 200..299) {
        response.body().close()
        return null
    }
    val declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
    if (declared > MAX_ENCODED_ARTWORK_BYTES) {
        response.body().close()
        return null
    }
    return response.body().use { readBoundedArtworkBytes(it, MAX_ENCODED_ARTWORK_BYTES) }
}

private suspend fun fetchFileArtwork(path: Path): ByteArray? {
    if (!Files.isRegularFile(path)) return null
    val size = runCatching { Files.size(path) }.getOrDefault(-1L)
    if (size > MAX_ENCODED_ARTWORK_BYTES) return null
    return withContext(Dispatchers.IO) {
        Files.newInputStream(path).use { readBoundedArtworkBytes(it, MAX_ENCODED_ARTWORK_BYTES) }
    }
}

internal suspend fun readBoundedArtworkBytes(input: InputStream, maxBytes: Int): ByteArray? {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun decodeArtworkSmall(encoded: ByteArray, targetMaxDimension: Int = DECODE_TARGET_MAX_DIMENSION): DesktopArtworkImage? {
    if (encoded.isEmpty()) return null
    return runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(encoded)).use { input ->
            if (input == null) return@runCatching null
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.input = input
                val sourceWidth = reader.getWidth(0)
                val sourceHeight = reader.getHeight(0)
                if (sourceWidth <= 0 || sourceHeight <= 0) return@runCatching null
                if (sourceWidth > MAX_SOURCE_DIMENSION || sourceHeight > MAX_SOURCE_DIMENSION) return@runCatching null

                val decodeCeiling = targetMaxDimension.coerceAtLeast(1) * 2
                val largest = maxOf(sourceWidth, sourceHeight)
                val subsample = maxOf(1, (largest + decodeCeiling - 1) / decodeCeiling)
                val parameter = reader.defaultReadParam
                if (subsample > 1) parameter.setSourceSubsampling(subsample, subsample, 0, 0)
                val decoded = reader.read(0, parameter) ?: return@runCatching null

                val scale = minOf(1.0, targetMaxDimension.toDouble() / maxOf(decoded.width, decoded.height).toDouble())
                val width = maxOf(1, (decoded.width * scale).toInt())
                val height = maxOf(1, (decoded.height * scale).toInt())
                val finalImage = if (width == decoded.width && height == decoded.height) {
                    decoded
                } else {
                    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { target ->
                        target.createGraphics().use { graphics ->
                            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                            graphics.drawImage(decoded, 0, 0, width, height, null)
                        }
                    }
                }
                try {
                    val pixels = IntArray(width * height)
                    finalImage.getRGB(0, 0, width, height, pixels, 0, width)
                    DesktopArtworkImage(width, height, pixels)
                } finally {
                    if (finalImage !== decoded) finalImage.flush()
                    decoded.flush()
                }
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()
}

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error != null) continuation.resumeWithException(error)
        else continuation.resume(value)
    }
    continuation.invokeOnCancellation { cancel(true) }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        dispose()
    }

private val GOOGLE_WIDTH_HEIGHT = Regex("w\\d+-h\\d+")
private val GOOGLE_SQUARE_SIZE = Regex("=s\\d+")
private const val DEFAULT_CACHE_ENTRIES = 12
private const val MAX_ENCODED_ARTWORK_BYTES = 6 * 1024 * 1024
private const val DECODE_TARGET_MAX_DIMENSION = 256
private const val MAX_SOURCE_DIMENSION = 16_384
private const val CANONICAL_REMOTE_SIZE = 320
private val ARTWORK_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(10))
    .build()

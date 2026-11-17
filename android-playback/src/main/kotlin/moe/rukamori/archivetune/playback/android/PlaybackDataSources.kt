package moe.rukamori.archivetune.playback.android

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import java.util.Locale

/** Keeps the stable media identity in the queue so resolution can happen when Media3 opens it. */
@UnstableApi
fun MediaItem.Builder.setPlaybackAddress(mediaId: String, cacheKey: String?): MediaItem.Builder =
    setMediaId(mediaId).setUri(mediaId).setCustomCacheKey(cacheKey)

/** Resolves each deferred request on Media3's loading thread, including automatic queue advances. */
@UnstableApi
fun resolvingPlaybackUpstream(
    context: Context,
    httpFactory: DataSource.Factory,
    chunkLength: Long,
    resolveDeferred: (request: DataSpec, mediaId: String) -> DataSpec,
): DataSource.Factory {
    val chunkedFactory = DataSource.Factory { ChunkedDataSource(httpFactory.createDataSource(), chunkLength) }
    return ResolvingDataSource.Factory(DefaultDataSource.Factory(context, chunkedFactory)) { request ->
        val mediaId = request.key
        if (mediaId == null || request.uri.bypassesDeferredPlaybackResolution()) request else resolveDeferred(request, mediaId)
    }
}

/** Chooses the source at open time so cache policy changes also apply to already queued items. */
@UnstableApi
fun cachedPlaybackDataSourceFactory(
    upstream: DataSource.Factory,
    playerCache: Cache,
    downloadCache: Cache,
    bypassCache: (String) -> Boolean,
    cacheWritesEnabled: () -> Boolean,
    onCacheableRequest: (String) -> Unit = {},
): DataSource.Factory {
    val downloadedFactory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setCacheWriteDataSinkFactory(null)
    val streamingFactory = DataSource.Factory {
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(upstream)
            .apply { if (!cacheWritesEnabled()) setCacheWriteDataSinkFactory(null) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }
    val isDownloaded = downloadCache::isFullyCached
    return DataSource.Factory {
        RoutingDataSource { request ->
            when (playbackRoute(request.uri.scheme, request.key, bypassCache, onCacheableRequest, isDownloaded)) {
                PlaybackRoute.DIRECT -> upstream
                PlaybackRoute.DOWNLOAD -> downloadedFactory
                PlaybackRoute.STREAM -> streamingFactory
            }
        }
    }
}

@UnstableApi
fun Cache.isFullyCached(mediaId: String): Boolean {
    val contentLength = ContentMetadata.getContentLength(getContentMetadata(mediaId))
    return contentLength > 0L && isCached(mediaId, 0L, contentLength)
}

fun Uri.bypassesPlaybackCache(): Boolean = isLocalPlaybackScheme(scheme?.lowercase(Locale.US))

fun Uri.bypassesDeferredPlaybackResolution(): Boolean = !isDeferredPlaybackScheme(scheme)

internal fun isDeferredPlaybackScheme(scheme: String?): Boolean {
    val normalized = scheme?.lowercase(Locale.US)
    return normalized != "http" && normalized != "https" && !isLocalPlaybackScheme(normalized)
}

private fun isLocalPlaybackScheme(scheme: String?): Boolean = when (scheme) {
    "content", "file", "android.resource" -> true
    else -> false
}

internal enum class PlaybackRoute { DIRECT, DOWNLOAD, STREAM }

internal fun playbackRoute(
    scheme: String?,
    mediaId: String?,
    bypassCache: (String) -> Boolean,
    onCacheableRequest: (String) -> Unit,
    isDownloaded: (String) -> Boolean,
): PlaybackRoute {
    if (isLocalPlaybackScheme(scheme?.lowercase(Locale.US)) || (mediaId != null && bypassCache(mediaId))) return PlaybackRoute.DIRECT
    if (mediaId == null) return PlaybackRoute.STREAM
    onCacheableRequest(mediaId)
    return if (isDownloaded(mediaId)) PlaybackRoute.DOWNLOAD else PlaybackRoute.STREAM
}

@UnstableApi
private class RoutingDataSource(private val selectFactory: (DataSpec) -> DataSource.Factory) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val selected = selectFactory(dataSpec).createDataSource()
        transferListeners.forEach(selected::addTransferListener)
        delegate = selected
        return selected.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val selected = delegate
        delegate = null
        selected?.close()
    }
}

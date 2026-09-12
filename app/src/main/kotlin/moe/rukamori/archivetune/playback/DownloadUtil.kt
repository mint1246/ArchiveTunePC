/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloaderFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.downloads.DownloadedArtworkRepository
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.utils.AuthScopedCacheValue
import moe.rukamori.archivetune.utils.StreamClientUtils
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.enumPreference
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.retryWithoutPlaybackLoginContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val database: MusicDatabase,
        val databaseProvider: DatabaseProvider,
        @DownloadCache val downloadCache: Cache,
        @PlayerCache val playerCache: Cache,
        private val downloadedArtworkRepository: DownloadedArtworkRepository,
    ) {
        private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
        private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val songUrlCache = ConcurrentHashMap<String, AuthScopedCacheValue>()
        private val playbackCacheReuseIds = ConcurrentHashMap.newKeySet<String>()
        private val downloadExecutor = Executors.newFixedThreadPool(DEFAULT_MAX_PARALLEL_DOWNLOADS)
        private val artworkJobs = mutableMapOf<String, Job>()

        private val mediaOkHttpClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .proxy(YouTube.streamOkHttpProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = MAX_DOWNLOAD_HTTP_REQUESTS
                        maxRequestsPerHost = DEFAULT_MAX_PARALLEL_DOWNLOADS
                    },
                ).connectionPool(
                    ConnectionPool(
                        MAX_IDLE_DOWNLOAD_CONNECTIONS,
                        DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES,
                        TimeUnit.MINUTES,
                    ),
                ).addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val isYouTubeMediaHost =
                        host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                    if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                    val requestProfile = StreamClientUtils.resolveRequestProfile(request.url)
                    val response = chain.proceed(
                        StreamClientUtils
                            .applyRequestProfile(
                                request.newBuilder(),
                                requestProfile,
                            ).build(),
                    )
                    if (response.code in STREAM_REFRESH_RESPONSE_CODES) {
                        invalidateResolvedStreamUrl(request.url.toString())
                    }
                    response
                }.build()
        }

        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        private val resolvedNetworkDataSourceFactory =
            ResolvingDataSource.Factory(
                OkHttpDataSource.Factory(mediaOkHttpClient),
                ::resolveDownloadDataSpec,
            )

        private val playbackCacheDataSourceFactory =
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(resolvedNetworkDataSourceFactory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

        private val downloadUpstreamDataSourceFactory =
            DataSource.Factory {
                CompletePlaybackCacheDataSource(
                    downloadCache = downloadCache,
                    playerCache = playerCache,
                    playbackCacheDataSourceFactory = playbackCacheDataSourceFactory,
                    networkDataSourceFactory = resolvedNetworkDataSourceFactory,
                    playbackCacheReuseIds = playbackCacheReuseIds,
                )
            }

        val downloadNotificationHelper =
            DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

        private val downloaderFactory =
            DefaultDownloaderFactory(
                CacheDataSource
                    .Factory()
                    .setCache(downloadCache)
                    .setUpstreamDataSourceFactory(downloadUpstreamDataSourceFactory)
                    .setCacheWriteDataSinkFactory(
                        CacheDataSink.Factory()
                            .setCache(downloadCache)
                            .setBufferSize(DOWNLOAD_WRITE_BUFFER_SIZE),
                    ).setFlags(FLAG_IGNORE_CACHE_ON_ERROR),
                downloadExecutor,
            )

        val downloadManager: DownloadManager =
            DownloadManager(
                context,
                DefaultDownloadIndex(databaseProvider),
                DownloaderFactory { request ->
                    ResumingDownloader(request, downloaderFactory, ::resetDownloadContent)
                },
            ).apply {
                maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
                minRetryCount = DOWNLOAD_MIN_RETRY_COUNT
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            downloads.update { map ->
                                map.toMutableMap().apply {
                                    set(download.request.id, download.toProgressSnapshot())
                                }
                            }
                            if (download.state == Download.STATE_COMPLETED) {
                                scheduleDownloadedArtwork(download.request.id)
                                playbackCacheReuseIds.remove(download.request.id)
                            } else if (download.state == Download.STATE_FAILED) {
                                playbackCacheReuseIds.remove(download.request.id)
                            }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            downloads.update { map -> map - download.request.id }
                            cancelDownloadedArtworkJob(download.request.id)
                            playbackCacheReuseIds.remove(download.request.id)
                            downloadScope.launch {
                                downloadedArtworkRepository.remove(download.request.id)
                            }
                        }
                    },
                )
            }

        init {
            downloadScope.launch {
                val result = mutableMapOf<String, Download>()
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        result[cursor.download.request.id] = cursor.download.toProgressSnapshot()
                    }
                }
                downloads.update { current ->
                    result.apply { putAll(current) }
                }
                downloadedArtworkRepository.retainForDownloads(result.keys)
                for (download in result.values) {
                    if (download.state == Download.STATE_COMPLETED) {
                        scheduleDownloadedArtwork(download.request.id).join()
                    }
                }
            }
            downloadScope.launch {
                while (isActive) {
                    delay(DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS)
                    refreshActiveDownloadSnapshots()
                }
            }
            downloadScope.launch {
                var previousFingerprint: String? = null
                YouTube.authStateFlow
                    .map { it.fingerprint }
                    .distinctUntilChanged()
                    .collect { fingerprint ->
                        if (previousFingerprint != null && previousFingerprint != fingerprint) {
                            songUrlCache.clear()
                        }
                        previousFingerprint = fingerprint
                    }
            }
        }

        fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

        private fun refreshActiveDownloadSnapshots() {
            val activeDownloads = downloadManager.currentDownloads
            if (activeDownloads.isEmpty()) return
            downloads.update { current ->
                current.toMutableMap().apply {
                    activeDownloads.forEach { download ->
                        set(download.request.id, download.toProgressSnapshot())
                    }
                }
            }
        }

        private fun invalidateResolvedStreamUrl(url: String) {
            songUrlCache.entries.forEach { (cacheKey, cached) ->
                if (cached.url == url && songUrlCache.remove(cacheKey, cached)) {
                    YTPlayerUtils.invalidateCachedStreamUrls(cacheKey.substringBeforeLast(':'))
                }
            }
        }

        private fun resetDownloadContent(mediaId: String) {
            downloadCache.removeResource(mediaId)
            playerCache.removeResource(mediaId)
            val mutations =
                ContentMetadataMutations()
                    .remove(DOWNLOAD_FORMAT_ID_METADATA_KEY)
                    .remove(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY)
            ContentMetadataMutations.setContentLength(mutations, C.LENGTH_UNSET.toLong())
            ContentMetadataMutations.setRedirectedUri(mutations, null)
            downloadCache.applyContentMetadataMutations(mediaId, mutations)
            playbackCacheReuseIds.remove(mediaId)
            songUrlCache.keys.removeIf { it.startsWith("$mediaId:") }
            YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
        }

        private fun resolveDownloadDataSpec(dataSpec: DataSpec): DataSpec {
            val mediaId = dataSpec.key ?: error("No media id")
            val downloadMetadata = downloadCache.getContentMetadata(mediaId)
            val hasPartialDownload = downloadCache.getCachedSpans(mediaId).isNotEmpty()
            val lowDataModeActive = context.isLowDataModeActive()
            val requestedAudioQuality = resolveDownloadAudioQuality(lowDataModeActive)
            val streamCacheKey = buildSongUrlCacheKey(mediaId, requestedAudioQuality)
            val authFingerprint = YouTube.currentPlaybackAuthState().fingerprint
            val previousFormatId =
                downloadMetadata
                    .get(DOWNLOAD_FORMAT_ID_METADATA_KEY, -1L)
                    .toInt()
                    .takeIf { it > 0 }
            val pinnedFormatId = previousFormatId.takeIf { hasPartialDownload }
            songUrlCache[streamCacheKey]
                ?.takeIf {
                    it.isValidFor(
                        authFingerprint = authFingerprint,
                        minimumRemainingMs = YTPlayerUtils.STREAM_URL_EXPIRY_SAFETY_MS,
                    )
                }?.let {
                    return dataSpec.withUri(it.url.toUri())
                }
            val playbackData =
                runBlocking(Dispatchers.IO) {
                    context.retryWithoutPlaybackLoginContext {
                        YTPlayerUtils.playerResponseForDownload(
                            mediaId,
                            audioQuality = requestedAudioQuality,
                            connectivityManager = connectivityManager,
                            networkMetered = lowDataModeActive,
                            pinnedFormatId = pinnedFormatId,
                        )
                    }
                }.getOrThrow()

            val previousContentLength =
                downloadMetadata
                    .get(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, -1L)
                    .takeIf { it > 0L }
                    ?: ContentMetadata.getContentLength(downloadMetadata).takeIf { it > 0L }
            val resolvedFormatId = playbackData.format.itag
            val resolvedContentLength = playbackData.format.contentLength?.takeIf { it > 0L }
            val formatChanged = previousFormatId != null && resolvedFormatId > 0 && previousFormatId != resolvedFormatId
            val lengthChanged =
                previousContentLength != null && resolvedContentLength != null &&
                    previousContentLength != resolvedContentLength
            if (formatChanged || lengthChanged) {
                throw DownloadContentChangedException()
            }

            val metadataMutations = ContentMetadataMutations()
            if (resolvedFormatId > 0) {
                metadataMutations.set(DOWNLOAD_FORMAT_ID_METADATA_KEY, resolvedFormatId.toLong())
            }
            if (resolvedContentLength != null) {
                metadataMutations.set(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, resolvedContentLength)
            }
            downloadCache.applyContentMetadataMutations(mediaId, metadataMutations)
            persistPlaybackMetadata(mediaId, playbackData)

            val streamUrl = playbackData.streamUrl
            songUrlCache[streamCacheKey] =
                AuthScopedCacheValue(
                    url = streamUrl,
                    expiresAtMs = System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L),
                    authFingerprint = playbackData.authFingerprint,
                )
            return dataSpec
                .buildUpon()
                .setUri(streamUrl.toUri())
                .apply {
                    resolvedContentLength?.let { totalLength ->
                        val remainingLength = totalLength - dataSpec.position
                        if (remainingLength > 0L) {
                            setLength(
                                if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                                    remainingLength
                                } else {
                                    minOf(dataSpec.length, remainingLength)
                                },
                            )
                        }
                    }
                }.build()
        }

        private fun Download.toProgressSnapshot(): Download {
            val progressSnapshot =
                DownloadProgress().apply {
                    bytesDownloaded = this@toProgressSnapshot.bytesDownloaded
                    percentDownloaded = this@toProgressSnapshot.percentDownloaded
                }
            return Download(
                request,
                state,
                startTimeMs,
                updateTimeMs,
                contentLength,
                stopReason,
                failureReason,
                progressSnapshot,
            )
        }

        private fun resolveDownloadAudioQuality(lowDataModeActive: Boolean): AudioQuality =
            if (lowDataModeActive) AudioQuality.LOW else audioQuality

        private fun buildSongUrlCacheKey(
            mediaId: String,
            requestedAudioQuality: AudioQuality,
        ): String = "$mediaId:${requestedAudioQuality.name}"

        private fun persistPlaybackMetadata(
            mediaId: String,
            playbackData: YTPlayerUtils.PlaybackData,
        ) {
            downloadScope.launch {
                try {
                    val format = playbackData.format
                    val contentLength = format.contentLength ?: 0L
                    val resolvedCodecs =
                        format.mimeType
                            .substringAfter("codecs=", "")
                            .removeSurrounding("\"")
                            .substringBefore("\"")

                    val artworkUrls =
                        database.withTransaction {
                            upsert(
                                FormatEntity(
                                    id = mediaId,
                                    itag = format.itag,
                                    mimeType = format.mimeType.split(";")[0],
                                    codecs = resolvedCodecs,
                                    bitrate = format.bitrate,
                                    sampleRate = format.audioSampleRate,
                                    contentLength = contentLength,
                                    loudnessDb = playbackData.audioConfig?.loudnessDb,
                                    perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                                    playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl,
                                ),
                            )

                        val now = LocalDateTime.now()
                        val existing = getSongByIdBlocking(mediaId)?.song
                        val resolvedThumbnailUrl =
                            playbackData.videoDetails
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                                ?.takeIf { it.isNotBlank() }

                        val updatedSong =
                            if (existing != null) {
                                existing.copy(
                                    thumbnailUrl = existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedThumbnailUrl,
                                    dateDownload = existing.dateDownload ?: now,
                                )
                            } else {
                                SongEntity(
                                    id = mediaId,
                                    title = playbackData.videoDetails?.title ?: "Unknown",
                                    duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                                    thumbnailUrl = resolvedThumbnailUrl,
                                    dateDownload = now,
                                )
                            }

                            upsert(updatedSong)
                            listOf(updatedSong.thumbnailUrl, resolvedThumbnailUrl)
                        }
                    scheduleDownloadedArtwork(mediaId, artworkUrls)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Timber.w(exception, "Failed to persist download metadata")
                }
            }
        }

        private fun scheduleDownloadedArtwork(
            mediaId: String,
            knownSourceUrls: Collection<String?> = emptyList(),
        ): Job =
            synchronized(artworkJobs) {
                val currentJob = artworkJobs[mediaId]
                if (currentJob?.isActive == true && knownSourceUrls.isEmpty()) return@synchronized currentJob
                currentJob?.cancel()

                val job =
                    downloadScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val databaseThumbnailUrl =
                                database.withTransaction {
                                    getSongByIdBlocking(mediaId)?.song?.thumbnailUrl
                                }
                            downloadedArtworkRepository.cache(
                                mediaId = mediaId,
                                sourceUrls = knownSourceUrls + databaseThumbnailUrl,
                            )
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            Timber.w(exception, "Failed to cache downloaded artwork")
                        } finally {
                            val runningJob = currentCoroutineContext()[Job]
                            synchronized(artworkJobs) {
                                if (artworkJobs[mediaId] === runningJob) {
                                    artworkJobs.remove(mediaId)
                                }
                            }
                        }
                    }
                artworkJobs[mediaId] = job
                job.start()
                job
            }

        private fun cancelDownloadedArtworkJob(mediaId: String) {
            synchronized(artworkJobs) {
                artworkJobs.remove(mediaId)?.cancel()
            }
        }

        private class CompletePlaybackCacheDataSource(
            private val downloadCache: Cache,
            private val playerCache: Cache,
            private val playbackCacheDataSourceFactory: DataSource.Factory,
            private val networkDataSourceFactory: DataSource.Factory,
            private val playbackCacheReuseIds: MutableSet<String>,
        ) : DataSource {
            private val transferListeners = mutableListOf<TransferListener>()
            private var delegate: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                transferListeners += transferListener
                delegate?.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val mediaId = dataSpec.key
                val selectedFactory =
                    if (mediaId != null && shouldUsePlaybackCache(mediaId)) {
                        playbackCacheDataSourceFactory
                    } else {
                        networkDataSourceFactory
                    }
                return selectedFactory
                    .createDataSource()
                    .also { selected ->
                        transferListeners.forEach(selected::addTransferListener)
                        delegate = selected
                    }.open(dataSpec)
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = checkNotNull(delegate).read(buffer, offset, length)

            override fun getUri(): Uri? = delegate?.uri

            override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

            override fun close() {
                val selected = delegate
                delegate = null
                selected?.close()
            }

            private fun shouldUsePlaybackCache(mediaId: String): Boolean {
                if (mediaId in playbackCacheReuseIds) return true
                val hasPartialDownload = downloadCache.getCachedSpans(mediaId).isNotEmpty()
                val playbackContentLength =
                    ContentMetadata
                        .getContentLength(playerCache.getContentMetadata(mediaId))
                        .takeIf { it > 0L }
                val fullyCached =
                    playbackContentLength?.let { contentLength ->
                        playerCache.isCached(mediaId, 0L, contentLength)
                    } == true
                if (
                    !shouldReuseCompletePlaybackCache(
                        hasPartialDownload = hasPartialDownload,
                        playbackContentLength = playbackContentLength,
                        fullyCached = fullyCached,
                    )
                ) {
                    return false
                }
                playbackCacheReuseIds += mediaId
                return true
            }
        }

        companion object {
            private const val DOWNLOAD_FORMAT_ID_METADATA_KEY = "archivetune_download_format_id"
            private const val DOWNLOAD_CONTENT_LENGTH_METADATA_KEY = "archivetune_download_content_length"
            private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 6
            private const val DOWNLOAD_MIN_RETRY_COUNT = 10
            private const val MAX_IDLE_DOWNLOAD_CONNECTIONS = 12
            private const val MAX_DOWNLOAD_HTTP_REQUESTS = 24
            private const val DOWNLOAD_PROGRESS_REFRESH_INTERVAL_MS = 1_000L
            private const val DOWNLOAD_CONNECTION_KEEP_ALIVE_MINUTES = 5L
            private const val DOWNLOAD_WRITE_BUFFER_SIZE = 256 * 1024
            private val STREAM_REFRESH_RESPONSE_CODES = setOf(403, 404, 410, 416)
        }
    }

internal fun shouldReuseCompletePlaybackCache(
    hasPartialDownload: Boolean,
    playbackContentLength: Long?,
    fullyCached: Boolean,
): Boolean = !hasPartialDownload && playbackContentLength != null && playbackContentLength > 0L && fullyCached

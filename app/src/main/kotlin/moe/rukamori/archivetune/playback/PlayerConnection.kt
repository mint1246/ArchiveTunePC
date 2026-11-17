/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.currentMetadata
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.MusicService.MusicBinder
import moe.rukamori.archivetune.playback.android.Media3PlayerStateObserver
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.canvas.CanvasPlaybackRequest
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.reportException
import java.util.Locale

internal enum class CanvasArtworkRefetchResult {
    Success,
    Failure,
    AlreadyRunning,
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    context: Context,
    binder: MusicBinder,
    val database: MusicDatabase,
    scope: CoroutineScope,
) {
    val service = binder.service
    val player = service.player
    val localPlayer = service.localPlayer

    val queueTitle = MutableStateFlow(service.queueTitle)
    private val media3State = Media3PlayerStateObserver(
        player = player,
        onPlayerError = ::reportException,
        onTimelineUpdated = { _, _ -> queueTitle.value = service.queueTitle },
    )

    val playbackState = media3State.playbackState
    val isPlaying = media3State.isPlaying
    val playbackParameters = media3State.playbackParameters
    val mediaMetadata = service.currentMediaMetadata
    val currentSong =
        mediaMetadata.flatMapLatest {
            database.song(it?.id)
        }
    val currentLyrics =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.lyrics(mediaMetadata?.id)
        }
    val currentFormat =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    val queueWindows = media3State.queueWindows
    val currentMediaItemIndex = media3State.currentMediaItemIndex
    val currentWindowIndex = media3State.currentWindowIndex

    val shuffleModeEnabled = media3State.shuffleModeEnabled
    val repeatMode = media3State.repeatMode

    val canSkipPrevious = media3State.canSkipPrevious
    val canSkipNext = media3State.canSkipNext

    val aodModeEnabled = MutableStateFlow(false)

    val error = media3State.error
    val waitingForNetworkConnection = service.waitingForNetworkConnection
    val queueRestoreCompleted = service.queueRestoreCompleted

    internal val canvasNetworkAllowed = service.canvasPlaybackUseCase.policy
        .map { it.networkAllowed }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    private val canvasArtworkRefetchMutex = Mutex()
    private val _isCanvasArtworkRefetching = MutableStateFlow(false)
    internal val isCanvasArtworkRefetching = _isCanvasArtworkRefetching.asStateFlow()

    private var metadataExtractionJob: Job? = null

    init {
        if (player.mediaItemCount > 0 && service.currentMediaMetadata.value == null) {
            service.currentMediaMetadata.value = player.currentMetadata
        }

        metadataExtractionJob =
            scope.launch(Dispatchers.IO) {
                mediaMetadata
                    .distinctUntilChangedBy { it?.id }
                    .collectLatest { metadata ->
                        val mediaId = metadata?.id ?: return@collectLatest
                        if (mediaId.isLocalMediaId()) {
                            val storedFormat = database.format(mediaId).first()
                            if (storedFormat != null && storedFormat.bitrate == 0 && storedFormat.sampleRate == null) {
                                val result =
                                    extractLocalAudioProperties(context, mediaId)
                                        ?: return@collectLatest
                                ensureActive()
                                val finalBitrate =
                                    if (result.first <= 0 && result.second == null) {
                                        -1
                                    } else {
                                        result.first
                                    }
                                database.updateLocalAudioMetadata(mediaId, finalBitrate, result.second)
                            }
                        }
                    }
            }
    }

    private suspend fun extractLocalAudioProperties(
        context: Context,
        uriString: String,
    ): Pair<Int, Int?>? =
        withContext(Dispatchers.IO) {
            val extractor = android.media.MediaExtractor()
            var bitrate = 0
            var sampleRate: Int? = null
            try {
                val uri = android.net.Uri.parse(uriString)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    timber.log.Timber
                        .tag("LocalMetadataExtractor")
                        .w("Could not open file descriptor for %s", uriString)
                    return@withContext null
                }
                pfd.use { descriptor ->
                    extractor.setDataSource(descriptor.fileDescriptor)
                    if (extractor.trackCount == 0) {
                        return@withContext Pair(-1, null)
                    }
                    var foundAudioTrack = false
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            foundAudioTrack = true
                            if (format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) {
                                bitrate = format.getInteger(android.media.MediaFormat.KEY_BIT_RATE)
                            }
                            if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            }
                            break
                        }
                    }
                    if (!foundAudioTrack) {
                        return@withContext Pair(-1, null)
                    }
                }
                Pair(bitrate, sampleRate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                timber.log.Timber
                    .tag("LocalMetadataExtractor")
                    .w(e, "Permission denied extracting metadata for %s", uriString)
                null
            } catch (e: java.io.FileNotFoundException) {
                timber.log.Timber
                    .tag("LocalMetadataExtractor")
                    .w(e, "File not found for %s", uriString)
                null
            } catch (e: java.io.IOException) {
                val message = e.message?.lowercase() ?: ""
                if ("unsupported" in message || "malformed" in message || "invalid" in message || "failed to instantiate" in message) {
                    timber.log.Timber
                        .tag("LocalMetadataExtractor")
                        .w(e, "Confirmed unsupported file %s", uriString)
                    Pair(-1, null)
                } else {
                    timber.log.Timber
                        .tag("LocalMetadataExtractor")
                        .w(e, "Transient I/O error extracting metadata for %s", uriString)
                    null
                }
            } catch (e: Exception) {
                timber.log.Timber
                    .tag("LocalMetadataExtractor")
                    .w(e, "Unexpected error extracting metadata for %s", uriString)
                null
            } finally {
                runCatching { extractor.release() }
            }
        }

    fun playQueue(queue: Queue) {
        service.playQueue(queue)
    }

    fun startRadioSeamlessly() {
        service.startRadioSeamlessly()
    }

    fun playNext(item: MediaItem) = playNext(listOf(item))

    fun playNext(items: List<MediaItem>) {
        service.playNext(items)
    }

    fun moveQueueItemToNext(mediaItemIndex: Int) {
        service.moveQueueItemToNext(mediaItemIndex)
    }

    fun addToQueue(item: MediaItem) = addToQueue(listOf(item))

    fun addToQueue(items: List<MediaItem>) {
        service.addToQueue(items)
    }

    fun playFromVoiceSearch(query: String) {
        service.playFromVoiceSearch(query)
    }

    fun toggleLike() {
        service.toggleLike()
    }

    internal suspend fun refetchCanvasArtwork(
        metadata: MediaMetadata,
        requireVertical: Boolean,
    ): CanvasArtworkRefetchResult {
        if (!canvasArtworkRefetchMutex.tryLock()) return CanvasArtworkRefetchResult.AlreadyRunning

        _isCanvasArtworkRefetching.value = true
        return try {
            val country = Locale.getDefault().country
            val storefront = if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
            val refreshed = service.canvasPlaybackUseCase.refresh(
                CanvasPlaybackRequest(
                    mediaId = metadata.id,
                    title = metadata.title,
                    artist = metadata.artists.firstOrNull()?.name.orEmpty(),
                    storefront = storefront,
                    requireVertical = requireVertical,
                ),
            )
            if (!refreshed) return CanvasArtworkRefetchResult.Failure

            CanvasArtworkRefetchResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            timber.log.Timber.tag("CanvasArtwork").w(error, "Canvas refetch failed for %s", metadata.id)
            CanvasArtworkRefetchResult.Failure
        } finally {
            _isCanvasArtworkRefetching.value = false
            canvasArtworkRefetchMutex.unlock()
        }
    }

    fun dismissPlaybackError() = media3State.dismissPlaybackError()

    fun seekToNext() {
        val state = service.togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Joined
        if (state?.role is moe.rukamori.archivetune.together.TogetherRole.Guest) {
            service.requestTogetherControl(moe.rukamori.archivetune.together.ControlAction.SkipNext)
            return
        }
        media3State.seekToNext()
    }

    fun seekToPrevious() {
        val state = service.togetherSessionState.value as? moe.rukamori.archivetune.together.TogetherSessionState.Joined
        if (state?.role is moe.rukamori.archivetune.together.TogetherRole.Guest) {
            service.requestTogetherControl(moe.rukamori.archivetune.together.ControlAction.SkipPrevious)
            return
        }
        media3State.seekToPrevious()
    }

    fun dispose() {
        media3State.close()
        metadataExtractionJob?.cancel()
        metadataExtractionJob = null
    }
}

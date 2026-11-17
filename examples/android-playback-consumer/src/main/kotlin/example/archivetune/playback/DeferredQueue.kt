package example.archivetune.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.playback.android.Media3PlaybackSession
import moe.rukamori.archivetune.playback.android.createMedia3PlaybackSession
import moe.rukamori.archivetune.playback.android.resolvingPlaybackUpstream
import moe.rukamori.archivetune.playback.android.setPlaybackAddress
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver

/**
 * Creates a turnkey [Media3PlaybackSession] wired with lazy data-source deferred resolution and
 * optional durable JSON queue persistence. Close the returned session on the main thread when done.
 */
@UnstableApi
fun prepareDeferredSession(
    context: Context,
    queueFile: File? = null,
    resolver: PlaybackSourceResolver,
): Media3PlaybackSession = createMedia3PlaybackSession(
    context = context,
    sourceResolver = resolver,
    queueFile = queueFile,
    playerDispatcher = Dispatchers.Main.immediate,
)

/** The host supplies stream resolution, calls play(), and releases the player when finished. */
@UnstableApi
fun prepareDeferredQueue(
    context: Context,
    mediaIds: List<String>,
    resolve: (request: DataSpec, mediaId: String) -> DataSpec,
): ExoPlayer {
    val upstream = resolvingPlaybackUpstream(
        context = context,
        httpFactory = DefaultHttpDataSource.Factory(),
        chunkLength = 8L * 1024 * 1024,
        resolveDeferred = resolve,
    )
    val items = mediaIds.map { mediaId ->
        MediaItem.Builder().setPlaybackAddress(mediaId, cacheKey = mediaId).build()
    }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(upstream))
        .build()
        .apply {
            setMediaItems(items)
            prepare()
        }
}

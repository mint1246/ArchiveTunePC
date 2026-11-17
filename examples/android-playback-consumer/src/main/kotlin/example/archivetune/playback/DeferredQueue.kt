package example.archivetune.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import moe.rukamori.archivetune.playback.android.resolvingPlaybackUpstream
import moe.rukamori.archivetune.playback.android.setPlaybackAddress

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

/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.stems

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface AndroidStemRenderer : AutoCloseable {
    suspend fun prepare(
        entry: AndroidStemCacheEntry,
        positionMs: Long,
        playbackParameters: PlaybackParameters,
        audioSessionId: Int,
    )

    fun setEffectiveGains(gains: List<Float>)

    fun setMasterVolume(volume: Float)

    fun setPlaybackParameters(playbackParameters: PlaybackParameters)

    fun setAudioSessionId(audioSessionId: Int)

    fun seekTo(positionMs: Long)

    fun play()

    fun pause()

    fun positionsMs(): List<Long>

    fun failure(): PlaybackException?
}

/**
 * Real Android renderer: six independent local Opus decoders stay aligned to the primary ExoPlayer
 * transport. Android's audio mixer sums the six stereo AudioTracks; each source remains separately
 * gain-controllable and shares the primary audio session so ArchiveTune EQ effects remain useful.
 */
internal class Media3AndroidStemRenderer(
    context: Context,
    private val audioAttributes: AudioAttributes,
) : AndroidStemRenderer {
    private val players: LinkedHashMap<AndroidStemSource, ExoPlayer> =
        linkedMapOf<AndroidStemSource, ExoPlayer>().apply {
            AndroidStemSource.workerOrder.forEach { source ->
                put(
                    source,
                    ExoPlayer
                        .Builder(context)
                        .setAudioAttributes(audioAttributes, false)
                        .setHandleAudioBecomingNoisy(false)
                        .build(),
                )
            }
        }
    private var gains = List(AndroidStemSource.workerOrder.size) { 1f }
    private var masterVolume = 1f
    private var lastFailure: PlaybackException? = null
    private val errorListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lastFailure = error
            }
        }

    init {
        players.values.forEach { it.addListener(errorListener) }
    }

    override suspend fun prepare(
        entry: AndroidStemCacheEntry,
        positionMs: Long,
        playbackParameters: PlaybackParameters,
        audioSessionId: Int,
    ) = withContext(Dispatchers.Main.immediate) {
        lastFailure = null
        val ready = players.map { (source, player) ->
            val completion = CompletableDeferred<Unit>()
            val listener =
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && !completion.isCompleted) completion.complete(Unit)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!completion.isCompleted) completion.completeExceptionally(error)
                    }
                }
            player.addListener(listener)
            player.playWhenReady = false
            player.volume = 0f
            player.playbackParameters = playbackParameters
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) player.setAudioSessionId(audioSessionId)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(entry.file(source))))
            player.seekTo(positionMs.coerceAtLeast(0L))
            player.prepare()
            completion to listener
        }
        try {
            withTimeout(PREPARE_TIMEOUT_MS) {
                ready.forEach { (completion, _) -> completion.await() }
            }
        } finally {
            ready.zip(players.values).forEach { (pair, player) -> player.removeListener(pair.second) }
        }
        seekTo(positionMs)
        applyVolumes()
    }

    override fun setEffectiveGains(gains: List<Float>) {
        require(gains.size == AndroidStemSource.workerOrder.size) { "Expected six stem gains" }
        this.gains = gains.map { it.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f }
        applyVolumes()
    }

    override fun setMasterVolume(volume: Float) {
        masterVolume = volume.takeIf(Float::isFinite)?.coerceIn(0f, MAX_MASTER_GAIN) ?: 1f
        applyVolumes()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        players.values.forEach { it.playbackParameters = playbackParameters }
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        players.values.forEach { player ->
            if (player.audioSessionId != audioSessionId) player.setAudioSessionId(audioSessionId)
        }
    }

    override fun seekTo(positionMs: Long) {
        val safe = positionMs.coerceAtLeast(0L)
        players.values.forEach { it.seekTo(safe) }
    }

    override fun play() {
        players.values.forEach(ExoPlayer::play)
    }

    override fun pause() {
        players.values.forEach(ExoPlayer::pause)
    }

    override fun positionsMs(): List<Long> = players.values.map { it.currentPosition }

    override fun failure(): PlaybackException? = lastFailure

    override fun close() {
        players.values.forEach { player ->
            runCatching { player.removeListener(errorListener) }
            runCatching { player.release() }
        }
        players.clear()
    }

    private fun applyVolumes() {
        AndroidStemSource.workerOrder.forEachIndexed { index, source ->
            players[source]?.volume = (masterVolume * gains[index]).coerceIn(0f, MAX_MASTER_GAIN)
        }
    }

    private companion object {
        const val PREPARE_TIMEOUT_MS = 8_000L
        const val MAX_MASTER_GAIN = 1.5f
    }
}

internal fun stemRendererNeedsResync(
    primaryPositionMs: Long,
    stemPositionsMs: List<Long>,
    toleranceMs: Long = 120L,
): Boolean =
    stemPositionsMs.size != AndroidStemSource.workerOrder.size ||
        stemPositionsMs.any { position -> kotlin.math.abs(position - primaryPositionMs) > toleranceMs }

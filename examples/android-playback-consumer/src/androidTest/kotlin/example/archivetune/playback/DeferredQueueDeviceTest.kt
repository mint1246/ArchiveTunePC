package example.archivetune.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class DeferredQueueDeviceTest {
    @Test
    fun automaticallyAdvancingOpensTheNextDeferredTrack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File.createTempFile("deferred-queue", ".wav", context.cacheDir)
        var player: ExoPlayer? = null
        try {
            audio.writeBytes(shortPcmWave())
            val resolvedIds = ConcurrentHashMap.newKeySet<String>()
            val advanced = CountDownLatch(1)
            val error = AtomicReference<PlaybackException>()

            instrumentation.runOnMainSync {
                val activePlayer = prepareDeferredQueue(context, listOf("first-track", "second-track")) { request, mediaId ->
                    resolvedIds.add(mediaId)
                    request.withUri(Uri.fromFile(audio))
                }
                player = activePlayer
                activePlayer.addListener(object : Player.Listener {
                    private var advancedAutomatically = false

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        advancedAutomatically =
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem?.mediaId == "second-track"
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (advancedAutomatically && player.playbackState == Player.STATE_READY) {
                            advanced.countDown()
                        }
                    }

                    override fun onPlayerError(playbackError: PlaybackException) {
                        error.set(playbackError)
                        advanced.countDown()
                    }
                })
                activePlayer.play()
            }

            assertTrue("Player did not advance to the second item", advanced.await(15, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("Deferred queue playback failed", it) }
            assertEquals(setOf("first-track", "second-track"), resolvedIds)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            audio.delete()
        }
    }

    private fun shortPcmWave(): ByteArray {
        val sampleRate = 44_100
        val bytesPerSample = 2
        val pcmLength = sampleRate / 4 * bytesPerSample
        val headerLength = 44
        return ByteBuffer.allocate(headerLength + pcmLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(headerLength + pcmLength - 8)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * bytesPerSample)
            putShort(bytesPerSample.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmLength)
        }.array()
    }
}

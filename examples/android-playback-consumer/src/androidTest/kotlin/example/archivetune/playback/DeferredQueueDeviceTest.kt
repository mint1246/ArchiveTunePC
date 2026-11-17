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
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.playback.android.Media3PlaybackSession
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
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

    @Test
    fun sessionAdvancesDeferredQueueAndRestoresPersistedQueue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File.createTempFile("deferred-session", ".wav", context.cacheDir)
        val queueFile = File(context.cacheDir, "session-queue-test.json").apply { delete() }
        var session: Media3PlaybackSession? = null
        var restoredSession: Media3PlaybackSession? = null
        try {
            audio.writeBytes(shortPcmWave())
            val resolvedKeys = ConcurrentHashMap.newKeySet<String>()
            val advanced = CountDownLatch(1)
            val error = AtomicReference<PlaybackException>()
            val resolver = PlaybackSourceResolver { item ->
                val key = (item.source as? PlaybackSource.Deferred)?.key ?: item.metadata.id
                resolvedKeys.add(key)
                Result.success(ResolvedPlaybackSource(uri = Uri.fromFile(audio).toString()))
            }

            val items = listOf(
                PlaybackItem(PlaybackMetadata(id = "track-1", title = "First"), PlaybackSource.Deferred("key-1")),
                PlaybackItem(PlaybackMetadata(id = "track-2", title = "Second"), PlaybackSource.Deferred("key-2")),
            )

            instrumentation.runOnMainSync {
                val active = prepareDeferredSession(context, queueFile = queueFile, resolver = resolver)
                session = active
                active.exoPlayer.addListener(object : Player.Listener {
                    private var advancedAutomatically = false

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        advancedAutomatically =
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem?.mediaId == "track-2"
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
                runBlocking {
                    active.replaceQueue(items, startIndex = 0, title = "Device Mix", playWhenReady = true)
                }
            }

            assertTrue("Session did not advance to the second deferred item", advanced.await(15, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("Deferred session playback failed", it) }
            assertEquals(setOf("key-1", "key-2"), resolvedKeys)

            instrumentation.runOnMainSync {
                session?.close()
                session = null

                val restored = prepareDeferredSession(context, queueFile = queueFile, resolver = resolver)
                restoredSession = restored
                val didRestore = runBlocking { restored.restorePersistedQueue(playWhenReady = false) }
                assertTrue("Expected persisted queue to restore", didRestore)
                assertEquals(items, restored.state.value.queue)
                assertEquals(1, restored.state.value.currentIndex)
                assertEquals("Device Mix", restored.state.value.queueTitle)
            }
        } finally {
            instrumentation.runOnMainSync {
                session?.close()
                restoredSession?.close()
            }
            audio.delete()
            queueFile.delete()
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

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackSourceResolver
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.ResolvedPlaybackSource
import moe.rukamori.archivetune.playback.session.PersistedPlaybackQueue

@OptIn(ExperimentalCoroutinesApi::class)
class IosPlaybackQueueCodecTest {
    @Test
    fun roundTripsMetadataSourcesAndStripsTransientRemoteHeaders() {
        val queue = PersistedPlaybackQueue(
            title = "Evening Mix",
            items = listOf(
                PlaybackItem(
                    metadata = PlaybackMetadata(
                        id = "vid-1",
                        title = "Song One",
                        artists = listOf(PlaybackArtist(id = "ar-1", name = "Artist One")),
                        album = PlaybackAlbum(id = "al-1", title = "Album One"),
                        artworkUri = "https://i.ytimg.com/vi/vid-1/hqdefault.jpg",
                        durationHintMs = 215_000L,
                        explicit = true,
                        extras = mapOf("origin" to "home"),
                    ),
                    source = PlaybackSource.Deferred("vid-1"),
                ),
                PlaybackItem(
                    metadata = PlaybackMetadata(id = "vid-2", title = "Remote Song"),
                    source = PlaybackSource.Remote(
                        url = "https://audio.example.com/2.m4a",
                        mimeType = "audio/mp4",
                        headers = mapOf("Authorization" to "Bearer top-secret"),
                    ),
                ),
            ),
            currentIndex = 1,
            positionMs = 42_000L,
        )

        val encoded = IosPlaybackQueueCodec.encode(queue)
        assertFalse(encoded.contains("Authorization"))
        assertFalse(encoded.contains("top-secret"))

        val decoded = IosPlaybackQueueCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals("Evening Mix", decoded.title)
        assertEquals(1, decoded.currentIndex)
        assertEquals(42_000L, decoded.positionMs)
        assertEquals(2, decoded.items.size)
        assertEquals(PlaybackSource.Deferred("vid-1"), decoded.items[0].source)
        val remote = decoded.items[1].source as PlaybackSource.Remote
        assertTrue(remote.headers.isEmpty())
        assertEquals("https://audio.example.com/2.m4a", remote.url)
    }

    @Test
    fun corruptJsonReturnsNullAndStringBackedPersistenceRestoresSession() = runTest {
        assertNull(IosPlaybackQueueCodec.decode("{not-json"))
        assertNull(IosPlaybackQueueCodec.decode(""))

        var rawStore: String? = null
        val persistence = StringBackedPlaybackQueuePersistence(
            read = { rawStore },
            write = { rawStore = it },
            delete = { rawStore = null },
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dummyEngine = object : IosPlaybackEngine {
            private val s = MutableStateFlow(IosEngineState())
            override val state: StateFlow<IosEngineState> = s.asStateFlow()
            override fun load(item: PlaybackItem, source: ResolvedPlaybackSource, startPositionMs: Long) {}
            override fun play() {}
            override fun pause() {}
            override fun seekTo(positionMs: Long) {}
            override fun stop() {}
            override fun close() {}
        }

        persistence.save(
            moe.rukamori.archivetune.playback.api.PlaybackSnapshot(
                queue = listOf(PlaybackItem(PlaybackMetadata(id = "v1", title = "Restored"))),
                queueTitle = "Saved",
                currentIndex = 0,
                positionMs = 15_000L,
            ),
        )
        assertNotNull(rawStore)

        val session = createIosPlaybackSession(
            engine = dummyEngine,
            sourceResolver = PlaybackSourceResolver { Result.success(ResolvedPlaybackSource("https://audio.example.com/v1.m4a", "audio/mp4")) },
            queuePersistence = persistence,
            persistenceDispatcher = dispatcher,
            parentContext = dispatcher,
        )
        val restored = session.restorePersistedQueue(playWhenReady = false)
        advanceUntilIdle()
        assertTrue(restored)
        assertEquals("v1", session.state.value.currentItem?.metadata?.id)
        assertEquals(PlaybackStatus.IDLE, session.state.value.status)

        session.close()
    }
}

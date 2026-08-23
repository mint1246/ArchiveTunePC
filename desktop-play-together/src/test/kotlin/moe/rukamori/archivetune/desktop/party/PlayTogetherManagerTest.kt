package moe.rukamori.archivetune.desktop.party

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.playback.api.PlaybackController
import moe.rukamori.archivetune.playback.api.PlaybackEvent
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackQueueEntry
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import moe.rukamori.archivetune.playback.api.RepeatMode
import tel.schich.libdatachannel.DataChannelCallback
import tel.schich.libdatachannel.GatheringState
import tel.schich.libdatachannel.LibDataChannelArchDetect
import tel.schich.libdatachannel.PeerConnection
import tel.schich.libdatachannel.PeerConnectionConfiguration
import tel.schich.libdatachannel.SessionDescriptionType

class PlayTogetherManagerTest {
    @Test
    fun `quick tunnel invite becomes secure websocket uri`() {
        val uri = PlayTogetherManager.parseInvite(
            "https://small-cloud-name.trycloudflare.com/party?token=0123456789abcdef",
        )

        assertEquals("wss", uri.scheme)
        assertEquals("small-cloud-name.trycloudflare.com", uri.host)
        assertEquals("/party", uri.path)
        assertEquals("token=0123456789abcdef", uri.query)
    }

    @Test
    fun `rejects a non quick tunnel host`() {
        assertFailsWith<IllegalArgumentException> {
            PlayTogetherManager.parseInvite("https://example.com/party?token=0123456789abcdef")
        }
    }

    @Test
    fun `rejects invite without access token`() {
        assertFailsWith<IllegalArgumentException> {
            PlayTogetherManager.parseInvite("https://small-cloud-name.trycloudflare.com/party")
        }
    }

    @Test
    fun `guest restrictions only apply while actually connected`() {
        assertFalse(PlayTogetherState(mode = PartyMode.GUEST, phase = PartyPhase.STARTING).isConnectedGuest)
        assertTrue(PlayTogetherState(mode = PartyMode.GUEST, phase = PartyPhase.CONNECTED).isConnectedGuest)
        assertFalse(PlayTogetherState(mode = PartyMode.NONE, phase = PartyPhase.ERROR).isConnectedGuest)
    }

    @Test
    fun `party playback policy reflects ownership`() {
        val none = PlayTogetherState()
        assertTrue(none.localTransportCommandsAllowed)
        assertTrue(none.persistSpeedAndRepeat)
        assertTrue(none.persistShuffle)

        val host = PlayTogetherState(mode = PartyMode.HOST, phase = PartyPhase.CONNECTED)
        assertTrue(host.localTransportCommandsAllowed)
        assertTrue(host.persistSpeedAndRepeat)
        assertFalse(host.persistShuffle)

        val guestStarting = PlayTogetherState(mode = PartyMode.GUEST, phase = PartyPhase.STARTING)
        assertTrue(guestStarting.localTransportCommandsAllowed)
        assertFalse(guestStarting.persistSpeedAndRepeat)
        assertFalse(guestStarting.persistShuffle)

        val guestConnected = PlayTogetherState(mode = PartyMode.GUEST, phase = PartyPhase.CONNECTED)
        assertFalse(guestConnected.localTransportCommandsAllowed)
        assertFalse(guestConnected.persistSpeedAndRepeat)
        assertFalse(guestConnected.persistShuffle)
    }

    @Test
    fun `host and non party playback toggle stays local`() = runBlocking {
        val playback = RecordingPlaybackController(PlaybackSnapshot(status = PlaybackStatus.READY, playWhenReady = true))
        PlayTogetherManager(playback, Files.createTempDirectory("party-toggle-test"), Logger.getAnonymousLogger()).use { manager ->
            assertTrue(manager.togglePlayback())
            assertFalse(playback.state.value.playWhenReady)

            val generation = manager.beginSession(PartyMode.HOST, "Hosting")
            assertTrue(manager.togglePlayback())
            assertTrue(playback.state.value.playWhenReady)
            manager.finishSession(generation, PlayTogetherState())
        }
    }

    @Test
    fun `new guest transport permissions default off`() {
        val state = PlayTogetherState(mode = PartyMode.GUEST, phase = PartyPhase.CONNECTED)
        assertFalse(state.canChangeSong)
        assertFalse(state.canSeek)

        val participant = PartyParticipant(id = "guest", name = "Guest")
        assertTrue(participant.canEditQueue)
        assertTrue(participant.canControlPlayback)
        assertFalse(participant.canChangeSong)
        assertFalse(participant.canSeek)
    }

    @Test
    fun `host and non party change song and seek actions stay local`() = runBlocking {
        val first = PlaybackItem(PlaybackMetadata(id = "first", title = "First"))
        val second = PlaybackItem(PlaybackMetadata(id = "second", title = "Second"))
        val playback = RecordingPlaybackController(
            PlaybackSnapshot(queue = listOf(first, second), currentIndex = 0, status = PlaybackStatus.READY),
        )
        PlayTogetherManager(playback, Files.createTempDirectory("party-transport-test"), Logger.getAnonymousLogger()).use { manager ->
            assertTrue(manager.seekTo(2_500L))
            assertEquals(2_500L, playback.state.value.positionMs)
            assertTrue(manager.selectQueueItem(1))
            assertEquals(1, playback.state.value.currentIndex)

            val generation = manager.beginSession(PartyMode.HOST, "Hosting")
            assertTrue(manager.playQueue(listOf(first, second), startIndex = 0, title = "Shared"))
            assertEquals(0, playback.state.value.currentIndex)
            assertEquals("Shared", playback.state.value.queueTitle)
            assertTrue(playback.state.value.playWhenReady)
            manager.finishSession(generation, PlayTogetherState())
        }
    }

    @Test
    fun `guest session restores exact pre-party playback modes`() = runBlocking {
        val playback = RecordingPlaybackController(
            PlaybackSnapshot(speed = 1.25f, repeatMode = RepeatMode.ONE, shuffleEnabled = true),
        )
        PlayTogetherManager(playback, Files.createTempDirectory("party-guest-test"), Logger.getAnonymousLogger()).use { manager ->
            val generation = manager.beginSession(PartyMode.GUEST, "Joining")
            assertFalse(playback.state.value.shuffleEnabled)

            playback.setSpeed(0.75f)
            playback.setRepeatMode(RepeatMode.OFF)
            manager.finishSession(generation, PlayTogetherState())

            assertEquals(1.25f, playback.state.value.speed)
            assertEquals(RepeatMode.ONE, playback.state.value.repeatMode)
            assertTrue(playback.state.value.shuffleEnabled)
            assertEquals(PartyMode.NONE, manager.state.value.mode)
        }
    }

    @Test
    fun `host session restores shuffle but keeps owner speed and repeat changes`() = runBlocking {
        val playback = RecordingPlaybackController(
            PlaybackSnapshot(speed = 1.0f, repeatMode = RepeatMode.OFF, shuffleEnabled = true),
        )
        PlayTogetherManager(playback, Files.createTempDirectory("party-host-test"), Logger.getAnonymousLogger()).use { manager ->
            val generation = manager.beginSession(PartyMode.HOST, "Hosting")
            assertFalse(playback.state.value.shuffleEnabled)

            playback.setSpeed(1.5f)
            playback.setRepeatMode(RepeatMode.ALL)
            manager.finishSession(generation, PlayTogetherState())

            assertEquals(1.5f, playback.state.value.speed)
            assertEquals(RepeatMode.ALL, playback.state.value.repeatMode)
            assertTrue(playback.state.value.shuffleEnabled)
        }
    }

    @Test
    fun `closing guest session restores transient playback modes before shutdown`() = runBlocking {
        val playback = RecordingPlaybackController(
            PlaybackSnapshot(speed = 1.25f, repeatMode = RepeatMode.ONE, shuffleEnabled = true),
        )
        val manager = PlayTogetherManager(
            playback,
            Files.createTempDirectory("party-close-restore-test"),
            Logger.getAnonymousLogger(),
        )

        manager.beginSession(PartyMode.GUEST, "Joining")
        playback.setSpeed(0.75f)
        playback.setRepeatMode(RepeatMode.OFF)
        assertFalse(playback.state.value.shuffleEnabled)

        manager.close()

        assertEquals(1.25f, playback.state.value.speed)
        assertEquals(RepeatMode.ONE, playback.state.value.repeatMode)
        assertTrue(playback.state.value.shuffleEnabled)
        assertEquals(PartyMode.NONE, manager.state.value.mode)
    }

    @Test
    fun `stale finish cannot restore over a newer party`() = runBlocking {
        val playback = RecordingPlaybackController(
            PlaybackSnapshot(speed = 1.25f, repeatMode = RepeatMode.ONE, shuffleEnabled = true),
        )
        PlayTogetherManager(playback, Files.createTempDirectory("party-generation-test"), Logger.getAnonymousLogger()).use { manager ->
            val oldGeneration = manager.beginSession(PartyMode.GUEST, "Old guest")
            playback.setSpeed(0.75f)
            playback.setRepeatMode(RepeatMode.OFF)

            val newGeneration = manager.beginSession(PartyMode.HOST, "New host")
            assertEquals(PartyMode.HOST, manager.state.value.mode)
            assertEquals(1.25f, playback.state.value.speed)
            assertEquals(RepeatMode.ONE, playback.state.value.repeatMode)
            assertFalse(playback.state.value.shuffleEnabled)

            manager.finishSession(oldGeneration, PlayTogetherState(mode = PartyMode.NONE, phase = PartyPhase.ERROR))
            assertEquals(PartyMode.HOST, manager.state.value.mode)
            assertFalse(playback.state.value.shuffleEnabled)

            manager.finishSession(newGeneration, PlayTogetherState())
            assertTrue(playback.state.value.shuffleEnabled)
        }
    }

    @Test
    fun `stale startup cannot publish connected over a newer session`() = runBlocking {
        val playback = RecordingPlaybackController(PlaybackSnapshot())
        PlayTogetherManager(playback, Files.createTempDirectory("party-publish-generation-test"), Logger.getAnonymousLogger()).use { manager ->
            val oldGeneration = manager.beginSession(PartyMode.GUEST, "Old guest")
            val newGeneration = manager.beginSession(PartyMode.HOST, "New host")

            assertTrue(
                manager.publishConnectedSession(
                    newGeneration,
                    PlayTogetherState(mode = PartyMode.HOST, phase = PartyPhase.CONNECTED, message = "Ready"),
                ),
            )
            assertEquals(PartyMode.HOST, manager.state.value.mode)
            assertEquals(PartyPhase.CONNECTED, manager.state.value.phase)
            assertEquals("Ready", manager.state.value.message)

            // Model the old startup resuming after the newer session already published and
            // installed its generation-owned loop. It must not touch either state or loop slots.
            assertFalse(
                manager.publishConnectedSession(
                    oldGeneration,
                    PlayTogetherState(mode = PartyMode.GUEST, phase = PartyPhase.CONNECTED),
                ),
            )
            assertEquals(PartyMode.HOST, manager.state.value.mode)
            assertEquals(PartyPhase.CONNECTED, manager.state.value.phase)
            assertEquals("Ready", manager.state.value.message)
        }
    }

    @Test
    fun `guest connected publication preserves early permission revision and direct negotiation`() {
        val defaultConnected = PlayTogetherState(
            mode = PartyMode.GUEST,
            phase = PartyPhase.CONNECTED,
            transport = PartyTransport.RELAY,
            participantCount = 2,
            message = "Connected — negotiating direct P2P",
        )
        val earlyPermissionAndSnapshot = PlayTogetherState(
            mode = PartyMode.GUEST,
            phase = PartyPhase.STARTING,
            transport = PartyTransport.SIGNALING,
            canEditQueue = true,
            canControlPlayback = true,
            canChangeSong = true,
            canSeek = true,
            queueRevision = "revision-42",
            roundTripMs = 18,
            message = "Joining listening party…",
        )

        val relay = PlayTogetherManager.mergeConnectedState(earlyPermissionAndSnapshot, defaultConnected)
        assertEquals(PartyPhase.CONNECTED, relay.phase)
        assertEquals(PartyTransport.RELAY, relay.transport)
        assertTrue(relay.canEditQueue)
        assertTrue(relay.canControlPlayback)
        assertTrue(relay.canChangeSong)
        assertTrue(relay.canSeek)
        assertEquals("revision-42", relay.queueRevision)
        assertEquals(18, relay.roundTripMs)
        assertEquals(2, relay.participantCount)

        val earlyDirect = earlyPermissionAndSnapshot.copy(
            phase = PartyPhase.CONNECTED,
            transport = PartyTransport.DIRECT,
            directPeerCount = 1,
            message = "Connected directly P2P",
        )
        val direct = PlayTogetherManager.mergeConnectedState(earlyDirect, defaultConnected)
        assertEquals(PartyTransport.DIRECT, direct.transport)
        assertEquals(1, direct.directPeerCount)
        assertEquals("Connected directly P2P", direct.message)
        assertTrue(direct.canEditQueue)
        assertTrue(direct.canControlPlayback)
        assertTrue(direct.canChangeSong)
        assertTrue(direct.canSeek)
        assertEquals("revision-42", direct.queueRevision)
        assertEquals(18, direct.roundTripMs)
    }

    @Test
    fun `session mutation fence serializes transition and rejects stale work`() = runBlocking {
        val original = PlaybackItem(PlaybackMetadata(id = "original", title = "Original"))
        val accepted = PlaybackItem(PlaybackMetadata(id = "accepted", title = "Accepted"))
        val stale = PlaybackItem(PlaybackMetadata(id = "stale", title = "Stale"))
        val playback = RecordingPlaybackController(PlaybackSnapshot(queue = listOf(original), currentIndex = 0))
        PlayTogetherManager(playback, Files.createTempDirectory("party-mutation-fence-test"), Logger.getAnonymousLogger()).use { manager ->
            val oldGeneration = manager.beginSession(PartyMode.GUEST, "Old guest")
            val enteredMutation = CompletableDeferred<Unit>()
            val releaseMutation = CompletableDeferred<Unit>()

            val mutation = async {
                manager.withCurrentSessionMutation(oldGeneration) {
                    enteredMutation.complete(Unit)
                    releaseMutation.await()
                    playback.enqueue(listOf(accepted))
                }
            }
            enteredMutation.await()

            val nextSession = async { manager.beginSession(PartyMode.HOST, "New host") }
            yield()
            assertFalse(nextSession.isCompleted, "A new session must wait for the old session's accepted mutation to finish")

            releaseMutation.complete(Unit)
            assertTrue(mutation.await())
            val newGeneration = nextSession.await()
            assertEquals(listOf("original", "accepted"), playback.state.value.queue.map { it.metadata.id })

            assertFalse(
                manager.withCurrentSessionMutation(oldGeneration) {
                    playback.enqueue(listOf(stale))
                },
            )
            assertEquals(listOf("original", "accepted"), playback.state.value.queue.map { it.metadata.id })
            manager.finishSession(newGeneration, PlayTogetherState())
        }
    }

    @Test
    fun `queue revision distinguishes duplicate entries by stable queue identity`() {
        val duplicate = PlaybackItem(PlaybackMetadata(id = "same-video", title = "Duplicate"))
        val firstOrder = PlaybackSnapshot(
            queue = listOf(duplicate, duplicate),
            queueEntries = listOf(
                PlaybackQueueEntry(id = 11L, item = duplicate, logicalIndex = 0, isCurrent = true),
                PlaybackQueueEntry(id = 22L, item = duplicate, logicalIndex = 1, isCurrent = false),
            ),
            currentIndex = 0,
        )
        val swappedStableEntries = firstOrder.copy(
            queueEntries = listOf(
                PlaybackQueueEntry(id = 22L, item = duplicate, logicalIndex = 0, isCurrent = true),
                PlaybackQueueEntry(id = 11L, item = duplicate, logicalIndex = 1, isCurrent = false),
            ),
        )

        assertNotEquals(
            PlayTogetherManager.queueSignature(firstOrder),
            PlayTogetherManager.queueSignature(swappedStableEntries),
        )
    }

    @Test
    fun `queue revision keeps stable-entry encoding unchanged`() {
        val first = PlaybackItem(PlaybackMetadata(id = "alpha", title = "Alpha"))
        val second = PlaybackItem(PlaybackMetadata(id = "beta", title = "Beta"))
        val snapshot = PlaybackSnapshot(
            queueTitle = "Queue",
            queue = listOf(first, second),
            queueEntries = listOf(
                PlaybackQueueEntry(id = 11L, item = first, logicalIndex = 0, isCurrent = true),
                PlaybackQueueEntry(id = 22L, item = second, logicalIndex = 1, isCurrent = false),
            ),
            currentIndex = 0,
        )

        assertEquals("Queue|11@0:alpha|22@1:beta", PlayTogetherManager.queueSignature(snapshot))
    }

    @Test
    fun `fallback queue revision is collision safe for delimiter shaped ids`() {
        val splitIds = PlayTogetherManager.fallbackQueueSignature("Queue", listOf("alpha", "beta"))
        val embeddedDelimiter = PlayTogetherManager.fallbackQueueSignature("Queue", listOf("alpha|beta"))
        val nullTitle = PlayTogetherManager.fallbackQueueSignature(null, listOf("alpha"))
        val literalNullTitle = PlayTogetherManager.fallbackQueueSignature("null", listOf("alpha"))

        assertNotEquals(splitIds, embeddedDelimiter)
        assertNotEquals(nullTitle, literalNullTitle)
    }

    @Test
    fun `fallback queue revision changes on reorder add and remove`() {
        val original = PlayTogetherManager.fallbackQueueSignature("Queue", listOf("alpha", "beta"))
        val reordered = PlayTogetherManager.fallbackQueueSignature("Queue", listOf("beta", "alpha"))
        val added = PlayTogetherManager.fallbackQueueSignature("Queue", listOf("alpha", "beta", "gamma"))
        val removed = PlayTogetherManager.fallbackQueueSignature("Queue", listOf("alpha"))

        assertNotEquals(original, reordered)
        assertNotEquals(original, added)
        assertNotEquals(original, removed)
        assertEquals(original, PlayTogetherManager.fallbackQueueSignature("Queue", listOf("alpha", "beta")))
    }

    @Test
    fun `cloudflared temporary files are generation owned and cleanup is scoped`() {
        val tools = Files.createTempDirectory("party-cloudflared-temp-test")
        val executable = tools.resolve("cloudflared.exe")
        val first = PlayTogetherManager.cloudflaredTemporaryPath(executable, 41L)
        val second = PlayTogetherManager.cloudflaredTemporaryPath(executable, 42L)

        assertNotEquals(first, second)
        assertEquals("cloudflared.exe.part.41", first.fileName.toString())
        assertEquals("cloudflared.exe.part.42", second.fileName.toString())

        Files.writeString(first, "old attempt")
        Files.writeString(second, "new attempt")
        assertTrue(PlayTogetherManager.cleanupCloudflaredTemporaryFile(first))
        assertFalse(Files.exists(first))
        assertTrue(Files.exists(second), "Cleaning one generation must not remove another attempt's temporary file")
        assertTrue(PlayTogetherManager.cleanupCloudflaredTemporaryFile(second))
        assertFalse(Files.exists(second))
    }

    @Test
    fun `bundled WebRTC data channel negotiates locally`() {
        LibDataChannelArchDetect.initialize()

        PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT).use { host ->
            PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT).use { guest ->
                val hostDescription = CompletableFuture<String>()
                val guestDescription = CompletableFuture<String>()
                val guestChannelFuture = CompletableFuture<tel.schich.libdatachannel.DataChannel>()

                host.onGatheringStateChange.register { peer, state ->
                    if (state == GatheringState.RTC_GATHERING_COMPLETE) hostDescription.complete(peer.localDescription())
                }
                guest.onGatheringStateChange.register { peer, state ->
                    if (state == GatheringState.RTC_GATHERING_COMPLETE) guestDescription.complete(peer.localDescription())
                }
                guest.onDataChannel.register { _, channel -> guestChannelFuture.complete(channel) }

                val hostChannel = host.createDataChannel("archivetune-test")
                assertEquals("archivetune-test", hostChannel.label())
                val hostOpen = CompletableFuture<Unit>()
                hostChannel.onOpen.register { hostOpen.complete(Unit) }

                val offer = hostDescription.get(10, TimeUnit.SECONDS)
                guest.setRemoteDescription(offer, SessionDescriptionType.OFFER)
                host.setAnswer(guestDescription.get(10, TimeUnit.SECONDS))

                val guestChannel = guestChannelFuture.get(10, TimeUnit.SECONDS)
                val received = CompletableFuture<String>()
                guestChannel.onMessage.register(
                    DataChannelCallback.Message.handleText { _, text -> received.complete(text) },
                )

                if (!hostChannel.isOpen) hostOpen.get(10, TimeUnit.SECONDS)
                assertTrue(hostChannel.isOpen)

                hostChannel.sendMessage("party-state")
                assertEquals("party-state", received.get(10, TimeUnit.SECONDS))
            }
        }
    }
}

private class RecordingPlaybackController(initial: PlaybackSnapshot) : PlaybackController {
    override val state = MutableStateFlow(initial)
    override val events = MutableSharedFlow<PlaybackEvent>()

    override suspend fun replaceQueue(items: List<PlaybackItem>, startIndex: Int, startPositionMs: Long, title: String?, playWhenReady: Boolean) {
        state.value = state.value.copy(queue = items, currentIndex = startIndex, positionMs = startPositionMs, queueTitle = title, playWhenReady = playWhenReady)
    }
    override suspend fun play() { state.value = state.value.copy(playWhenReady = true) }
    override suspend fun pause() { state.value = state.value.copy(playWhenReady = false) }
    override suspend fun stop() = Unit
    override suspend fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override suspend fun seekToItem(index: Int, positionMs: Long) { state.value = state.value.copy(currentIndex = index, positionMs = positionMs) }
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious(restartThresholdMs: Long) = Unit
    override suspend fun enqueue(items: List<PlaybackItem>) { state.value = state.value.copy(queue = state.value.queue + items) }
    override suspend fun playNext(items: List<PlaybackItem>) = Unit
    override suspend fun removeQueueItem(index: Int) { state.value = state.value.copy(queue = state.value.queue.filterIndexed { i, _ -> i != index }) }
    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) = Unit
    override suspend fun moveQueueItemToNext(index: Int) = Unit
    override suspend fun clearQueue() { state.value = state.value.copy(queue = emptyList(), currentIndex = -1) }
    override suspend fun setRepeatMode(mode: RepeatMode) { state.value = state.value.copy(repeatMode = mode) }
    override suspend fun setShuffleEnabled(enabled: Boolean) { state.value = state.value.copy(shuffleEnabled = enabled) }
    override suspend fun setVolume(volume: Float) { state.value = state.value.copy(volume = volume) }
    override suspend fun setSpeed(speed: Float) { state.value = state.value.copy(speed = speed) }
    override suspend fun retry() = Unit
    override suspend fun clearError() = Unit
    override fun close() = Unit
}

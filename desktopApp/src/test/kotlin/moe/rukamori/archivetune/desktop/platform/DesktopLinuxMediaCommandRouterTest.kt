package moe.rukamori.archivetune.desktop.platform

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.linuxmedia.LinuxMediaCommand
import moe.rukamori.archivetune.linuxmedia.LinuxMediaControls
import moe.rukamori.archivetune.playback.api.*
import kotlin.test.*

class DesktopLinuxMediaCommandRouterTest {
    private class Player {
        val state = MutableStateFlow(PlaybackSnapshot(
            queue = listOf(PlaybackItem(PlaybackMetadata("one", "One"))), currentIndex = 0,
            durationMs = 10000L, positionMs = 5000L, status = PlaybackStatus.READY, playWhenReady = true,
        ))
        val calls = mutableListOf<Pair<String, Any?>>()
        val api = Proxy.newProxyInstance(PlaybackController::class.java.classLoader, arrayOf(PlaybackController::class.java)) { _, method, args ->
            when (method.name) {
                "getState" -> state
                "getEvents" -> MutableSharedFlow<PlaybackEvent>()
                else -> { calls += method.name to args?.firstOrNull(); Unit }
            }
        } as PlaybackController
    }
    @Test fun `desktop transport commands reach the local player`() = runBlocking {
        val player = Player()
        val router = DesktopLinuxMediaCommandRouter(player.api)
        router.handle(LinuxMediaCommand.Transport.PLAY_PAUSE)
        router.handle(LinuxMediaCommand.Transport.NEXT)
        assertEquals(listOf("pause", "skipNext"), player.calls.map { it.first })
    }
    @Test fun `media keys obey shared listening transport policy`() = runBlocking {
        val player = Player()
        var next = 0
        val router = DesktopLinuxMediaCommandRouter(player.api,
            policy = { DesktopPartyPlaybackPolicy(localTransportAllowed = false, persistSpeedAndRepeat = false, persistShuffle = false) },
            partyNext = { next++; true })
        router.handle(LinuxMediaCommand.Transport.NEXT)
        router.handle(LinuxMediaCommand.Rate(2f))
        router.handle(LinuxMediaCommand.Shuffle(true))
        assertEquals(1, next)
        assertTrue(player.calls.isEmpty())
    }
    @Test fun `SetPosition is checked again after the queued track changes`() = runBlocking {
        val player = Player()
        val stale = LinuxMediaControls.trackPath(player.state.value)
        player.state.value = player.state.value.copy(queue = listOf(PlaybackItem(PlaybackMetadata("two", "Two"))))
        DesktopLinuxMediaCommandRouter(player.api).handle(LinuxMediaCommand.SetPosition(stale, 8000))
        assertTrue(player.calls.isEmpty())
    }
    @Test fun `seek handles negative offsets and positive overflow safely`() = runBlocking {
        val player = Player()
        val router = DesktopLinuxMediaCommandRouter(player.api)
        router.handle(LinuxMediaCommand.Seek(Long.MIN_VALUE))
        assertEquals("seekTo" to 0L, player.calls.single())
        player.calls.clear()
        router.handle(LinuxMediaCommand.Seek(Long.MAX_VALUE))
        assertEquals("skipNext", player.calls.single().first)
    }
}

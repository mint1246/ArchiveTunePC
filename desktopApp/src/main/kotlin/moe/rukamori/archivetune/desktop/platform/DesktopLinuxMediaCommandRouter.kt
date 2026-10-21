package moe.rukamori.archivetune.desktop.platform

import moe.rukamori.archivetune.linuxmedia.LinuxMediaCommand
import moe.rukamori.archivetune.linuxmedia.LinuxMediaControls
import moe.rukamori.archivetune.playback.api.PlaybackController

/** Uses the same transport/party rules as the buttons and configurable shortcuts. */
internal class DesktopLinuxMediaCommandRouter(
    private val playback: PlaybackController,
    private val policy: () -> DesktopPartyPlaybackPolicy = { DesktopPartyPlaybackPolicy() },
    private val partyToggle: suspend () -> Boolean = { false },
    private val partyNext: suspend () -> Boolean = { false },
    private val partyPrevious: suspend () -> Boolean = { false },
    private val partySeek: suspend (Long) -> Boolean = { false },
) {
    suspend fun handle(command: LinuxMediaCommand) {
        val rules = policy()
        val snapshot = playback.state.value
        when (command) {
            is LinuxMediaCommand.Transport -> when (command) {
                LinuxMediaCommand.Transport.PLAY -> if (!snapshot.isPlaying) {
                    if (rules.localTransportAllowed) playback.play() else partyToggle()
                }
                LinuxMediaCommand.Transport.PAUSE -> if (snapshot.playWhenReady) {
                    if (rules.localTransportAllowed) playback.pause() else partyToggle()
                }
                LinuxMediaCommand.Transport.PLAY_PAUSE -> {
                    if (!rules.localTransportAllowed) partyToggle()
                    else if (snapshot.playWhenReady) playback.pause() else playback.play()
                }
                LinuxMediaCommand.Transport.STOP -> {
                    if (rules.localTransportAllowed) playback.stop()
                    else if (snapshot.playWhenReady) partyToggle()
                }
                LinuxMediaCommand.Transport.NEXT -> if (rules.localTransportAllowed) playback.skipNext() else partyNext()
                LinuxMediaCommand.Transport.PREVIOUS -> if (rules.localTransportAllowed) playback.skipPrevious() else partyPrevious()
            }
            is LinuxMediaCommand.Seek -> {
                val position = saturatedAdd(snapshot.positionMs, command.offsetMs).coerceAtLeast(0L)
                if (snapshot.currentItem == null) return
                if (snapshot.durationMs > 0 && position > snapshot.durationMs) {
                    if (rules.localTransportAllowed) playback.skipNext() else partyNext()
                } else if (rules.localTransportAllowed) playback.seekTo(position) else partySeek(position)
            }
            is LinuxMediaCommand.SetPosition -> {
                // A track can change after the D-Bus thread accepted the method call.
                if (snapshot.currentItem == null || command.trackPath != LinuxMediaControls.trackPath(snapshot)) return
                if (command.positionMs < 0 || (snapshot.durationMs > 0 && command.positionMs > snapshot.durationMs)) return
                if (rules.localTransportAllowed) playback.seekTo(command.positionMs) else partySeek(command.positionMs)
            }
            is LinuxMediaCommand.Volume -> playback.setVolume(command.volume.coerceIn(0f, 1f))
            is LinuxMediaCommand.Rate -> if (rules.persistSpeedAndRepeat) playback.setSpeed(command.rate)
            is LinuxMediaCommand.Repeat -> if (rules.persistSpeedAndRepeat) playback.setRepeatMode(command.mode)
            is LinuxMediaCommand.Shuffle -> if (rules.persistShuffle) playback.setShuffleEnabled(command.enabled)
        }
    }

    private fun saturatedAdd(value: Long, delta: Long): Long = when {
        delta > 0L && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0L && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> value + delta
    }
}

package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.desktop.analysis.BeatKeyAnalysis
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBeatSyncTest {
    @Test
    fun `samples actual analyzed beat timestamps with smooth attack and decay`() {
        // First detected timestamps from the analyzed Arctic Monkeys - 505 fixture used by the
        // Auto Mix experiments. The changing intervals intentionally exercise local beat timing.
        val beats = floatArrayOf(
            8.661043f,
            9.392471f,
            10.112290f,
            10.820498f,
            11.528708f,
            12.236916f,
        )

        val onBeat = sampleBeatTimeline(beats, beats[1].toDouble() * 1_000.0)
        val middle = sampleBeatTimeline(beats, 9_752.380)
        val beforeNext = sampleBeatTimeline(beats, 10_080.0)

        assertEquals(1, onBeat.beatIndex)
        assertTrue(onBeat.pulse > 0.99f)
        assertTrue(onBeat.phase < 0.001f)
        assertEquals(719.819f, onBeat.beatDurationMs, 0.05f)
        assertTrue(middle.pulse < 0.05f)
        assertTrue(middle.phase in 0.49f..0.51f)
        assertTrue(beforeNext.pulse > 0.45f, "fast attack should already be rising immediately before the next beat")
    }

    @Test
    fun `controller follows seek pause resume speed skip and missing analysis`() {
        val first = item("first")
        val second = item("second")
        val beats = floatArrayOf(40.518822f, 40.959999f, 41.389568f, 41.819138f, 42.248707f, 42.678276f)
        val analysis = BeatKeyAnalysis(139.9, beats, "A", "minor", 0.79f)
        val controller = DesktopBeatSyncController(enabled = true, intensity = 0.75f)

        var now = System.nanoTime()
        controller.onPlaybackSnapshot(playing(first, positionMs = 40_960L, speed = 2f), now)
        controller.installAnalysis(first, analysis)
        now = System.nanoTime()
        controller.onPlaybackSnapshot(playing(first, positionMs = 40_960L, speed = 2f), now)
        controller.renderFrame(now + 100_000_000L)
        assertTrue(controller.beatPhase in 0.44f..0.49f, "2x speed should advance 200 ms through the real local interval")

        now += 200_000_000L
        controller.onPlaybackSnapshot(playing(first, positionMs = 42_678L, speed = 1f), now)
        assertTrue(controller.beatPulse > 0.99f, "seek directly onto a detected beat should peak the pulse")

        now += 20_000_000L
        controller.onPlaybackSnapshot(paused(first, positionMs = 41_600L), now)
        val frozenPhase = controller.beatPhase
        val frozenPulse = controller.beatPulse
        controller.renderFrame(now + 2_000_000_000L)
        assertEquals(frozenPhase, controller.beatPhase, 0.00001f)
        assertEquals(frozenPulse, controller.beatPulse, 0.00001f)

        controller.onPlaybackSnapshot(playing(second, positionMs = 0L), now + 2_100_000_000L)
        assertFalse(controller.hasBeatData)
        assertEquals(0f, controller.beatPulse)

        controller.installAnalysis(
            second,
            BeatKeyAnalysis(0.0, floatArrayOf(), "", "", 0f),
        )
        assertFalse(controller.hasBeatData)
        assertEquals(0f, controller.beatPulse)
    }

    private fun item(id: String) = PlaybackItem(PlaybackMetadata(id = id, title = id, durationHintMs = 180_000L))

    private fun playing(item: PlaybackItem, positionMs: Long, speed: Float = 1f) = PlaybackSnapshot(
        queue = listOf(item),
        currentIndex = 0,
        status = PlaybackStatus.READY,
        playWhenReady = true,
        positionMs = positionMs,
        speed = speed,
    )

    private fun paused(item: PlaybackItem, positionMs: Long) = PlaybackSnapshot(
        queue = listOf(item),
        currentIndex = 0,
        status = PlaybackStatus.READY,
        playWhenReady = false,
        positionMs = positionMs,
    )
}

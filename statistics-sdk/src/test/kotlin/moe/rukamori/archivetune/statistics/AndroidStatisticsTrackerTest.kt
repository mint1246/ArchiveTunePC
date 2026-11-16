package moe.rukamori.archivetune.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AndroidStatisticsTrackerTest {
    @Test
    fun `counts monotonic audible time and ignores paused interval`() {
        val fixture = Fixture()
        fixture.snapshot()
        fixture.advance(10_000L)
        fixture.snapshot(playWhenReady = false)
        fixture.advance(20_000L)
        fixture.snapshot(playWhenReady = true)
        fixture.advance(20_000L)
        fixture.tracker.flush()

        fixture.assertListening(30_000L)
        fixture.assertPlayCount(1)
    }

    @Test
    fun `play threshold is half duration when shorter than thirty seconds`() {
        val fixture = Fixture(song = SONG.copy(durationMs = 20_000L))
        fixture.snapshot()
        fixture.sampleAfter(9_999L)
        assertNull(fixture.sink.plays.singleOrNull())

        fixture.sampleAfter(1L)
        assertEquals(10_000L, fixture.sink.plays.single())
    }

    @Test
    fun `ineligible party interval neither records nor advances play gate`() {
        val fixture = Fixture()
        fixture.snapshot(eligible = false)
        fixture.advance(40_000L)
        fixture.snapshot(eligible = true)
        fixture.sampleAfter(29_999L)

        fixture.assertListening(29_999L)
        fixture.assertPlayCount(0)

        fixture.sampleAfter(1L)
        fixture.assertListening(30_000L)
        fixture.assertPlayCount(1)
    }

    @Test
    fun `same id transition creates a fresh play session`() {
        val fixture = Fixture()
        fixture.snapshot()
        fixture.advance(30_000L)
        fixture.snapshot()
        fixture.tracker.onMediaTransition()
        fixture.snapshot()
        fixture.advance(30_000L)
        fixture.snapshot()

        fixture.assertListening(60_000L)
        fixture.assertPlayCount(2)
    }

    private class Fixture(
        val song: AndroidStatisticsSong = SONG,
    ) {
        val clock = FakeClock()
        val sink = RecordingSink()
        val tracker = AndroidStatisticsTracker(sink, clock)

        fun snapshot(
            playWhenReady: Boolean = true,
            ready: Boolean = true,
            eligible: Boolean = true,
        ) {
            tracker.onSnapshot(
                AndroidStatisticsPlaybackSnapshot(song, playWhenReady = playWhenReady, ready = ready),
                eligible = eligible,
            )
        }

        fun advance(ms: Long) {
            clock.elapsed += ms
            clock.wall += ms
        }

        fun sampleAfter(ms: Long) {
            advance(ms)
            snapshot()
        }

        fun assertListening(expectedMs: Long) {
            assertEquals(expectedMs, sink.totalListeningMs)
        }

        fun assertPlayCount(expectedCount: Int) {
            assertEquals(expectedCount, sink.plays.size)
        }
    }

    private class FakeClock : AndroidStatisticsClock {
        var elapsed = 0L
        var wall = 0L

        override fun elapsedRealtimeMs(): Long = elapsed

        override fun wallTimeEpochMs(): Long = wall
    }

    private class RecordingSink : AndroidStatisticsSink {
        var totalListeningMs = 0L
        val plays = mutableListOf<Long>()

        override fun recordAudible(
            song: AndroidStatisticsSong,
            startEpochMs: Long,
            durationMs: Long,
            playAtEpochMs: Long?,
        ) {
            totalListeningMs += durationMs
            playAtEpochMs?.let(plays::add)
        }
    }

    companion object {
        private val SONG = AndroidStatisticsSong(id = "song", title = "Song", durationMs = 180_000L)
    }
}

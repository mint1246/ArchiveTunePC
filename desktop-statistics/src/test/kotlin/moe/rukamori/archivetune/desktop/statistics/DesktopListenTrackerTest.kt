/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.statistics

import moe.rukamori.archivetune.playback.api.PlaybackAlbum
import moe.rukamori.archivetune.playback.api.PlaybackArtist
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopListenTrackerTest {
    @Test
    fun pauseBufferAndResumeOnlyCountReadyPlayingTime() = withFixture { fixture ->
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 0L))
        fixture.advance(10_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 10_000L, playWhenReady = false))

        fixture.advance(20_000L)
        fixture.tracker.onSnapshot(
            fixture.snapshot(positionMs = 10_000L, status = PlaybackStatus.BUFFERING),
        )

        fixture.advance(10_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 10_000L))
        fixture.advance(5_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 15_000L))

        assertEquals(15_000L, fixture.store.totals().listeningTimeMs)
        assertEquals(0L, fixture.store.totals().playCount)
    }

    @Test
    fun eligibilityCanToggleMidTrackAndThresholdUsesOnlyEligibleAudibleTime() = withFixture { fixture ->
        fixture.tracker.onSnapshot(fixture.snapshot(), eligible = true)
        fixture.advance(20_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 20_000L), eligible = false)

        fixture.advance(20_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 40_000L), eligible = true)
        fixture.advance(9_999L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 49_999L), eligible = true)

        assertEquals(29_999L, fixture.store.totals().listeningTimeMs)
        assertEquals(0L, fixture.store.totals().playCount)

        fixture.advance(1L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 50_000L), eligible = true)
        assertEquals(30_000L, fixture.store.totals().listeningTimeMs)
        assertEquals(1L, fixture.store.totals().playCount)
    }

    @Test
    fun seekingLikePositionJumpsAndPlaybackSpeedDoNotChangeRealTimeCounted() = withFixture { fixture ->
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 0L, speed = 2f))
        fixture.advance(1_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 300_000L, speed = 2f))
        fixture.advance(2_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 5_000L, speed = 0.5f))

        assertEquals(3_000L, fixture.store.totals().listeningTimeMs)
        assertEquals(0L, fixture.store.totals().playCount)
    }

    @Test
    fun wallClockJumpOnlyMovesTheBucketAndDoesNotChangeElapsedListeningTime() = withFixture { fixture ->
        fixture.tracker.onSnapshot(fixture.snapshot())
        fixture.clock.advanceElapsed(5_000L)
        fixture.clock.advanceWall(86_400_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 5_000L))

        assertEquals(5_000L, fixture.store.totals().listeningTimeMs)
    }

    @Test
    fun thresholdCountsOncePerTrackSessionAndCanResetForSameSongRepeat() = withFixture { fixture ->
        fixture.tracker.onSnapshot(fixture.snapshot())
        fixture.advance(60_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 60_000L))
        fixture.advance(60_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 120_000L))
        assertEquals(1L, fixture.store.totals().playCount)

        fixture.tracker.onMediaTransition()
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 0L))
        fixture.advance(30_000L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 30_000L))
        assertEquals(2L, fixture.store.totals().playCount)
    }

    @Test
    fun shortTrackCountsAfterHalfItsDuration() = withFixture { fixture ->
        fixture.tracker.onSnapshot(fixture.snapshot(durationMs = 20_000L))
        fixture.advance(9_999L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 9_999L, durationMs = 20_000L))
        assertEquals(0L, fixture.store.totals().playCount)

        fixture.advance(1L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 10_000L, durationMs = 20_000L))
        assertEquals(1L, fixture.store.totals().playCount)
    }

    @Test
    fun longTrackStillUsesThirtySecondMaximumThreshold() = withFixture { fixture ->
        fixture.tracker.onSnapshot(fixture.snapshot(durationMs = 180_000L))
        fixture.advance(29_999L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 29_999L, durationMs = 180_000L))
        assertEquals(0L, fixture.store.totals().playCount)

        fixture.advance(1L)
        fixture.tracker.onSnapshot(fixture.snapshot(positionMs = 30_000L, durationMs = 180_000L))
        assertEquals(1L, fixture.store.totals().playCount)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val file = Files.createTempDirectory("archivetune-statistics-tracker").resolve("statistics.json")
        val clock = FakeClock(
            elapsedMs = 1_000L,
            wallMs = Instant.parse("2026-08-27T10:00:00Z").toEpochMilli(),
        )
        DesktopStatisticsStore(
            file = file,
            zoneId = ZoneOffset.UTC,
            writeMode = StatisticsWriteMode.Immediate,
        ).use { store ->
            block(Fixture(store, DesktopListenTracker(store, clock), clock))
        }
    }

    private data class Fixture(
        val store: DesktopStatisticsStore,
        val tracker: DesktopListenTracker,
        val clock: FakeClock,
    ) {
        fun advance(ms: Long) = clock.advance(ms)

        fun snapshot(
            positionMs: Long = 0L,
            playWhenReady: Boolean = true,
            status: PlaybackStatus = PlaybackStatus.READY,
            speed: Float = 1f,
            durationMs: Long = 360_000L,
        ): PlaybackSnapshot =
            PlaybackSnapshot(
                queue = listOf(SONG),
                currentIndex = 0,
                playWhenReady = playWhenReady,
                status = status,
                positionMs = positionMs,
                durationMs = durationMs,
                speed = speed,
            )
    }

    private class FakeClock(
        private var elapsedMs: Long,
        private var wallMs: Long,
    ) : StatisticsClock {
        override fun elapsedRealtimeMs(): Long = elapsedMs

        override fun wallTimeEpochMs(): Long = wallMs

        fun advance(ms: Long) {
            elapsedMs += ms
            wallMs += ms
        }

        fun advanceElapsed(ms: Long) {
            elapsedMs += ms
        }

        fun advanceWall(ms: Long) {
            wallMs += ms
        }
    }

    private companion object {
        val SONG = PlaybackItem(
            PlaybackMetadata(
                id = "song-1",
                title = "Song One",
                artists = listOf(PlaybackArtist("artist-1", "Artist One")),
                album = PlaybackAlbum("album-1", "Album One"),
            ),
        )
    }
}

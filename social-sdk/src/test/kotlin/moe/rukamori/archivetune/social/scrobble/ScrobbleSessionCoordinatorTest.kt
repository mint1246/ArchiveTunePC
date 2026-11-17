/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.social.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobbleSessionCoordinatorTest {
    private class FakeSink : ScrobbleSink {
        val scrobbles = mutableListOf<Pair<ScrobbleTrack, Long>>()
        val nowPlaying = mutableListOf<ScrobbleTrack>()
        var scrobbleResult: Result<Unit> = Result.success(Unit)
        var nowPlayingResult: Result<Unit> = Result.success(Unit)
        var throwOnScrobble: Throwable? = null

        override suspend fun scrobble(
            track: ScrobbleTrack,
            startedAtEpochSeconds: Long,
        ): Result<Unit> {
            throwOnScrobble?.let { throw it }
            scrobbles += track to startedAtEpochSeconds
            return scrobbleResult
        }

        override suspend fun updateNowPlaying(track: ScrobbleTrack): Result<Unit> {
            nowPlaying += track
            return nowPlayingResult
        }
    }

    @Test
    fun computeScrobbleDelayMillis_respectsMinimumAndMaxCap() {
        assertNull(computeScrobbleDelayMillis(durationSeconds = 30, minSongDurationSeconds = 30, delayPercent = 0.5f, maxDelaySeconds = 180))
        assertNull(computeScrobbleDelayMillis(durationSeconds = 15, minSongDurationSeconds = 30, delayPercent = 0.5f, maxDelaySeconds = 180))
        assertEquals(60_000L, computeScrobbleDelayMillis(durationSeconds = 120, minSongDurationSeconds = 30, delayPercent = 0.5f, maxDelaySeconds = 180))
        assertEquals(180_000L, computeScrobbleDelayMillis(durationSeconds = 600, minSongDurationSeconds = 30, delayPercent = 0.5f, maxDelaySeconds = 180))
    }

    @Test
    fun scrobbleTrack_formatsArtists() {
        val track = ScrobbleTrack(title = "Song", artists = listOf("Artist A", "Artist B"), durationSeconds = 180)
        assertEquals("Artist A, Artist B", track.formattedArtistString())
    }

    @Test
    fun onSongStart_scrobblesAtThresholdAndUpdatesNowPlaying() = runTest {
        val sink = FakeSink()
        var nowMs = 1_700_000_000_000L
        val coordinator =
            ScrobbleSessionCoordinator(
                scope = this,
                sink = sink,
                nowEpochMillis = { nowMs },
            )
        val track = ScrobbleTrack(title = "Track", artists = listOf("Artist"), durationSeconds = 100)

        coordinator.onSongStart(track)
        runCurrent()
        assertEquals(listOf(track), sink.nowPlaying)
        assertTrue(sink.scrobbles.isEmpty())

        advanceTimeBy(49_999L)
        runCurrent()
        assertTrue(sink.scrobbles.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(track to 1_700_000_000L), sink.scrobbles)
    }

    @Test
    fun pauseAndResume_deductsElapsedTime() = runTest {
        val sink = FakeSink()
        var nowMs = 1_000_000L
        val coordinator =
            ScrobbleSessionCoordinator(
                scope = this,
                sink = sink,
                nowEpochMillis = { nowMs },
            )
        val track = ScrobbleTrack(title = "Track", artists = listOf("Artist"), durationSeconds = 100)

        coordinator.onSongStart(track)
        runCurrent()

        nowMs += 20_000L
        advanceTimeBy(20_000L)
        coordinator.onSongPause()
        runCurrent()

        // Advancing while paused must not trigger scrobble
        advanceTimeBy(100_000L)
        runCurrent()
        assertTrue(sink.scrobbles.isEmpty())

        coordinator.onSongResume(track)
        advanceTimeBy(29_999L)
        runCurrent()
        assertTrue(sink.scrobbles.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(track to 1_000L), sink.scrobbles)
    }

    @Test
    fun onSongStopAndDestroy_cancelPendingScrobble() = runTest {
        val sink = FakeSink()
        val coordinator =
            ScrobbleSessionCoordinator(
                scope = this,
                sink = sink,
                nowEpochMillis = { 5_000L },
            )
        val track = ScrobbleTrack(title = "Track", artists = listOf("Artist"), durationSeconds = 100)

        coordinator.onSongStart(track)
        advanceTimeBy(10_000L)
        coordinator.onSongStop()
        advanceTimeBy(100_000L)
        runCurrent()
        assertTrue(sink.scrobbles.isEmpty())

        coordinator.onSongStart(track)
        coordinator.close()
        advanceTimeBy(100_000L)
        runCurrent()
        assertTrue(sink.scrobbles.isEmpty())
    }

    @Test
    fun onPlayerStateChanged_startsPausesResumesAndUsesDurationOverride() = runTest {
        val sink = FakeSink()
        var nowMs = 2_000_000L
        val coordinator =
            ScrobbleSessionCoordinator(
                scope = this,
                sink = sink,
                nowEpochMillis = { nowMs },
            ).apply {
                useNowPlaying = false
            }
        val track = ScrobbleTrack(title = "Track", artists = listOf("Artist"), durationSeconds = 0)

        coordinator.onPlayerStateChanged(isPlaying = true, track = track, durationOverrideMillis = 80_000L)
        runCurrent()
        assertTrue(sink.nowPlaying.isEmpty())

        nowMs += 10_000L
        advanceTimeBy(10_000L)
        coordinator.onPlayerStateChanged(isPlaying = false, track = track)
        runCurrent()

        coordinator.onPlayerStateChanged(isPlaying = true, track = track)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(listOf(track to 2_000L), sink.scrobbles)
    }

    @Test
    fun shortTrack_isIgnoredForScrobbleButUpdatesNowPlaying() = runTest {
        val sink = FakeSink()
        val coordinator = ScrobbleSessionCoordinator(scope = this, sink = sink)
        val shortTrack = ScrobbleTrack(title = "Interlude", artists = listOf("Artist"), durationSeconds = 30)

        coordinator.onSongStart(shortTrack)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(listOf(shortTrack), sink.nowPlaying)
        assertTrue(sink.scrobbles.isEmpty())
    }

    @Test
    fun errorsAreReported_andCancellationExceptionIsNeverSwallowed() = runTest {
        val sink = FakeSink()
        val errors = mutableListOf<Pair<String, Throwable>>()
        val coordinator =
            ScrobbleSessionCoordinator(
                scope = this,
                sink = sink,
                onError = { op, _, err -> errors += op to err },
            )
        val track = ScrobbleTrack(title = "Track", artists = listOf("Artist"), durationSeconds = 100)

        val failure = IllegalStateException("boom")
        sink.nowPlayingResult = Result.failure(failure)
        sink.scrobbleResult = Result.failure(failure)

        coordinator.onSongStart(track)
        advanceTimeBy(50_000L)
        runCurrent()

        assertEquals(2, errors.size)
        assertEquals("updateNowPlaying", errors[0].first)
        assertEquals("scrobble", errors[1].first)

        // CancellationException must not be reported to onError
        errors.clear()
        sink.nowPlayingResult = Result.success(Unit)
        sink.throwOnScrobble = CancellationException("cancelled")
        coordinator.onSongStart(track)
        advanceTimeBy(50_000L)
        runCurrent()
        assertTrue(errors.isEmpty())
    }
}

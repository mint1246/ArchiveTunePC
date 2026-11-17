/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.social.scrobble.ScrobbleSink
import moe.rukamori.archivetune.social.scrobble.ScrobbleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleManagerTest {
    private class RecordingSink : ScrobbleSink {
        val scrobbles = mutableListOf<Pair<ScrobbleTrack, Long>>()
        val nowPlaying = mutableListOf<ScrobbleTrack>()
        val firstScrobble = CompletableDeferred<Pair<ScrobbleTrack, Long>>()
        val firstNowPlaying = CompletableDeferred<ScrobbleTrack>()

        override suspend fun scrobble(
            track: ScrobbleTrack,
            startedAtEpochSeconds: Long,
        ): Result<Unit> {
            val pair = track to startedAtEpochSeconds
            scrobbles += pair
            firstScrobble.complete(pair)
            return Result.success(Unit)
        }

        override suspend fun updateNowPlaying(track: ScrobbleTrack): Result<Unit> {
            nowPlaying += track
            firstNowPlaying.complete(track)
            return Result.success(Unit)
        }
    }

    private fun testMetadata(durationSeconds: Int = 120): MediaMetadata =
        MediaMetadata(
            id = "song-1",
            title = "Test Title",
            artists =
                listOf(
                    MediaMetadata.Artist(id = "a1", name = "Artist One"),
                    MediaMetadata.Artist(id = "a2", name = "Artist Two"),
                ),
            duration = durationSeconds,
            album = MediaMetadata.Album(id = "alb-1", title = "Test Album"),
        )

    @Test
    fun delegatesSettingsAndMapsMetadataToScrobbleTrack() = runBlocking {
        val sink = RecordingSink()
        val manager =
            ScrobbleManager(
                scope = this,
                sink = sink,
                minSongDuration = 30,
                scrobbleDelayPercent = 0.5f,
                scrobbleDelaySeconds = 180,
                nowEpochMillis = { 1_700_000_000_000L },
            )

        assertTrue(manager.useNowPlaying)
        manager.useNowPlaying = false
        assertFalse(manager.useNowPlaying)

        manager.minSongDuration = 20
        manager.scrobbleDelayPercent = 0.0002f
        manager.scrobbleDelaySeconds = 0

        val metadata = testMetadata(durationSeconds = 100)
        manager.onSongStart(metadata)

        val (track, startedAt) = withTimeout(5_000L) { sink.firstScrobble.await() }
        assertTrue(sink.nowPlaying.isEmpty())
        assertEquals("Test Title", track.title)
        assertEquals(listOf("Artist One", "Artist Two"), track.artists)
        assertEquals(100, track.durationSeconds)
        assertEquals("Test Album", track.albumTitle)
        assertEquals(1_700_000_000L, startedAt)
        manager.destroy()
    }

    @Test
    fun durationOverrideMillisOverridesMetadataDuration() = runBlocking {
        val sink = RecordingSink()
        val manager =
            ScrobbleManager(
                scope = this,
                sink = sink,
                minSongDuration = 30,
                scrobbleDelayPercent = 0.0002f,
                scrobbleDelaySeconds = 0,
                nowEpochMillis = { 10_000L },
            )

        // Metadata duration is 0 (<= minSongDuration), but player duration override is 80s (> 30s)
        val metadata = testMetadata(durationSeconds = 0)
        manager.onPlayerStateChanged(isPlaying = true, metadata = metadata, duration = 80_000L)

        withTimeout(5_000L) {
            sink.firstNowPlaying.await()
            sink.firstScrobble.await()
        }
        assertEquals(1, sink.nowPlaying.size)
        assertEquals(1, sink.scrobbles.size)

        manager.onSongStop()
    }
}

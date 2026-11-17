/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadLibraryAggregatorTest {
    private data class TestSong(val title: String, val artist: String)

    private data class TestMetadata(val id: String, val title: String, val artist: String?)

    private fun aggregator() =
        DownloadLibraryAggregator<TestSong, TestMetadata> { source, id, fallbackTitle ->
            source?.let { TestMetadata(id, it.title, it.artist) }
                ?: TestMetadata(id, fallbackTitle, null)
        }

    private fun download(
        id: String,
        state: DownloadState,
        bytes: Long = 0L,
        percent: Float = -1f,
        updated: Long = 0L,
        title: String = "Title-$id",
        failureReason: Int = 0,
    ) =
        DownloadSnapshot(
            id = id,
            state = state,
            bytesDownloaded = bytes,
            contentLength = 1_000L,
            percentDownloaded = percent,
            updateTimeMs = updated,
            stopReason = 0,
            failureReason = failureReason,
            title = title,
        )

    private fun song(
        id: String,
        albumId: String? = null,
        artist: String = "Artist-$id",
    ) =
        DownloadSongSource(
            source = TestSong("Song-$id", artist),
            id = id,
            title = "Song-$id",
            artistsText = artist,
            thumbnailUrl = "thumb-$id",
            albumId = albumId,
            durationSeconds = 180,
        )

    private fun group(
        id: String,
        mediaType: DownloadMediaType,
    ) =
        DownloadGroup(
            id = id,
            mediaType = mediaType,
            title = "Group-$id",
            supportingText = null,
            thumbnailUrl = null,
            destinationRoute = "route/$id",
        )

    @Test
    fun completedDownloads_formPlaylistAlbumAndSongSectionsInOrder() {
        val result =
            aggregator().aggregate(
                DownloadLibraryInput(
                    songs =
                        listOf(
                            song("s1", albumId = "a1"),
                            song("s2", albumId = "a1"),
                        ),
                    groups =
                        listOf(
                            group("a1", DownloadMediaType.ALBUM),
                            group("p1", DownloadMediaType.PLAYLIST),
                        ),
                    songIdsByGroup = mapOf("p1" to listOf("s1", "s2")),
                    downloads =
                        mapOf(
                            "s1" to download("s1", DownloadState.COMPLETED, updated = 10L),
                            "s2" to download("s2", DownloadState.COMPLETED, updated = 20L),
                        ),
                    nowMs = 1_000L,
                ),
            )

        assertEquals(
            listOf(DownloadMediaType.PLAYLIST, DownloadMediaType.ALBUM, DownloadMediaType.SONG),
            result.downloadedSections.map { it.mediaType },
        )
        assertTrue(result.progressSections.isEmpty())
        assertFalse(result.isEmpty)

        val playlist = result.downloadedSections[0].entries.single()
        assertEquals("playlist:p1", playlist.id)
        assertEquals(listOf("s1", "s2"), playlist.songIds)
        assertEquals(2, playlist.children.size)
        assertEquals(1f, playlist.progress)
        assertEquals(100, playlist.percent)
        assertFalse(playlist.paused)
        assertFalse(playlist.failed)
        assertEquals("route/p1", playlist.destinationRoute)

        val album = result.downloadedSections[1].entries.single()
        assertEquals("album:a1", album.id)
        assertEquals(listOf("s1", "s2"), album.songIds)
        assertEquals("Song-s1", album.children.first().playbackMetadata?.title)

        val songs = result.downloadedSections[2].entries
        assertEquals(listOf("song:s2", "song:s1"), songs.map { it.id })
    }

    @Test
    fun inProgressDownloads_formProgressSectionsAndKeepRequestTitleFallback() {
        val result =
            aggregator().aggregate(
                DownloadLibraryInput(
                    songs = listOf(song("known")),
                    groups = emptyList(),
                    songIdsByGroup = emptyMap(),
                    downloads =
                        mapOf(
                            "known" to download("known", DownloadState.DOWNLOADING, percent = 25f, bytes = 100L),
                            "unknown" to download("unknown", DownloadState.QUEUED, title = "Queued title"),
                        ),
                    nowMs = 1_000L,
                ),
            )

        assertTrue(result.downloadedSections.isEmpty())
        val progress = result.progressSections.single()
        assertEquals(DownloadMediaType.SONG, progress.mediaType)
        assertEquals(2, progress.entries.size)
        val unknown = progress.entries.single { it.id == "song:unknown" }
        assertEquals("Queued title", unknown.title)
        assertEquals(TestMetadata("unknown", "Queued title", null), unknown.playbackMetadata)
        assertEquals(0.25f, progress.entries.single { it.id == "song:known" }.progress)
    }

    @Test
    fun pausedAndFailedStates_areVisibleInProgressAndDriveFlags() {
        val result =
            aggregator().aggregate(
                DownloadLibraryInput(
                    songs = emptyList(),
                    groups = emptyList(),
                    songIdsByGroup = emptyMap(),
                    downloads =
                        mapOf(
                            "paused" to download("paused", DownloadState.STOPPED),
                            "failed" to download("failed", DownloadState.FAILED, failureReason = 1),
                        ),
                    nowMs = 1_000L,
                ),
            )

        val entries = result.progressSections.single().entries
        assertTrue(entries.single { it.id == "song:paused" }.paused)
        assertTrue(entries.single { it.id == "song:failed" }.failed)
        assertTrue(result.progressSections.single().paused)
    }

    @Test
    fun singleSongCollection_isNotReportedAsPlaylistOrAlbum() {
        val result =
            aggregator().aggregate(
                DownloadLibraryInput(
                    songs = listOf(song("s1", albumId = "a1")),
                    groups = listOf(group("a1", DownloadMediaType.ALBUM)),
                    songIdsByGroup = mapOf("p1" to listOf("s1")),
                    downloads = mapOf("s1" to download("s1", DownloadState.COMPLETED)),
                    nowMs = 1_000L,
                ),
            )

        assertEquals(listOf(DownloadMediaType.SONG), result.downloadedSections.map { it.mediaType })
    }

    @Test
    fun speedSampler_usesMonotonicDeltasAndHoldsRepeatedSamples() {
        val orchestrator = aggregator()
        val first =
            orchestrator.aggregate(
                DownloadLibraryInput(emptyList(), emptyList(), emptyMap(), mapOf("s1" to download("s1", DownloadState.DOWNLOADING, bytes = 1_000L)), 1_000L),
            )
        assertEquals(0L, first.progressSections.single().speedBytesPerSecond)

        val second =
            orchestrator.aggregate(
                DownloadLibraryInput(emptyList(), emptyList(), emptyMap(), mapOf("s1" to download("s1", DownloadState.DOWNLOADING, bytes = 3_000L)), 1_500L),
            )
        assertEquals(4_000L, second.progressSections.single().speedBytesPerSecond)

        val held =
            orchestrator.aggregate(
                DownloadLibraryInput(emptyList(), emptyList(), emptyMap(), mapOf("s1" to download("s1", DownloadState.DOWNLOADING, bytes = 3_000L)), 2_000L),
            )
        assertEquals(4_000L, held.progressSections.single().speedBytesPerSecond)

        val stalled =
            orchestrator.aggregate(
                DownloadLibraryInput(emptyList(), emptyList(), emptyMap(), mapOf("s1" to download("s1", DownloadState.DOWNLOADING, bytes = 3_000L)), 5_000L),
            )
        assertEquals(0L, stalled.progressSections.single().speedBytesPerSecond)
    }

    @Test
    fun metadataSourceIsUsedWhenSongIsPresentOtherwiseFallback() {
        val result =
            aggregator().aggregate(
                DownloadLibraryInput(
                    songs = listOf(song("present")),
                    groups = emptyList(),
                    songIdsByGroup = emptyMap(),
                    downloads =
                        mapOf(
                            "present" to download("present", DownloadState.COMPLETED),
                            "missing" to download("missing", DownloadState.COMPLETED, title = "Fallback"),
                        ),
                    nowMs = 1_000L,
                ),
            )

        val entries = result.downloadedSections.single().entries.associateBy { it.id }
        assertEquals(TestMetadata("present", "Song-present", "Artist-present"), entries.getValue("song:present").playbackMetadata)
        assertEquals(TestMetadata("missing", "Fallback", null), entries.getValue("song:missing").playbackMetadata)
    }

    @Test
    fun unknownAndRemovingStates_areNotVisibleInProgress() {
        assertFalse(DownloadStates.isVisibleInProgress(DownloadState.UNKNOWN))
        assertFalse(DownloadStates.isVisibleInProgress(DownloadState.REMOVING))
        assertTrue(DownloadStates.isTrackedState(DownloadState.COMPLETED))
        assertFalse(DownloadStates.isTrackedState(DownloadState.REMOVING))
    }
}

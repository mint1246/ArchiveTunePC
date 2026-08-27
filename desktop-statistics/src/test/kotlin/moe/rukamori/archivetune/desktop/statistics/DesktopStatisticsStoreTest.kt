/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.statistics

import org.json.JSONObject
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopStatisticsStoreTest {
    @Test
    fun listeningIsSplitAcrossLocalHourAndDayBuckets() {
        val file = Files.createTempDirectory("archivetune-statistics-buckets").resolve("statistics.json")
        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Debounced(60_000L)).use { store ->
            val start = Instant.parse("2026-08-27T23:59:50Z").toEpochMilli()
            store.recordAudible(SONG_ONE, start, 20_000L, start + 10_000L)

            val hours = store.hourlyTrend()
            assertEquals(2, hours.size)
            assertEquals(10_000L, hours[0].listeningTimeMs)
            assertEquals(10_000L, hours[1].listeningTimeMs)
            assertEquals(0L, hours[0].playCount)
            assertEquals(1L, hours[1].playCount)

            val days = store.dailyTrend()
            assertEquals(2, days.size)
            assertEquals(10_000L, days[0].listeningTimeMs)
            assertEquals(10_000L, days[1].listeningTimeMs)
        }
    }

    @Test
    fun rangeQueriesAggregateTotalsAndRankings() {
        val file = Files.createTempDirectory("archivetune-statistics-range").resolve("statistics.json")
        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Immediate).use { store ->
            val dayOne = Instant.parse("2026-08-25T12:00:00Z").toEpochMilli()
            val dayTwo = Instant.parse("2026-08-26T12:00:00Z").toEpochMilli()
            store.recordAudible(SONG_ONE, dayOne, 40_000L, dayOne + 30_000L)
            store.recordAudible(SONG_TWO, dayTwo, 70_000L, dayTwo + 30_000L)

            val firstDay = StatisticsRange.between(
                Instant.parse("2026-08-25T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-08-26T00:00:00Z").toEpochMilli(),
            )
            val totals = store.totals(firstDay)
            assertEquals(40_000L, totals.listeningTimeMs)
            assertEquals(1L, totals.playCount)
            assertEquals(1, totals.uniqueSongs)
            assertEquals(1, totals.uniqueArtists)
            assertEquals(1, totals.uniqueAlbums)
            assertEquals("song-1", store.topSongs(firstDay).single().song.id)
            assertEquals("Artist One", store.topArtists(firstDay).single().artist.name)
            assertEquals("Album One", store.topAlbums(firstDay).single().album.title)

            val all = store.totals()
            assertEquals(110_000L, all.listeningTimeMs)
            assertEquals(2L, all.playCount)
            assertEquals(2, all.uniqueSongs)
        }
    }

    @Test
    fun persistenceKeepsMetadataOnceAndRestoresUnboundedAggregates() {
        val directory = Files.createTempDirectory("archivetune-statistics-persistence")
        val file = directory.resolve("statistics.json")
        val start = Instant.parse("2026-08-20T10:00:00Z").toEpochMilli()

        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Immediate).use { store ->
            repeat(2_101) { index ->
                store.recordAudible(
                    song = SONG_ONE,
                    startEpochMs = start + index * 3_600_000L,
                    durationMs = 1_000L,
                    playAtEpochMs = null,
                )
            }
            store.recordAudible(SONG_ONE, start, 0L, start + 500L)
            assertTrue(store.flush().isSuccess)
        }

        val root = JSONObject(Files.readString(file))
        assertEquals(1, root.getJSONObject("songs").length())
        assertTrue(root.getJSONObject("hours").length() > 2_000)

        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Immediate).use { restored ->
            val totals = restored.totals()
            assertEquals(2_101_000L, totals.listeningTimeMs)
            assertEquals(1L, totals.playCount)
            assertEquals(1, totals.uniqueSongs)
            val song = restored.topSongs().single()
            assertEquals("Song One", song.song.title)
            assertNotNull(song.song.album)
        }
    }

    @Test
    fun dayOfWeekAndHourOfDayQueriesUseLocalCalendarBuckets() {
        val file = Files.createTempDirectory("archivetune-statistics-trends").resolve("statistics.json")
        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Immediate).use { store ->
            val monday9 = Instant.parse("2026-08-24T09:15:00Z").toEpochMilli()
            val monday10 = Instant.parse("2026-08-24T10:15:00Z").toEpochMilli()
            store.recordAudible(SONG_ONE, monday9, 60_000L)
            store.recordAudible(SONG_TWO, monday10, 120_000L)

            val monday = store.dayOfWeekTrend().first { it.dayOfWeek.value == 1 }
            assertEquals(180_000L, monday.listeningTimeMs)
            assertEquals(2, monday.uniqueSongs)

            assertEquals(60_000L, store.hourOfDayTrend()[9].listeningTimeMs)
            assertEquals(120_000L, store.hourOfDayTrend()[10].listeningTimeMs)
        }
    }

    @Test
    fun legacyHistoryImportsOnceAndPersistsMigrationMarker() {
        val file = Files.createTempDirectory("archivetune-statistics-legacy").resolve("statistics.json")
        val start = Instant.parse("2026-08-20T10:00:00Z").toEpochMilli()
        val events = listOf(
            LegacyStatisticsEvent(SONG_ONE, start, 45_000L),
            LegacyStatisticsEvent(SONG_TWO, start + 60_000L, 12_000L),
        )

        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Immediate).use { store ->
            assertTrue(store.importLegacyHistory(events))
            assertFalse(store.importLegacyHistory(events))
            assertEquals(57_000L, store.totals().listeningTimeMs)
            assertEquals(1L, store.totals().playCount)
        }

        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Immediate).use { restored ->
            assertFalse(restored.importLegacyHistory(events))
            assertEquals(57_000L, restored.totals().listeningTimeMs)
            assertEquals(1L, restored.totals().playCount)
        }
    }

    @Test
    fun revisionChangesAfterDurableFlush() {
        val file = Files.createTempDirectory("archivetune-statistics-revision").resolve("statistics.json")
        DesktopStatisticsStore(file, ZoneOffset.UTC, StatisticsWriteMode.Debounced(60_000L)).use { store ->
            val before = store.revision.value
            store.recordAudible(SONG_ONE, Instant.parse("2026-08-27T12:00:00Z").toEpochMilli(), 5_000L)
            assertEquals(before, store.revision.value)
            assertTrue(store.flush().isSuccess)
            assertEquals(before + 1L, store.revision.value)
        }
    }

    private companion object {
        val SONG_ONE = StatisticsSongMetadata(
            id = "song-1",
            title = "Song One",
            artists = listOf(StatisticsArtist("artist-1", "Artist One")),
            album = StatisticsAlbum("album-1", "Album One"),
        )
        val SONG_TWO = StatisticsSongMetadata(
            id = "song-2",
            title = "Song Two",
            artists = listOf(StatisticsArtist("artist-2", "Artist Two")),
            album = StatisticsAlbum("album-2", "Album Two"),
        )
    }
}

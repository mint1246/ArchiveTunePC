package moe.rukamori.archivetune.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AndroidStatisticsQueryServiceTest {
    private val zone = ZoneId.of("Asia/Jerusalem")

    @Test
    fun `splitAcrossHours splits listening across hour boundaries`() {
        val start = LocalDateTime.of(2026, 9, 10, 14, 50).atZone(zone).toInstant().toEpochMilli()
        val hour14 = LocalDateTime.of(2026, 9, 10, 14, 0).atZone(zone).toInstant().toEpochMilli()
        val hour15 = LocalDateTime.of(2026, 9, 10, 15, 0).atZone(zone).toInstant().toEpochMilli()
        val hour16 = LocalDateTime.of(2026, 9, 10, 16, 0).atZone(zone).toInstant().toEpochMilli()

        val slices = mutableListOf<Pair<Long, Long>>()
        // 10 min in 14:00, 60 min in 15:00, 5 min in 16:00 = 75 min total
        splitAcrossHours(start, 75L * 60_000L, zone) { hourStart, sliceMs ->
            slices += hourStart to sliceMs
        }

        assertEquals(
            listOf(
                hour14 to 10L * 60_000L,
                hour15 to 60L * 60_000L,
                hour16 to 5L * 60_000L,
            ),
            slices,
        )
    }

    @Test
    fun `hourStartEpochMs truncates to hour in given zone`() {
        val instant = LocalDateTime.of(2026, 9, 10, 17, 42, 15).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 9, 10, 17, 0, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, hourStartEpochMs(instant, zone))
    }

    @Test
    fun `artistKey prefers non-blank id and normalizes fallback name`() {
        assertEquals("id:art-1", artistKey(AndroidStatisticsArtist(id = "art-1", name = "Artist")))
        assertEquals("name:the artist", artistKey(AndroidStatisticsArtist(id = "  ", name = "  The Artist  ")))
        assertEquals("name:mono", artistKey(AndroidStatisticsArtist(id = null, name = "MONO")))
    }

    @Test
    fun `daily trend fills zero listening days within bounded range`() {
        val day1 = LocalDateTime.of(2026, 9, 8, 10, 0).atZone(zone)
        val day3 = LocalDateTime.of(2026, 9, 10, 21, 0).atZone(zone)
        val range =
            AndroidStatisticsEpochRange(
                fromEpochMs = LocalDateTime.of(2026, 9, 8, 0, 0).atZone(zone).toInstant().toEpochMilli(),
                toEpochMs = LocalDateTime.of(2026, 9, 10, 23, 0).atZone(zone).toInstant().toEpochMilli(),
            )
        val slices =
            listOf(
                AndroidStatisticsHourSlice(day1.toInstant().toEpochMilli(), 120_000L),
                AndroidStatisticsHourSlice(day3.toInstant().toEpochMilli(), 45_000L),
            )

        val formatter = DateTimeFormatter.ofPattern("d MMM")
        val expected =
            listOf(
                AndroidStatisticsBucket(day1.toLocalDate().format(formatter), 120_000L),
                AndroidStatisticsBucket(day1.toLocalDate().plusDays(1).format(formatter), 0L),
                AndroidStatisticsBucket(day3.toLocalDate().format(formatter), 45_000L),
            )

        assertEquals(expected, buildDailyListeningTrend(slices, range, zone))
    }

    @Test
    fun `day of week and hour of day trends preserve sunday-first order and omit zeroes`() {
        // 2026-09-06 is Sunday; 2026-09-07 is Monday
        val sundayMorning = LocalDateTime.of(2026, 9, 6, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val mondayEvening = LocalDateTime.of(2026, 9, 7, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val slices =
            listOf(
                AndroidStatisticsHourSlice(sundayMorning, 10_000L),
                AndroidStatisticsHourSlice(mondayEvening, 25_000L),
            )

        assertEquals(
            listOf(
                AndroidStatisticsBucket("Sun", 10_000L),
                AndroidStatisticsBucket("Mon", 25_000L),
            ),
            buildDayOfWeekListeningTrend(slices, zone),
        )
        assertEquals(
            listOf(
                AndroidStatisticsBucket("08:00", 10_000L),
                AndroidStatisticsBucket("22:00", 25_000L),
            ),
            buildHourOfDayListeningTrend(slices, zone),
        )
    }

    @Test
    fun `query service assembles overview and skips zero-limit media queries`() {
        val now = LocalDateTime.of(2026, 9, 10, 17, 30).atZone(zone).toInstant().toEpochMilli()
        val sliceEpoch = LocalDateTime.of(2026, 9, 10, 17, 0).atZone(zone).toInstant().toEpochMilli()
        val source = FakeQuerySource(zone, listOf(AndroidStatisticsHourSlice(sliceEpoch, 60_000L)))
        val service = AndroidStatisticsQueryService(source, clock = { now })

        val overview = service.overviewForRange(AndroidStatisticsRange.DAYS_7, songLimit = 5, artistLimit = 0, albumLimit = 3)

        assertEquals(1, source.topSongCalls)
        assertEquals(0, source.topArtistCalls)
        assertEquals(1, source.topAlbumCalls)
        assertEquals(1, overview.topSongs.size)
        assertTrue(overview.topArtists.isEmpty())
        assertEquals(1, overview.topAlbums.size)
        assertEquals(7, overview.dailyListening.size)
    }

    @Test
    fun `query service allRangeOverviews covers every range and rejects negative limits`() {
        val now = LocalDateTime.of(2026, 9, 10, 17, 30).atZone(zone).toInstant().toEpochMilli()
        val source = FakeQuerySource(zone, emptyList())
        val service = AndroidStatisticsQueryService(source, clock = { now })

        val all = service.allRangeOverviews()
        assertEquals(AndroidStatisticsRange.entries.toSet(), all.keys)

        assertThrows(IllegalArgumentException::class.java) {
            service.overview(AndroidStatisticsEpochRange(), songLimit = -1)
        }
    }

    private class FakeQuerySource(
        override val zoneId: ZoneId,
        private val slices: List<AndroidStatisticsHourSlice>,
    ) : AndroidStatisticsQuerySource {
        var topSongCalls = 0
        var topArtistCalls = 0
        var topAlbumCalls = 0

        override fun querySummary(range: AndroidStatisticsEpochRange): AndroidStatisticsSummary =
            AndroidStatisticsSummary(totalListeningMs = slices.sumOf { it.listeningMs }, totalPlays = 1L, uniqueSongs = 1)

        override fun queryHourSlices(range: AndroidStatisticsEpochRange): List<AndroidStatisticsHourSlice> = slices

        override fun queryTopSongs(
            range: AndroidStatisticsEpochRange,
            limit: Int,
        ): List<AndroidStatisticsMedia> {
            topSongCalls++
            return listOf(AndroidStatisticsMedia(stableId = "s1", title = "Song 1", listeningMs = 60_000L, playCount = 1L))
        }

        override fun queryTopArtists(
            range: AndroidStatisticsEpochRange,
            limit: Int,
        ): List<AndroidStatisticsMedia> {
            topArtistCalls++
            return listOf(AndroidStatisticsMedia(stableId = "a1", title = "Artist 1", listeningMs = 60_000L, playCount = 1L))
        }

        override fun queryTopAlbums(
            range: AndroidStatisticsEpochRange,
            limit: Int,
        ): List<AndroidStatisticsMedia> {
            topAlbumCalls++
            return listOf(AndroidStatisticsMedia(stableId = "al1", title = "Album 1", listeningMs = 60_000L, playCount = 1L))
        }
    }
}

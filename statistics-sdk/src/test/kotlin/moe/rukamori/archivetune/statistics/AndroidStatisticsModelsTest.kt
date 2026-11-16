package moe.rukamori.archivetune.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AndroidStatisticsModelsTest {
    private val zone = ZoneId.of("Asia/Jerusalem")

    @Test
    fun `seven day range starts six calendar days before today`() {
        val now = LocalDateTime.of(2026, 9, 10, 17, 30).atZone(zone).toInstant().toEpochMilli()
        val range = AndroidStatisticsRange.DAYS_7.toEpochRange(now, zone)
        val expected = LocalDateTime.of(2026, 9, 4, 0, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, range.fromEpochMs)
        assertEquals(now + 1L, range.toEpochMs)
    }

    @Test
    fun `calendar month ranges preserve desktop semantics`() {
        val now = LocalDateTime.of(2026, 9, 10, 17, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            LocalDateTime.of(2026, 8, 10, 0, 0).atZone(zone).toInstant().toEpochMilli(),
            AndroidStatisticsRange.MONTH_1.toEpochRange(now, zone).fromEpochMs,
        )
        assertEquals(
            LocalDateTime.of(2026, 3, 10, 0, 0).atZone(zone).toInstant().toEpochMilli(),
            AndroidStatisticsRange.MONTHS_6.toEpochRange(now, zone).fromEpochMs,
        )
    }

    @Test
    fun `all time has open bounds`() {
        val range = AndroidStatisticsRange.ALL_TIME.toEpochRange(123L, zone)
        assertNull(range.fromEpochMs)
        assertNull(range.toEpochMs)
    }

    @Test
    fun `effective threshold is min thirty seconds and half duration`() {
        assertEquals(30_000L, effectivePlayThresholdMs(240_000L))
        assertEquals(10_000L, effectivePlayThresholdMs(20_000L))
        assertEquals(1L, effectivePlayThresholdMs(1L))
        assertEquals(30_000L, effectivePlayThresholdMs(null))
    }
}

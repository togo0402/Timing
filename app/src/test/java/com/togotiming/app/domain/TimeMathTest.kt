package com.togotiming.app.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeMathTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun dayBoundsAreLocalMidnight() {
        val noon = at(2026, 1, 15, 12, 30)
        assertEquals(at(2026, 1, 15, 0, 0), TimeMath.dayStart(noon, zone))
        assertEquals(at(2026, 1, 16, 0, 0), TimeMath.nextDayStart(noon, zone))
    }

    @Test
    fun weekStartsOnMonday() {
        // 2026-01-15 是周四
        val thursday = at(2026, 1, 15, 12, 0)
        assertEquals(at(2026, 1, 12, 0, 0), TimeMath.weekStart(thursday, zone))
        val sunday = at(2026, 1, 18, 23, 0)
        assertEquals(at(2026, 1, 12, 0, 0), TimeMath.weekStart(sunday, zone))
        val monday = at(2026, 1, 12, 0, 1)
        assertEquals(at(2026, 1, 12, 0, 0), TimeMath.weekStart(monday, zone))
    }

    @Test
    fun monthStartIsFirstDay() {
        assertEquals(at(2026, 3, 1, 0, 0), TimeMath.monthStart(at(2026, 3, 31, 23, 59), zone))
    }

    @Test
    fun clippedMillisHandlesPartialOverlap() {
        val from = at(2026, 1, 15, 0, 0)
        val to = at(2026, 1, 16, 0, 0)
        // 前一天 23:00 ~ 当天 01:00 → 只算 1 小时
        val start = at(2026, 1, 14, 23, 0)
        val end = at(2026, 1, 15, 1, 0)
        assertEquals(60 * 60_000L, TimeMath.clippedMillis(start, end, from, to))
        // 完全在区间外
        assertEquals(
            0L,
            TimeMath.clippedMillis(at(2026, 1, 13, 0, 0), at(2026, 1, 13, 2, 0), from, to)
        )
    }

    @Test
    fun activeRecordCountsToNow() {
        val from = at(2026, 1, 15, 0, 0)
        val now = at(2026, 1, 15, 1, 0)
        val start = at(2026, 1, 15, 0, 30)
        assertEquals(30 * 60_000L, TimeMath.clippedMillis(start, null, from, from + 86_400_000L, now))
    }

    @Test
    fun splitByDaySplitsAcrossMidnight() {
        val start = at(2026, 1, 14, 23, 0)
        val end = at(2026, 1, 15, 2, 0)
        val segments = TimeMath.splitByDay(start, end, zone)
        assertEquals(2, segments.size)
        assertEquals(start, segments[0].from)
        assertEquals(at(2026, 1, 15, 0, 0), segments[0].to)
        assertEquals(at(2026, 1, 15, 0, 0), segments[1].from)
        assertEquals(end, segments[1].to)
    }

    @Test
    fun snapRoundsToFiveMinutes() {
        val base = at(2026, 1, 15, 10, 2)
        val snapped = TimeMath.snap(base, TimeMath.FIVE_MINUTES)
        assertEquals(0L, snapped % TimeMath.FIVE_MINUTES)
        assertEquals(-2 * 60_000L, snapped - base)

        val base2 = at(2026, 1, 15, 10, 3)
        val snapped2 = TimeMath.snap(base2, TimeMath.FIVE_MINUTES)
        assertEquals(2 * 60_000L, snapped2 - base2)
    }

    @Test
    fun formatsDurations() {
        assertEquals("01:30", TimeMath.formatDuration(90 * 60_000L))
        assertEquals("00:05", TimeMath.formatDuration(5 * 60_000L))
        assertEquals("25:00", TimeMath.formatDuration(25 * 3_600_000L))
        assertEquals("01:30:05", TimeMath.formatDurationSeconds(90 * 60_000L + 5_000L))
    }
}

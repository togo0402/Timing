package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsAggregatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(h: Int, m: Int = 0): Long =
        ZonedDateTime.of(2026, 1, 15, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val rootA = ActivityTypeEntity(id = 1, name = "学习", colorArgb = 0xFF1E88E5.toInt(), path = "学习")
    private val childA1 = ActivityTypeEntity(
        id = 2, parentId = 1, name = "英语", colorArgb = 0xFF42A5F5.toInt(), depth = 1, path = "学习/英语"
    )
    private val childA2 = ActivityTypeEntity(
        id = 3, parentId = 1, name = "数学", colorArgb = 0xFF64B5F6.toInt(), depth = 1, path = "学习/数学"
    )
    private val rootB = ActivityTypeEntity(id = 4, name = "工作", colorArgb = 0xFFE53935.toInt(), path = "工作")
    private val types = listOf(rootA, childA1, childA2, rootB)

    @Test
    fun byRootSumsChildren() {
        val records = listOf(
            TimeRecordEntity(id = 1, typeId = 2, startAt = at(9), endAt = at(9, 30)),
            TimeRecordEntity(id = 2, typeId = 3, startAt = at(10), endAt = at(10, 30)),
            TimeRecordEntity(id = 3, typeId = 4, startAt = at(13), endAt = at(14)),
        )
        val totals = StatsAggregator.byRoot(records, types, at(0), at(0).plus(86_400_000L))
        assertEquals(2, totals.size)
        val byId = totals.associateBy { it.type.id }
        assertEquals(60 * 60_000L, byId.getValue(1L).millis)
        assertEquals(60 * 60_000L, byId.getValue(4L).millis)
    }

    @Test
    fun byRootClipsToRange() {
        val records = listOf(
            // 前一天 23:00 ~ 当天 01:00，只算当天 1 小时
            TimeRecordEntity(id = 1, typeId = 1, startAt = at(0) - 60 * 60_000L, endAt = at(1)),
        )
        val totals = StatsAggregator.byRoot(
            records, types,
            at(0), at(0).plus(86_400_000L),
            now = at(12),
        )
        assertEquals(60 * 60_000L, totals.first().millis)
    }

    @Test
    fun activeRecordCountsToNow() {
        val records = listOf(
            TimeRecordEntity(id = 1, typeId = 1, startAt = at(9), endAt = null),
        )
        val totals = StatsAggregator.byRoot(
            records, types,
            at(0), at(0).plus(86_400_000L),
            now = at(10),
        )
        assertEquals(60 * 60_000L, totals.first().millis)
    }

    @Test
    fun childrenTotalsSplitsRoot() {
        val records = listOf(
            TimeRecordEntity(id = 1, typeId = 2, startAt = at(9), endAt = at(9, 30)),
            TimeRecordEntity(id = 2, typeId = 3, startAt = at(10), endAt = at(10, 15)),
        )
        val totals = StatsAggregator.childrenTotals(
            records, types, rootId = 1,
            from = at(0), to = at(0).plus(86_400_000L), now = at(12),
        )
        assertEquals(2, totals.size)
        assertEquals(2L, totals[0].type.id)
        assertEquals(30 * 60_000L, totals[0].millis)
        assertEquals(15 * 60_000L, totals[1].millis)
    }

    @Test
    fun percentIsShareOfTotal() {
        val totals = listOf(TypeTotal(rootA, 75), TypeTotal(rootB, 25))
        assertEquals(0.75f, totals[0].percent(100), 0.001f)
        assertEquals(0.25f, totals[1].percent(100), 0.001f)
        assertEquals(0f, totals[0].percent(0), 0.001f)
    }

    @Test
    fun rootOfWalksToTop() {
        val map = types.associateBy { it.id }
        assertEquals(1L, StatsAggregator.rootOf(childA1, map).id)
        assertEquals(4L, StatsAggregator.rootOf(rootB, map).id)
    }
}

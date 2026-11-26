package com.togotiming.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 时间计算工具：全部以 UTC 毫秒存储，按本地时区展示 / 切日。 */
object TimeMath {

    val zone: ZoneId get() = ZoneId.systemDefault()

    fun dayStart(ms: Long, zone: ZoneId = this.zone): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun nextDayStart(ms: Long, zone: ZoneId = this.zone): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun weekStart(ms: Long, zone: ZoneId = this.zone): Long {
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val monday = date.with(DayOfWeek.MONDAY)
        return monday.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun monthStart(ms: Long, zone: ZoneId = this.zone): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun localDate(ms: Long, zone: ZoneId = this.zone): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** 记录与 [from, to) 的交集时长；进行中记录算到 now */
    fun clippedMillis(
        startAt: Long,
        endAt: Long?,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis()
    ): Long {
        val realEnd = endAt ?: now
        val s = maxOf(startAt, from)
        val e = minOf(realEnd, to)
        return (e - s).coerceAtLeast(0L)
    }

    /** 跨天记录按本地日界拆段（用于时间轴 / 占比环） */
    fun splitByDay(startAt: Long, endAt: Long?, zone: ZoneId = this.zone): List<DaySegment> {
        val realEnd = endAt ?: System.currentTimeMillis()
        if (realEnd <= startAt) return emptyList()
        val out = mutableListOf<DaySegment>()
        var cursor = startAt
        var guard = 0
        while (cursor < realEnd && guard++ < 400) {
            val ds = dayStart(cursor, zone)
            val de = nextDayStart(cursor, zone)
            val segEnd = minOf(realEnd, de)
            out += DaySegment(ds, cursor, segEnd)
            cursor = segEnd
        }
        return out
    }

    data class DaySegment(val dayStart: Long, val from: Long, val to: Long)

    /** HH:MM，超过 24h 显示为 25:03 */
    fun formatDuration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    /** HH:MM:SS */
    fun formatDurationSeconds(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun formatClock(ms: Long, zone: ZoneId = this.zone): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

    fun formatDateTime(ms: Long, zone: ZoneId = this.zone): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US))

    fun formatDate(ms: Long, zone: ZoneId = this.zone): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))

    fun formatDateCn(ms: Long, zone: ZoneId = this.zone): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))

    /** 吸附到 stepMs 的整数倍（就近） */
    fun snap(ms: Long, stepMs: Long): Long = Math.round(ms.toDouble() / stepMs) * stepMs

    const val MINUTE = 60_000L
    const val FIVE_MINUTES = 5 * MINUTE
    const val DAY = 24 * 60 * MINUTE
}

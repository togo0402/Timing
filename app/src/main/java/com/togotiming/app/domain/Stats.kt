package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity

data class TypeTotal(val type: ActivityTypeEntity, val millis: Long) {
    fun percent(total: Long): Float = if (total <= 0L) 0f else millis.toFloat() / total.toFloat()
}

/**
 * 统计聚合：
 * - 主视图以根节点为单位（父 = 自身 + 所有子孙）；
 * - 跨天记录按与查询区间的交集计算；
 * - 进行中记录统计到 now。
 */
object StatsAggregator {

    fun overlap(record: TimeRecordEntity, from: Long, to: Long, now: Long): Long =
        TimeMath.clippedMillis(record.startAt, record.endAt, from, to, now)

    fun typeMap(types: List<ActivityTypeEntity>): Map<Long, ActivityTypeEntity> =
        types.associateBy { it.id }

    fun rootOf(type: ActivityTypeEntity, map: Map<Long, ActivityTypeEntity>): ActivityTypeEntity {
        var cur = type
        var guard = 0
        while (cur.parentId != null && guard++ < 8) {
            cur = map[cur.parentId] ?: break
        }
        return cur
    }

    /** 每个节点的「直接」时长（记录挂在哪个节点就记在哪个节点） */
    fun directTotals(
        records: List<TimeRecordEntity>,
        types: List<ActivityTypeEntity>,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis(),
    ): Map<Long, Long> {
        val map = typeMap(types)
        val out = HashMap<Long, Long>()
        for (r in records) {
            if (map[r.typeId] == null) continue
            val ms = overlap(r, from, to, now)
            if (ms > 0) out[r.typeId] = (out[r.typeId] ?: 0L) + ms
        }
        return out
    }

    /** 按根节点聚合（含子孙），按时长降序 */
    fun byRoot(
        records: List<TimeRecordEntity>,
        types: List<ActivityTypeEntity>,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis(),
    ): List<TypeTotal> {
        val map = typeMap(types)
        val acc = HashMap<Long, Long>()
        for (r in records) {
            val t = map[r.typeId] ?: continue
            val root = rootOf(t, map)
            val ms = overlap(r, from, to, now)
            if (ms > 0) acc[root.id] = (acc[root.id] ?: 0L) + ms
        }
        return acc.entries
            .mapNotNull { (id, ms) -> map[id]?.let { TypeTotal(it, ms) } }
            .sortedByDescending { it.millis }
    }

    /** 按叶子（记录实际所属节点）聚合，按时长降序 */
    fun byRecordType(
        records: List<TimeRecordEntity>,
        types: List<ActivityTypeEntity>,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis(),
    ): List<TypeTotal> = directTotals(records, types, from, to, now)
        .entries
        .mapNotNull { (id, ms) -> typeMap(types)[id]?.let { TypeTotal(it, ms) } }
        .sortedByDescending { it.millis }

    /** 某个根节点的直接子节点聚合（用于下钻），剩余归入「其它」由 UI 处理 */
    fun childrenTotals(
        records: List<TimeRecordEntity>,
        types: List<ActivityTypeEntity>,
        rootId: Long,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis(),
    ): List<TypeTotal> {
        val map = typeMap(types)
        val children = types.filter { it.parentId == rootId }
        if (children.isEmpty()) return emptyList()
        val acc = HashMap<Long, Long>()
        for (r in records) {
            val t = map[r.typeId] ?: continue
            var cur: ActivityTypeEntity? = t
            var guard = 0
            while (cur != null && guard++ < 8 && cur.parentId != null && cur.parentId != rootId) {
                cur = map[cur.parentId]
            }
            val child = cur
            if (child == null || child.parentId != rootId) continue
            val ms = overlap(r, from, to, now)
            if (ms > 0) acc[child.id] = (acc[child.id] ?: 0L) + ms
        }
        return acc.entries
            .mapNotNull { (id, ms) -> map[id]?.let { TypeTotal(it, ms) } }
            .sortedByDescending { it.millis }
    }

    fun totalMillis(totals: List<TypeTotal>): Long = totals.sumOf { it.millis }
}

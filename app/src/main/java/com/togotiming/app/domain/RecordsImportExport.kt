package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

/** 记录导入导出格式版本 / 文件类型标记（与分类导入导出的 version = 1 相互独立）。 */
const val RECORD_IO_VERSION = 1
const val RECORD_IO_KIND = "timing_records"

/**
 * 导出文件里的分类条目。只包含「导出记录实际涉及」的分类，
 * 导入时若父级缺失，会按 path 的层级补齐（父级先创建）。
 */
@Serializable
data class RecordExportType(
    val path: String = "",
    val name: String = "",
    val colorArgb: Int? = null,
    val targetMinutes: Int = 60,
)

/** 导出文件里的一条时间记录：已裁剪到导出区间内、且 endAt 必定有值。 */
@Serializable
data class RecordExportRecord(
    val typePath: String = "",
    val startAt: Long = 0L,
    val endAt: Long? = null,
    val comment: String? = null,
    val targetMinutes: Int? = null,
)

/** 时间记录导入导出文档（kind = timing_records，与分类导入导出 JSON 区分）。 */
@Serializable
data class RecordExportDoc(
    val version: Int = RECORD_IO_VERSION,
    val kind: String = RECORD_IO_KIND,
    val exportedAt: String? = null,
    val from: Long = 0L,
    val to: Long = 0L,
    val types: List<RecordExportType> = emptyList(),
    val records: List<RecordExportRecord> = emptyList(),
)

sealed interface RecordParseResult {
    data class Ok(val doc: RecordExportDoc) : RecordParseResult
    data class Error(val message: String) : RecordParseResult
}

/** 导出结果统计：记录条数 / 分类数 / 时间范围（[from, to) 毫秒）。 */
data class RecordExportSummary(
    val recordCount: Int,
    val typeCount: Int,
    val from: Long,
    val to: Long,
)

/** 导入报告：导入 N 条、跳过重复 N 条、创建分类 N 个、缺失或非法 N 条。 */
data class RecordImportReport(
    val imported: Int,
    val skipped: Int,
    val createdTypes: Int,
    val invalid: Int,
)

/**
 * 时间记录 JSON 的 DTO、序列化与校验。
 *
 * 与 [ImportExport]（分类结构）分开维护：
 * - 分类导入导出只读写 activities 树；
 * - 这里读写 time_records 的扁平记录 + 记录涉及的分类 path。
 * 解析阶段只做「文档级」致命校验（version / kind / 范围 / types 结构），
 * 单条记录的时间缺失、非法、分类找不到等留给导入事务按条计数（缺失/非法 N 条）。
 */
object RecordsImportExport {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun serialize(doc: RecordExportDoc): String =
        json.encodeToString(RecordExportDoc.serializer(), doc)

    /**
     * 构建导出文档：
     * - 只导出已结束（endAt != null）的记录；
     * - 与 [from, to) 有交集的记录按区间裁剪，裁剪后 <= 0 的跳过；
     * - [typeIds] 为 null 或空集合表示全部分类，否则按分类 id 过滤（父级是否包含子级由调用方决定）；
     * - types 只包含导出记录实际涉及的分类（按 path 排序）。
     */
    fun buildExportDoc(
        from: Long,
        to: Long,
        typeIds: Set<Long>?,
        allTypes: List<ActivityTypeEntity>,
        records: List<TimeRecordEntity>,
    ): RecordExportDoc {
        val typesById = allTypes.associateBy { it.id }
        val allowedIds = typeIds?.takeIf { it.isNotEmpty() }
        val usedTypes = LinkedHashMap<Long, ActivityTypeEntity>()
        val out = mutableListOf<RecordExportRecord>()

        for (record in records.sortedBy { it.startAt }) {
            val endAt = record.endAt ?: continue
            if (allowedIds != null && record.typeId !in allowedIds) continue
            val type = typesById[record.typeId] ?: continue
            val start = maxOf(record.startAt, from)
            val end = minOf(endAt, to)
            if (end <= start) continue
            usedTypes[type.id] = type
            out += RecordExportRecord(
                typePath = type.path,
                startAt = start,
                endAt = end,
                comment = record.comment?.takeIf { it.isNotBlank() },
                targetMinutes = record.targetMinutes,
            )
        }

        val types = usedTypes.values
            .sortedWith(compareBy({ it.path }, { it.id }))
            .map {
                RecordExportType(
                    path = it.path,
                    name = it.name,
                    colorArgb = it.colorArgb,
                    targetMinutes = it.targetMinutes,
                )
            }

        return RecordExportDoc(
            version = RECORD_IO_VERSION,
            kind = RECORD_IO_KIND,
            exportedAt = OffsetDateTime.now().toString(),
            from = from,
            to = to,
            types = types,
            records = out,
        )
    }

    /**
     * 解析并校验记录 JSON。致命错误返回 [RecordParseResult.Error] 并带具体原因；
     * 单条记录的问题不在这里拦截（导入时计入「缺失/非法」）。
     */
    fun parse(text: String): RecordParseResult {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return RecordParseResult.Error("JSON 解析失败：${e.message ?: "格式错误"}")
        }
        val obj = root as? JsonObject
            ?: return RecordParseResult.Error("JSON 顶层必须是对象（{ ... }）")

        val versionElement = obj["version"]
            ?: return RecordParseResult.Error("缺少 version 字段，当前仅支持 version = $RECORD_IO_VERSION")
        val version = versionElement.jsonPrimitive.intOrNull
            ?: return RecordParseResult.Error("version 必须为整数，当前仅支持 version = $RECORD_IO_VERSION")
        if (version != RECORD_IO_VERSION) {
            return RecordParseResult.Error(
                "不支持的版本 $version，当前仅支持 version = $RECORD_IO_VERSION"
            )
        }

        val kindElement = obj["kind"]
            ?: return RecordParseResult.Error(
                "缺少 kind 字段，应为 \"$RECORD_IO_KIND\"（分类导入导出文件没有该字段，请到「分类导入 / 导出」导入）"
            )
        val kind = kindElement.jsonPrimitive.contentOrNull
            ?: return RecordParseResult.Error("kind 必须为字符串 \"$RECORD_IO_KIND\"")
        if (kind != RECORD_IO_KIND) {
            return RecordParseResult.Error(
                "kind 必须为 \"$RECORD_IO_KIND\"，实际为 \"$kind\"（这是分类导入导出文件？请到「分类导入 / 导出」导入）"
            )
        }

        val doc = try {
            json.decodeFromString(RecordExportDoc.serializer(), text)
        } catch (e: Exception) {
            return RecordParseResult.Error("JSON 解析失败：${e.message ?: "格式错误"}")
        }
        if (doc.from <= 0L || doc.to <= 0L) {
            return RecordParseResult.Error("时间范围 from / to 缺失或非法")
        }
        if (doc.from >= doc.to) {
            return RecordParseResult.Error("时间范围非法：from 必须早于 to")
        }
        if (doc.records.isNotEmpty() && doc.types.isEmpty()) {
            return RecordParseResult.Error("types 为空，无法确定记录的分类")
        }

        val seenPaths = HashSet<String>()
        doc.types.forEachIndexed { index, type ->
            val path = type.path.trim()
            if (path.isEmpty()) return RecordParseResult.Error("types[$index].path 为空")
            if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
                return RecordParseResult.Error("types[$index].path 非法：${type.path}")
            }
            val segments = path.split('/')
            if (segments.size > 3) {
                return RecordParseResult.Error("分类最多 3 层：$path")
            }
            if (segments.any { it.isBlank() }) {
                return RecordParseResult.Error("types[$index].path 存在空层级：${type.path}")
            }
            if (!seenPaths.add(path)) {
                return RecordParseResult.Error("重复的 type path：$path")
            }
        }
        return RecordParseResult.Ok(doc)
    }

    fun summaryOf(doc: RecordExportDoc): RecordExportSummary =
        RecordExportSummary(
            recordCount = doc.records.size,
            typeCount = doc.types.size,
            from = doc.from,
            to = doc.to,
        )
}

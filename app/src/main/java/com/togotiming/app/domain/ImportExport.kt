package com.togotiming.app.domain

import com.togotiming.app.data.ActivityTypeEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ImportNode(
    val key: String = "",
    val name: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val children: List<ImportNode> = emptyList(),
)

@Serializable
data class ImportDoc(
    val version: Int = 1,
    val exportedAt: String? = null,
    val activities: List<ImportNode> = emptyList(),
)

/** 已展开的扁平节点，按父在前、子在后的顺序 */
data class FlatNode(
    val path: String,
    val name: String,
    val colorArgb: Int?,
    val icon: String?,
    val depth: Int,
    val parentPath: String?,
)

sealed interface ParseResult {
    data class Ok(val nodes: List<FlatNode>, val doc: ImportDoc) : ParseResult
    data class Error(val message: String) : ParseResult
}

object ImportExport {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun parse(text: String): ParseResult {
        val doc = try {
            json.decodeFromString(ImportDoc.serializer(), text)
        } catch (e: Exception) {
            return ParseResult.Error("JSON 解析失败：${e.message ?: "格式错误"}")
        }
        if (doc.version != 1) return ParseResult.Error("不支持的版本 ${doc.version}，当前仅支持 version = 1")
        if (doc.activities.isEmpty()) return ParseResult.Error("activities 为空，没有可导入的活动")

        val out = mutableListOf<FlatNode>()
        val seenPaths = HashSet<String>()

        fun walk(node: ImportNode, parentPath: String?, depth: Int) {
            if (depth > 3) throw IllegalArgumentException("嵌套超过 3 层：${parentPath.orEmpty()}/${node.key}")
            val key = node.key.trim()
            if (key.isEmpty()) throw IllegalArgumentException("存在空 key")
            if (key.contains('/')) throw IllegalArgumentException("key 不能包含斜杠：$key")
            val name = node.name?.trim().orEmpty()
            if (name.isEmpty()) throw IllegalArgumentException("活动缺少 name：$key")
            val color = node.color?.let {
                ColorPalette.parseHex(it)
                    ?: throw IllegalArgumentException("颜色格式错误：$it（应为 #RRGGBB）")
            }
            val path = if (parentPath.isNullOrEmpty()) key else "$parentPath/$key"
            if (!seenPaths.add(path)) throw IllegalArgumentException("重复的 path：$path")
            out += FlatNode(path, name, color, node.icon, depth, parentPath)
            node.children.forEach { walk(it, path, depth + 1) }
        }

        return try {
            doc.activities.forEach { walk(it, null, 1) }
            ParseResult.Ok(out, doc)
        } catch (e: IllegalArgumentException) {
            ParseResult.Error(e.message ?: "数据校验失败")
        }
    }

    fun buildExportDoc(types: List<ActivityTypeEntity>, now: Long = System.currentTimeMillis()): ImportDoc {
        val active = types.filter { !it.archived }
        val childrenByParent = active.groupBy { it.parentId }
        fun build(type: ActivityTypeEntity): ImportNode {
            val kids = childrenByParent[type.id].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.id }))
            return ImportNode(
                key = type.path.substringAfterLast('/'),
                name = type.name,
                color = ColorPalette.toHex(type.colorArgb),
                icon = type.iconRes,
                children = kids.map { build(it) },
            )
        }
        val roots = active.filter { it.parentId == null }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        return ImportDoc(
            version = 1,
            exportedAt = java.time.OffsetDateTime.now().toString(),
            activities = roots.map { build(it) },
        )
    }

    fun serialize(doc: ImportDoc): String = json.encodeToString(ImportDoc.serializer(), doc)

    fun serializeCurrent(types: List<ActivityTypeEntity>): String = serialize(buildExportDoc(types))
}

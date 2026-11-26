package com.togotiming.app.data

import androidx.room.withTransaction
import com.togotiming.app.domain.ColorPalette
import com.togotiming.app.domain.FlatNode
import com.togotiming.app.domain.ImportExport
import com.togotiming.app.domain.RecordExportType
import com.togotiming.app.domain.RecordImportReport
import com.togotiming.app.domain.RecordParseResult
import com.togotiming.app.domain.RecordsImportExport
import com.togotiming.app.domain.TimeMath
import kotlinx.coroutines.flow.Flow

enum class DeleteMode { PROMOTE_CHILDREN, DELETE_SUBTREE }

enum class ImportConflictStrategy { SKIP, RENAME }

data class ImportReport(
    val imported: Int,
    val skipped: Int,
    val renamed: Int,
)

sealed interface ImportOutcome {
    data class Success(val report: ImportReport) : ImportOutcome
    data class Failure(val message: String) : ImportOutcome
}

/**
 * 单一数据入口：所有写操作走事务，保证「同时最多一条进行中记录」等硬约束。
 */
class TimingRepository(
    private val db: AppDatabase,
    private val typeDao: ActivityTypeDao,
    private val recordDao: TimeRecordDao,
    private val bindingDao: NfcBindingDao,
) {

    val types: Flow<List<ActivityTypeEntity>> = typeDao.observeAll()
    val activeTypes: Flow<List<ActivityTypeEntity>> = typeDao.observeAllActive()
    val leaves: Flow<List<ActivityTypeEntity>> = typeDao.observeLeaves()
    val activeRecord: Flow<TimeRecordEntity?> = recordDao.observeActive()
    val bindings: Flow<List<NfcBindingEntity>> = bindingDao.observeAll()

    fun observeRecords(from: Long, to: Long): Flow<List<TimeRecordEntity>> =
        recordDao.observeOverlapping(from, to)

    // ---------------- 查询 ----------------

    suspend fun recordsIn(from: Long, to: Long): List<TimeRecordEntity> = recordDao.getOverlapping(from, to)

    suspend fun allTypes(includeArchived: Boolean = false): List<ActivityTypeEntity> =
        if (includeArchived) typeDao.getAll() else typeDao.getAllActive()

    suspend fun leavesNow(): List<ActivityTypeEntity> = typeDao.getLeaves()

    suspend fun typeById(id: Long): ActivityTypeEntity? = typeDao.getById(id)

    suspend fun activeNow(): TimeRecordEntity? = recordDao.getActive()

    suspend fun recordById(id: Long): TimeRecordEntity? = recordDao.getById(id)

    suspend fun bindingByUid(uid: String): NfcBindingEntity? = bindingDao.getByUid(uid)

    suspend fun bindingsNow(): List<NfcBindingEntity> = bindingDao.getAll()

    suspend fun lastFinished(before: Long = System.currentTimeMillis()): TimeRecordEntity? =
        recordDao.getLastFinished(before)

    suspend fun allRecords(): List<TimeRecordEntity> = recordDao.getAll()

    suspend fun recordsOfType(typeId: Long): List<TimeRecordEntity> = recordDao.getByType(typeId)

    // ---------------- 计时 ----------------

    /** 结束所有进行中的记录（进行中记录唯一约束的兜底） */
    suspend fun stopAllActive(at: Long = System.currentTimeMillis()): Boolean = db.withTransaction {
        recordDao.stopAllActive(at) > 0
    }

    /**
     * 开始计时：事务内先结束所有进行中记录，再插入新记录。
     * 只允许叶子节点（无未归档子节点）计时。
     */
    suspend fun start(
        typeId: Long,
        source: String,
        at: Long = System.currentTimeMillis(),
        targetMinutes: Int? = null,
    ): Long =
        db.withTransaction {
            val type = typeDao.getById(typeId) ?: throw IllegalArgumentException("活动类型不存在")
            if (type.archived) throw IllegalArgumentException("活动类型已归档")
            if (typeDao.countChildren(typeId) > 0) throw IllegalArgumentException("只能对叶子节点计时")
            recordDao.stopAllActive(at)
            recordDao.insert(
                TimeRecordEntity(
                    typeId = typeId,
                    startAt = at,
                    endAt = null,
                    source = source,
                    targetMinutes = targetMinutes ?: type.targetMinutes,
                )
            )
        }

    suspend fun stop(at: Long = System.currentTimeMillis()): Boolean = db.withTransaction {
        recordDao.stopAllActive(at) > 0
    }

    /**
     * 暂停：把进行中记录标记为已结束；恢复时把 startAt 向后平移暂停时长，
     * 从而单条记录的「有效时长」不包含暂停时间，统计/时间轴都无需特殊处理。
     */
    suspend fun pauseRecord(id: Long, at: Long = System.currentTimeMillis()): Boolean = db.withTransaction {
        val record = recordDao.getById(id) ?: return@withTransaction false
        if (record.endAt != null) return@withTransaction false
        recordDao.update(record.copy(endAt = at))
        true
    }

    suspend fun resumeRecord(id: Long, at: Long = System.currentTimeMillis()): Boolean = db.withTransaction {
        val record = recordDao.getById(id) ?: return@withTransaction false
        val pausedEnd = record.endAt ?: return@withTransaction false
        val pausedMs = (at - pausedEnd).coerceAtLeast(0L)
        recordDao.update(record.copy(startAt = record.startAt + pausedMs, endAt = null))
        true
    }

    /** 补记一段（允许与其它记录重叠，但起止必须合法） */
    suspend fun addManual(
        typeId: Long,
        startAt: Long,
        endAt: Long,
        comment: String? = null,
        targetMinutes: Int? = null,
    ): Long = db.withTransaction {
        require(endAt > startAt) { "结束时间必须晚于开始时间" }
        val type = typeDao.getById(typeId) ?: throw IllegalArgumentException("活动类型不存在")
        if (type.archived) throw IllegalArgumentException("活动类型已归档")
        recordDao.insert(
            TimeRecordEntity(
                typeId = typeId,
                startAt = startAt,
                endAt = endAt,
                comment = comment,
                source = SOURCE_MANUAL,
                targetMinutes = targetMinutes ?: type.targetMinutes,
            )
        )
    }

    /** 编辑已有记录（改类型 / 起止 / 备注） */
    suspend fun updateRecord(
        id: Long,
        typeId: Long,
        startAt: Long,
        endAt: Long?,
        comment: String?,
    ) = db.withTransaction {
        val record = recordDao.getById(id) ?: throw IllegalArgumentException("记录不存在")
        if (endAt != null) require(endAt > startAt) { "结束时间必须晚于开始时间" }
        if (record.endAt != null && endAt == null) {
            throw IllegalArgumentException("已结束的记录不能改回进行中")
        }
        val active = recordDao.getActive()
        if (endAt == null && active != null && active.id != id) {
            throw IllegalArgumentException("已有进行中的计时")
        }
        recordDao.update(
            record.copy(typeId = typeId, startAt = startAt, endAt = endAt, comment = comment)
        )
    }

    suspend fun deleteRecord(id: Long) = db.withTransaction { recordDao.deleteById(id) }

    // ---------------- 活动类型 ----------------

    suspend fun createType(
        parentId: Long?,
        name: String,
        colorArgb: Int? = null,
        icon: String? = null,
        targetMinutes: Int = 60,
    ): Long = db.withTransaction {
        val parent = parentId?.let { typeDao.getById(it) ?: throw IllegalArgumentException("父节点不存在") }
        if (parent != null && parent.depth >= 2) throw IllegalArgumentException("最多支持 3 层分类")
        if (parent?.archived == true) throw IllegalArgumentException("父节点已归档")
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "名称不能为空" }

        val depth = if (parent == null) 0 else parent.depth + 1
        val index = if (parent == null) typeDao.countRoots() else typeDao.countChildren(parent.id)
        val color = colorArgb
            ?: if (parent == null) ColorPalette.rootColor(index)
            else ColorPalette.childColor(parent.colorArgb, index)
        val seed = if (colorArgb == null && parent != null) parent.colorArgb else null

        val segment = sanitizeSegment(cleanName)
        val basePath = if (parent == null) segment else "${parent.path}/$segment"
        val path = uniquePath(basePath)
        val sort = typeDao.maxSortOrder(parentId) + 1
        typeDao.insert(
            ActivityTypeEntity(
                parentId = parentId,
                name = cleanName,
                iconRes = icon,
                colorArgb = color,
                colorSeed = seed,
                sortOrder = sort,
                depth = depth,
                path = path,
                targetMinutes = targetMinutes.coerceIn(1, 24 * 60),
            )
        )
    }

    suspend fun updateTypeTarget(id: Long, minutes: Int) = db.withTransaction {
        val node = typeDao.getById(id) ?: return@withTransaction
        typeDao.update(node.copy(targetMinutes = minutes.coerceIn(1, 24 * 60)))
    }

    suspend fun updateRecordTarget(id: Long, minutes: Int) = db.withTransaction {
        val record = recordDao.getById(id) ?: return@withTransaction
        recordDao.update(record.copy(targetMinutes = minutes.coerceIn(1, 24 * 60)))
    }

    /** 重命名：同步刷新自身及所有子孙的 path */
    suspend fun renameType(id: Long, newName: String) = db.withTransaction {
        val node = typeDao.getById(id) ?: throw IllegalArgumentException("节点不存在")
        val cleanName = newName.trim()
        require(cleanName.isNotEmpty()) { "名称不能为空" }
        val all = typeDao.getAll()
        val subtree = subtreeIds(all, id)
        val parentPath = node.parentId?.let { pid -> all.firstOrNull { it.id == pid }?.path }
        val segment = sanitizeSegment(cleanName)
        val base = if (parentPath.isNullOrEmpty()) segment else "$parentPath/$segment"
        var candidate = base
        var i = 2
        while (all.any { it.id !in subtree && it.path == candidate }) {
            candidate = "$base#$i"
            i++
        }
        val oldPrefix = node.path
        all.filter { it.id in subtree }.forEach { t ->
            val newPath = if (t.id == id) candidate else candidate + t.path.removePrefix(oldPrefix)
            typeDao.update(if (t.id == id) t.copy(name = cleanName, path = newPath) else t.copy(path = newPath))
        }
    }

    /**
     * 修改颜色。
     * @param cascadeDerived true = 同时更新「派生状态」的子孙（colorSeed 仍等于父色），
     *                       false = 只改自身，并把直接子节点的 colorSeed 置 null（不再跟随）
     */
    suspend fun recolorType(id: Long, newColor: Int, cascadeDerived: Boolean) = db.withTransaction {
        val node = typeDao.getById(id) ?: throw IllegalArgumentException("节点不存在")
        val all = typeDao.getAll()
        val oldColor = node.colorArgb
        val updates = LinkedHashMap<Long, ActivityTypeEntity>()
        updates[node.id] = node.copy(colorArgb = newColor, colorSeed = null)

        data class Cascade(val parentId: Long, val oldColor: Int, val newColor: Int)
        val queue = ArrayDeque<Cascade>()
        queue += Cascade(id, oldColor, newColor)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            val kids = all.filter { it.parentId == c.parentId && !it.archived }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            kids.forEachIndexed { index, kid ->
                if (cascadeDerived) {
                    if (kid.colorSeed == c.oldColor) {
                        val newKid = ColorPalette.childColor(c.newColor, index)
                        updates[kid.id] = kid.copy(colorArgb = newKid, colorSeed = c.newColor)
                        queue += Cascade(kid.id, kid.colorArgb, newKid)
                    }
                } else {
                    updates[kid.id] = kid.copy(colorSeed = null)
                }
            }
        }
        updates.values.forEach { typeDao.update(it) }
    }

    /** 删除（归档）节点：子节点提升为一级 / 连同子树一起归档 */
    suspend fun deleteType(id: Long, mode: DeleteMode) = db.withTransaction {
        val node = typeDao.getById(id) ?: return@withTransaction
        val all = typeDao.getAll()
        val children = all.filter { it.parentId == id && !it.archived }
        when (mode) {
            DeleteMode.PROMOTE_CHILDREN -> {
                var sort = typeDao.maxSortOrder(null) + 1
                children.forEach { child ->
                    val segment = sanitizeSegment(child.name)
                    val path = uniquePath(segment)
                    typeDao.reparent(child.id, null, 0, path, sort)
                    sort++
                }
                typeDao.archiveIds(listOf(id))
            }
            DeleteMode.DELETE_SUBTREE -> {
                val ids = subtreeIds(all, id).toList()
                typeDao.archiveIds(ids)
                // 绑定到被归档叶子的 NFC 一并清理，避免碰了标签没反应
                bindingsNow().filter { it.typeId in ids }.forEach { bindingDao.deleteById(it.id) }
            }
        }
    }

    /**
     * 批量移动分类：只移动「选中节点里的最顶层节点」，其子孙保持原有父子关系一起移动。
     * [targetParentId] 为 null 表示移动到一级（空白区域）。
     */
    suspend fun moveTypes(ids: Collection<Long>, targetParentId: Long?) = db.withTransaction {
        if (ids.isEmpty()) return@withTransaction
        val all = typeDao.getAll()
        val selected = ids.toSet()
        val target = targetParentId?.let {
            typeDao.getById(it) ?: throw IllegalArgumentException("目标节点不存在")
        }
        if (target?.archived == true) throw IllegalArgumentException("目标节点已归档")

        // 防止把节点移到自己或自己的子孙下面
        var cursor = target
        while (cursor != null) {
            if (cursor.id in selected) {
                throw IllegalArgumentException("不能移动到自身或子分类下")
            }
            val parentId = cursor.parentId
            cursor = if (parentId == null) null else all.firstOrNull { it.id == parentId }
        }

        val tops = all.filter {
            it.id in selected && !it.archived && (it.parentId == null || it.parentId !in selected)
        }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        if (tops.isEmpty()) return@withTransaction

        val targetPath = target?.path ?: ""
        val targetDepth = target?.depth ?: -1
        var nextSort = typeDao.maxSortOrder(targetParentId) + 1
        // 旧路径（不在移动集合里的节点）用于避免重名
        val usedPaths = all.filter { it.id !in selected }.map { it.path }.toMutableSet()

        suspend fun assign(
            node: ActivityTypeEntity,
            parentId: Long?,
            parentPath: String,
            depth: Int,
            order: Int,
        ) {
            val segment = sanitizeSegment(node.name)
            val base = if (parentPath.isEmpty()) segment else "$parentPath/$segment"
            var candidate = base
            var suffix = 2
            while (candidate in usedPaths) {
                candidate = "$base-$suffix"
                suffix++
            }
            usedPaths += candidate
            typeDao.reparent(node.id, parentId, depth, candidate, order)
            all.filter { it.parentId == node.id && !it.archived }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .forEach { child ->
                    assign(child, node.id, candidate, depth + 1, child.sortOrder)
                }
        }

        tops.forEachIndexed { index, root ->
            assign(root, targetParentId, targetPath, targetDepth + 1, nextSort + index)
        }
    }

    // ---------------- NFC 绑定 ----------------
    suspend fun bindNfc(tagUid: String, typeId: Long): NfcBindingEntity = db.withTransaction {
        val uid = com.togotiming.app.domain.NfcToggle.normalizeUid(tagUid)
        require(uid.isNotEmpty()) { "UID 不能为空" }
        val type = typeDao.getById(typeId) ?: throw IllegalArgumentException("节点不存在")
        if (type.archived) throw IllegalArgumentException("节点已归档")
        if (typeDao.countChildren(typeId) > 0) throw IllegalArgumentException("只能绑定叶子节点（该节点还有子分类）")
        val existing = bindingDao.getByUid(uid)
        if (existing != null) {
            val updated = existing.copy(typeId = typeId, enabled = true)
            bindingDao.update(updated)
            updated
        } else {
            val entity = NfcBindingEntity(tagUid = uid, typeId = typeId, enabled = true)
            val newId = bindingDao.insert(entity)
            entity.copy(id = newId)
        }
    }

    suspend fun setBindingEnabled(id: Long, enabled: Boolean) = db.withTransaction {
        val all = bindingDao.getAll()
        all.firstOrNull { it.id == id }?.let { bindingDao.update(it.copy(enabled = enabled)) }
    }

    suspend fun deleteBinding(id: Long) = db.withTransaction { bindingDao.deleteById(id) }

    // ---------------- 导入 / 导出 ----------------

    suspend fun findImportConflicts(nodes: List<FlatNode>): List<String> {
        val existing = typeDao.getAll().map { it.path }.toHashSet()
        return nodes.map { it.path }.filter { it in existing }
    }

    suspend fun importActivities(nodes: List<FlatNode>, strategy: ImportConflictStrategy): ImportOutcome {
        return try {
            val report = db.withTransaction {
                val existing = typeDao.getAll().associateBy { it.path }.toMutableMap()
                val idByPath = HashMap<String, Long>()
                var imported = 0
                var skipped = 0
                var renamed = 0

                for (node in nodes) {
                    val parentId = node.parentPath?.let { pp ->
                        idByPath[pp] ?: existing[pp]?.id
                        ?: throw IllegalArgumentException("父节点不存在：$pp")
                    }
                    var path = node.path
                    var name = node.name
                    if (existing.containsKey(path) || idByPath.containsKey(path)) {
                        when (strategy) {
                            ImportConflictStrategy.SKIP -> {
                                skipped++
                                continue
                            }
                            ImportConflictStrategy.RENAME -> {
                                var suffix = 2
                                var candidate: String
                                do {
                                    candidate = "${node.path}#$suffix"
                                    suffix++
                                } while (existing.containsKey(candidate) || idByPath.containsKey(candidate))
                                path = candidate
                                name = "${node.name}($suffix)"
                                renamed++
                            }
                        }
                    }
                    val parent = parentId?.let { pid -> existing.values.firstOrNull { t -> t.id == pid } }
                    val color = node.colorArgb
                        ?: if (parentId == null) ColorPalette.rootColor(typeDao.countRoots())
                        else ColorPalette.childColor(parent?.colorArgb ?: ColorPalette.rootColor(0), typeDao.countChildren(parentId))
                    val sort = typeDao.maxSortOrder(parentId) + 1
                    val entity = ActivityTypeEntity(
                        parentId = parentId,
                        name = name,
                        iconRes = node.icon,
                        colorArgb = color,
                        colorSeed = if (node.colorArgb == null && parent != null) parent.colorArgb else null,
                        sortOrder = sort,
                        depth = node.depth - 1,
                        path = path,
                    )
                    val newId = typeDao.insert(entity)
                    idByPath[path] = newId
                    existing[path] = entity.copy(id = newId)
                    imported++
                }
                ImportReport(imported = imported, skipped = skipped, renamed = renamed)
            }
            ImportOutcome.Success(report)
        } catch (e: Exception) {
            ImportOutcome.Failure(e.message ?: "导入失败")
        }
    }

    suspend fun exportJson(): String = ImportExport.serializeCurrent(typeDao.getAll())

    // ---------------- 记录导入 / 导出 ----------------

    /**
     * 导出 [from, to) 内已结束的时间记录为 JSON。
     *
     * - 只导出已结束记录（endAt != null）；
     * - 与区间相交的记录按 [from, to) 裁剪，裁剪后 <= 0 的跳过；
     * - [typeIds] 为 null / 空集合表示全部分类；调用方（UI）负责把勾选的父级展开成含子级的集合；
     * - types 只包含导出记录实际涉及的分类，按 path 排序。
     */
    suspend fun exportRecordsJson(from: Long, to: Long, typeIds: Set<Long>?): String =
        db.withTransaction {
            require(to > from) { "时间范围非法：结束必须晚于开始" }
            val allTypes = typeDao.getAll()
            val records = recordDao.getOverlapping(from, to)
            RecordsImportExport.serialize(
                RecordsImportExport.buildExportDoc(from, to, typeIds, allTypes, records)
            )
        }

    /**
     * 导入记录 JSON（事务内完成）：
     * - 分类按 path 匹配现有分类；缺失的按层级自动创建（父级先创建，颜色 / 目标沿用导出值）；
     * - 记录插入为新记录（id 自增），endAt 必须有值；
     * - 同 typeId + startAt + endAt 已存在则跳过；
     * - 分类缺失 / 时间非法的记录计入 invalid，不阻断其它记录；
     * - 致命格式错误（version / kind / 时间范围 / types 结构）抛 [IllegalArgumentException]。
     */
    suspend fun importRecordsJson(text: String): RecordImportReport {
        val parsed = RecordsImportExport.parse(text)
        if (parsed is RecordParseResult.Error) throw IllegalArgumentException(parsed.message)
        val doc = (parsed as RecordParseResult.Ok).doc

        return db.withTransaction {
            val allTypes = typeDao.getAll()
            val entitiesByPath = LinkedHashMap<String, ActivityTypeEntity>()
            val idByPath = HashMap<String, Long>()
            allTypes.forEach { type ->
                if (type.path.isNotBlank()) {
                    entitiesByPath[type.path] = type
                    idByPath[type.path] = type.id
                }
            }

            var createdTypes = 0

            // 1) 分类映射 / 自动创建：按 path 层级从浅到深处理，保证父级先创建。
            val declaredByPath = LinkedHashMap<String, RecordExportType>()
            doc.types.forEach { declared ->
                val path = declared.path.trim()
                if (path.isNotEmpty() && path !in declaredByPath) declaredByPath[path] = declared
            }
            val missingByPath = LinkedHashMap<String, Boolean>()
            for (declared in doc.types) {
                val path = declared.path.trim()
                if (path.isEmpty() || path in idByPath) continue
                val segments = path.split('/')
                for (i in 1..segments.size) {
                    val prefix = segments.subList(0, i).joinToString("/")
                    if (prefix in idByPath || prefix in missingByPath) continue
                    missingByPath[prefix] = declaredByPath.containsKey(prefix)
                }
            }
            for (path in missingByPath.keys.sortedBy { p -> p.count { it == '/' } }) {
                if (path in idByPath) continue
                val parentPath = if ('/' in path) path.substringBeforeLast('/') else ""
                val parent = if (parentPath.isEmpty()) null else entitiesByPath[parentPath]
                // 父级既不在库里也没创建成功：跳过该分支，相关记录会在下面计入「缺失/非法」
                if (parentPath.isNotEmpty() && parent == null) continue
                val declared = declaredByPath[path]
                val name = declared?.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: path.substringAfterLast('/')
                val parentId = parent?.id
                val color = declared?.colorArgb
                    ?: if (parent == null) {
                        ColorPalette.rootColor(typeDao.countRoots())
                    } else {
                        ColorPalette.childColor(parent.colorArgb, typeDao.countChildren(parent.id))
                    }
                val targetMinutes = declared?.targetMinutes?.coerceIn(1, 24 * 60)
                    ?: parent?.targetMinutes
                    ?: 60
                val entity = ActivityTypeEntity(
                    parentId = parentId,
                    name = name,
                    iconRes = null,
                    colorArgb = color,
                    colorSeed = if (declared?.colorArgb == null && parent != null) parent.colorArgb else null,
                    sortOrder = typeDao.maxSortOrder(parentId) + 1,
                    depth = path.count { it == '/' },
                    path = path,
                    targetMinutes = targetMinutes.coerceIn(1, 24 * 60),
                )
                val newId = typeDao.insert(entity)
                entitiesByPath[path] = entity.copy(id = newId)
                idByPath[path] = newId
                createdTypes++
            }

            // 2) 记录插入：重复（同 typeId + startAt + endAt）跳过；分类缺失 / 时间非法计入 invalid。
            val seenKeys = HashSet<Triple<Long, Long, Long>>()
            recordDao.getAll().forEach { existing ->
                val end = existing.endAt
                if (end != null) seenKeys += Triple(existing.typeId, existing.startAt, end)
            }
            var imported = 0
            var skipped = 0
            var invalid = 0
            val now = System.currentTimeMillis()
            for (item in doc.records) {
                val typePath = item.typePath.trim()
                val typeId = idByPath[typePath]
                val startAt = item.startAt
                val endAt = item.endAt
                if (typePath.isEmpty() || typeId == null || endAt == null ||
                    endAt <= startAt || startAt < 0L
                ) {
                    invalid++
                    continue
                }
                if (!seenKeys.add(Triple(typeId, startAt, endAt))) {
                    skipped++
                    continue
                }
                val type = entitiesByPath[typePath]
                recordDao.insert(
                    TimeRecordEntity(
                        typeId = typeId,
                        startAt = startAt,
                        endAt = endAt,
                        comment = item.comment?.trim()?.takeIf { it.isNotEmpty() },
                        source = SOURCE_MANUAL,
                        targetMinutes = item.targetMinutes?.coerceIn(1, 24 * 60) ?: type?.targetMinutes,
                        createdAt = now,
                    )
                )
                imported++
            }

            RecordImportReport(
                imported = imported,
                skipped = skipped,
                createdTypes = createdTypes,
                invalid = invalid,
            )
        }
    }

    // ---------------- 工具 ----------------

    private suspend fun uniquePath(base: String): String {
        if (typeDao.getByPath(base) == null) return base
        var i = 2
        while (typeDao.getByPath("$base#$i") != null) i++
        return "$base#$i"
    }

    companion object {
        fun sanitizeSegment(name: String): String =
            name.trim().replace("/", "／").replace("#", "＃").ifBlank { "未命名" }

        fun subtreeIds(all: List<ActivityTypeEntity>, rootId: Long): Set<Long> {
            val result = LinkedHashSet<Long>()
            val queue = ArrayDeque<Long>()
            queue += rootId
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (!result.add(cur)) continue
                all.filter { it.parentId == cur }.forEach { queue += it.id }
            }
            return result
        }

        fun now(): Long = System.currentTimeMillis()

        fun dayRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> =
            TimeMath.dayStart(now) to TimeMath.nextDayStart(now)
    }
}

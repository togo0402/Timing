package com.togotiming.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.togotiming.app.AppContainer
import com.togotiming.app.TimingApp
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.DeleteMode
import com.togotiming.app.data.ImportConflictStrategy
import com.togotiming.app.data.ImportOutcome
import com.togotiming.app.data.SOURCE_MANUAL
import com.togotiming.app.data.ThemeMode
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.data.TimingRepository
import com.togotiming.app.domain.ColorPalette
import com.togotiming.app.domain.FlatNode
import com.togotiming.app.domain.ImportExport
import com.togotiming.app.domain.ParseResult
import com.togotiming.app.domain.RecordExportSummary
import com.togotiming.app.domain.RecordImportReport
import com.togotiming.app.domain.RecordParseResult
import com.togotiming.app.domain.RecordsImportExport
import com.togotiming.app.domain.TimeMath
import com.togotiming.app.util.FontManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val repository: TimingRepository = container.repository

    val types: StateFlow<List<ActivityTypeEntity>> = repository.types
        .stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())

    val activeTypes: StateFlow<List<ActivityTypeEntity>> = repository.activeTypes
        .stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())

    val leaves: StateFlow<List<ActivityTypeEntity>> = repository.leaves
        .stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())

    val activeRecord: StateFlow<TimeRecordEntity?> = repository.activeRecord
        .stateIn(viewModelScope, WhileSubscribed(5_000), null)

    val bindings = repository.bindings
        .stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())

    val nfcEnabled = container.settings.nfcEnabled
    val fontPath = container.settings.fontPath

    val themeMode: StateFlow<ThemeMode> = container.themePreferences.mode
        .stateIn(viewModelScope, WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.themePreferences.setMode(mode) }
    }

    val activeType: StateFlow<ActivityTypeEntity?> =
        combine(activeRecord, types) { record, list ->
            record?.let { r -> list.firstOrNull { it.id == r.typeId } }
        }.stateIn(viewModelScope, WhileSubscribed(5_000), null)

    private val _selectedDay = MutableStateFlow(TimeMath.dayStart(System.currentTimeMillis()))
    val selectedDay: StateFlow<Long> = _selectedDay.asStateFlow()

    val dayRecords: StateFlow<List<TimeRecordEntity>> = _selectedDay
        .flatMapLatest { day -> repository.observeRecords(day, TimeMath.nextDayStart(day)) }
        .stateIn(viewModelScope, WhileSubscribed(5_000), emptyList())

    private val _messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages: kotlinx.coroutines.flow.Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val active = runCatching { repository.activeNow() }.getOrNull()
            if (active != null) {
                val name = runCatching { repository.typeById(active.typeId)?.name }.getOrNull() ?: "活动"
                _messages.send("已恢复未结束的计时：$name（${TimeMath.formatClock(active.startAt)} 开始）")
            }
        }
    }

    fun typeName(id: Long): String? = types.value.firstOrNull { it.id == id }?.name

    fun typeById(id: Long): ActivityTypeEntity? = types.value.firstOrNull { it.id == id }

    suspend fun recordsIn(from: Long, to: Long): List<TimeRecordEntity> =
        withContext(Dispatchers.IO) { repository.recordsIn(from, to) }

    suspend fun allTypesIncludingArchived(): List<ActivityTypeEntity> =
        withContext(Dispatchers.IO) { repository.allTypes(includeArchived = true) }

    suspend fun recordsOfType(typeId: Long): List<TimeRecordEntity> =
        withContext(Dispatchers.IO) { repository.recordsOfType(typeId) }

    fun setSelectedDay(dayStart: Long) {
        _selectedDay.value = TimeMath.dayStart(dayStart)
    }

    fun shiftDay(days: Int) {
        _selectedDay.value = TimeMath.dayStart(
            _selectedDay.value + days * TimeMath.DAY
        )
    }

    // ---------------- 计时 ----------------

    fun startTimer(typeId: Long, source: String = SOURCE_MANUAL, targetMinutes: Int? = null) {
        viewModelScope.launch {
            try {
                val name = repository.typeById(typeId)?.name ?: ""
                container.timerController.start(typeId, source, targetMinutes)
                _messages.send("$name 开始")
            } catch (e: Exception) {
                _messages.send(e.message ?: "开始失败")
            }
        }
    }

    fun stopTimer() {
        viewModelScope.launch {
            try {
                container.timerController.stop()
            } catch (e: Exception) {
                _messages.send(e.message ?: "停止失败")
            }
        }
    }

    fun toggleType(typeId: Long) {
        val active = activeRecord.value
        if (active?.typeId == typeId) stopTimer() else startTimer(typeId)
    }

    // ---------------- 记录编辑 ----------------

    fun addManual(typeId: Long, startAt: Long, endAt: Long, comment: String?, targetMinutes: Int? = null) {
        viewModelScope.launch {
            try {
                repository.addManual(typeId, startAt, endAt, comment, targetMinutes)
                _messages.send("已补记")
                container.widgetUpdater.updateAll()
            } catch (e: Exception) {
                _messages.send(e.message ?: "补记失败")
            }
        }
    }

    fun updateRecord(id: Long, typeId: Long, startAt: Long, endAt: Long?, comment: String?) {
        viewModelScope.launch {
            try {
                repository.updateRecord(id, typeId, startAt, endAt, comment)
                _messages.send("已保存")
                container.widgetUpdater.updateAll()
            } catch (e: Exception) {
                _messages.send(e.message ?: "保存失败")
            }
        }
    }

    fun deleteRecord(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteRecord(id)
                container.widgetUpdater.updateAll()
            } catch (e: Exception) {
                _messages.send(e.message ?: "删除失败")
            }
        }
    }

    // ---------------- 分类 ----------------

    fun createType(
        parentId: Long?,
        name: String,
        colorArgb: Int?,
        icon: String? = null,
        targetMinutes: Int = 60,
    ) {
        viewModelScope.launch {
            try {
                repository.createType(parentId, name, colorArgb, icon, targetMinutes)
                _messages.send("已创建「$name」")
            } catch (e: Exception) {
                _messages.send(e.message ?: "创建失败")
            }
        }
    }

    fun updateTypeTarget(id: Long, minutes: Int) {
        viewModelScope.launch {
            try {
                repository.updateTypeTarget(id, minutes)
            } catch (e: Exception) {
                _messages.send(e.message ?: "目标保存失败")
            }
        }
    }

    fun updateRecordTarget(id: Long, minutes: Int) {
        viewModelScope.launch {
            try {
                repository.updateRecordTarget(id, minutes)
                container.widgetUpdater.updateAll()
            } catch (e: Exception) {
                _messages.send(e.message ?: "目标保存失败")
            }
        }
    }

    fun renameType(id: Long, newName: String) {
        viewModelScope.launch {
            try {
                repository.renameType(id, newName)
                _messages.send("已重命名")
            } catch (e: Exception) {
                _messages.send(e.message ?: "重命名失败")
            }
        }
    }

    fun recolorType(id: Long, colorArgb: Int, cascadeDerived: Boolean) {
        viewModelScope.launch {
            try {
                repository.recolorType(id, colorArgb, cascadeDerived)
            } catch (e: Exception) {
                _messages.send(e.message ?: "改色失败")
            }
        }
    }

    fun deleteType(id: Long, mode: DeleteMode) {
        deleteTypes(listOf(id), mode)
    }

    fun deleteTypes(ids: Collection<Long>, mode: DeleteMode) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.toList().forEach { repository.deleteType(it, mode) }
                _messages.send("已删除 ${ids.size} 个分类")
                container.widgetUpdater.updateAll()
            } catch (e: Exception) {
                _messages.send(e.message ?: "删除失败")
            }
        }
    }

    fun moveTypes(ids: Collection<Long>, targetParentId: Long?) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.moveTypes(ids, targetParentId)
                _messages.send("已移动 ${ids.size} 个分类")
                container.widgetUpdater.updateAll()
            } catch (e: Exception) {
                _messages.send(e.message ?: "移动失败")
            }
        }
    }

    fun defaultColorIndexForNewRoot(): Int = types.value.count { it.parentId == null && !it.archived }

    fun defaultColorForChild(parent: ActivityTypeEntity): Int =
        ColorPalette.childColor(parent.colorArgb, types.value.count { it.parentId == parent.id && !it.archived })

    // ---------------- NFC ----------------

    fun bindNfc(uid: String, typeId: Long) {
        viewModelScope.launch {
            try {
                repository.bindNfc(uid, typeId)
                _messages.send("已绑定")
            } catch (e: Exception) {
                _messages.send(e.message ?: "绑定失败")
            }
        }
    }

    fun deleteBinding(id: Long) {
        deleteBindings(listOf(id))
    }

    fun deleteBindings(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.toList().forEach { repository.deleteBinding(it) }
                _messages.send("已删除 ${ids.size} 个绑定")
            } catch (e: Exception) {
                _messages.send(e.message ?: "删除失败")
            }
        }
    }

    fun setBindingEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setBindingEnabled(id, enabled)
            } catch (e: Exception) {
                _messages.send(e.message ?: "操作失败")
            }
        }
    }

    fun setNfcEnabled(enabled: Boolean) {
        container.settings.setNfcEnabled(enabled)
    }

    // ---------------- 导入 / 导出 / 字体 ----------------

    suspend fun parseImportText(text: String): ParseResult =
        withContext(Dispatchers.Default) { ImportExport.parse(text) }

    suspend fun findImportConflicts(nodes: List<FlatNode>): List<String> =
        withContext(Dispatchers.IO) { repository.findImportConflicts(nodes) }

    suspend fun importActivities(nodes: List<FlatNode>, strategy: ImportConflictStrategy): ImportOutcome =
        withContext(Dispatchers.IO) { repository.importActivities(nodes, strategy) }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) { repository.exportJson() }

    fun allTypesForExport(): List<ActivityTypeEntity> = types.value

    // ---------------- 时间记录导入 / 导出 ----------------

    /** 导出 [from, to) 内已结束记录；[typeIds] 为 null / 空表示全部分类。 */
    suspend fun exportRecordsJson(from: Long, to: Long, typeIds: Set<Long>?): String =
        withContext(Dispatchers.IO) {
            repository.exportRecordsJson(
                from = from,
                to = to,
                typeIds = typeIds?.takeIf { it.isNotEmpty() },
            )
        }

    /** 从导出的 JSON 文本读取统计（记录条数 / 分类数 / 时间范围），解析失败返回 null。 */
    fun summarizeRecordExport(jsonText: String): RecordExportSummary? =
        when (val parsed = RecordsImportExport.parse(jsonText)) {
            is RecordParseResult.Ok -> RecordsImportExport.summaryOf(parsed.doc)
            is RecordParseResult.Error -> null
        }

    /** 导入记录 JSON；成功后刷新小部件（记录 Flow 会自动刷新界面）。 */
    suspend fun importRecordsJson(text: String): RecordImportReport =
        withContext(Dispatchers.IO) {
            val report = repository.importRecordsJson(text)
            container.widgetUpdater.updateAll()
            report
        }

    suspend fun importFont(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val path = FontManager.import(container.context, uri)
            container.settings.setFontPath(path)
            path
        }
    }

    fun clearFont() {
        container.settings.setFontPath(null)
    }

    fun toast(text: String) {
        _messages.trySend(text)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel((this[APPLICATION_KEY] as TimingApp).container)
            }
        }
    }
}

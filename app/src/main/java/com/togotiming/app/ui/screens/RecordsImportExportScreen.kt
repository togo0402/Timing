package com.togotiming.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.togotiming.app.R
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.domain.RecordExportSummary
import com.togotiming.app.domain.RecordImportReport
import com.togotiming.app.domain.TimeMath
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.components.SectionTitle
import com.togotiming.app.ui.theme.TimingColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间记录导入 / 导出页（Fluid Glass，与「分类导入 / 导出」并列）：
 *
 * - 导出：时间范围（默认最近 7 天、含两端整天）+ 事件范围（按树缩进多选，勾选父级自动含子级；
 *   不选任何分类 = 全部）+「导出到文件」(SAF CreateDocument, application/json) /「复制到剪贴板」，
 *   并展示导出统计（记录条数 / 分类数 / 时间范围）；
 * - 导入：粘贴 JSON 或 SAF 读取文件 →「解析并导入」→ 展示报告（导入 / 跳过重复 / 创建分类 / 缺失或非法）；
 * - 背景由根节点 AppBackground 提供，本页不铺底色；卡片统一 GlassCard，深色模式自适应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsImportExportScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeTypes by vm.activeTypes.collectAsStateWithLifecycle()
    val orderedTypes = remember(activeTypes) { flattenTypeTree(activeTypes) }

    // 默认最近 7 天：from = 今天 - 6 天，to = 今天（两端都含整天）
    val initialToday = remember { startOfLocalDay(TimeMath.localDate(System.currentTimeMillis())) }
    var fromDay by remember {
        mutableStateOf(startOfLocalDay(TimeMath.localDate(initialToday).minusDays(6)))
    }
    var toDay by remember { mutableStateOf(initialToday) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    var selectedTypeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var busyExport by remember { mutableStateOf(false) }
    var exportSummary by remember { mutableStateOf<RecordExportSummary?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    var pastedText by remember { mutableStateOf("") }
    var busyImport by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var importReport by remember { mutableStateOf<RecordImportReport?>(null) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingExportJson
        pendingExportJson = null
        if (uri != null && text != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("contentResolver.openOutputStream returned null")
                    }.isSuccess
                }
                vm.toast(
                    context.getString(if (ok) R.string.rio_export_done else R.string.rio_export_fail),
                )
            }
        }
    }

    val openJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (text.isNullOrBlank()) {
                    errorText = context.getString(R.string.rio_read_fail)
                } else {
                    pastedText = text
                    errorText = null
                    importReport = null
                    vm.toast(context.getString(R.string.rio_read_done))
                }
            }
        }
    }

    fun toggleTypeScope(type: ActivityTypeEntity) {
        val subtree = subtreeIds(activeTypes, type.id)
        selectedTypeIds = if (type.id in selectedTypeIds) {
            selectedTypeIds - subtree
        } else {
            selectedTypeIds + subtree
        }
    }

    fun exportToFile() {
        if (busyExport) return
        scope.launch {
            busyExport = true
            try {
                val json = vm.exportRecordsJson(
                    from = fromDay,
                    to = TimeMath.nextDayStart(toDay),
                    typeIds = selectedTypeIds.takeIf { it.isNotEmpty() },
                )
                exportSummary = vm.summarizeRecordExport(json)
                pendingExportJson = json
                createDocLauncher.launch(recordExportFileName())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.toast(context.getString(R.string.rio_export_fail))
            } finally {
                busyExport = false
            }
        }
    }

    fun copyToClipboard() {
        if (busyExport) return
        scope.launch {
            busyExport = true
            try {
                val json = vm.exportRecordsJson(
                    from = fromDay,
                    to = TimeMath.nextDayStart(toDay),
                    typeIds = selectedTypeIds.takeIf { it.isNotEmpty() },
                )
                exportSummary = vm.summarizeRecordExport(json)
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard == null) {
                    vm.toast(context.getString(R.string.rio_copy_fail))
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("timing-records", json))
                    vm.toast(context.getString(R.string.rio_copy_done))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.toast(context.getString(R.string.rio_copy_fail))
            } finally {
                busyExport = false
            }
        }
    }

    fun startImport(text: String) {
        if (text.isBlank()) {
            errorText = context.getString(R.string.rio_empty_text)
            return
        }
        if (busyImport) return
        scope.launch {
            busyImport = true
            errorText = null
            importReport = null
            try {
                val report = vm.importRecordsJson(text)
                importReport = report
                vm.toast(context.getString(R.string.rio_import_done, report.imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorText = e.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.rio_import_fail)
            } finally {
                busyImport = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RecordsIoHeader()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------------- 导出 ----------------
            RioSection(title = stringResource(R.string.rio_export_section)) {
                Text(
                    text = stringResource(R.string.rio_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.rio_date_range_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RioDateField(
                        label = stringResource(R.string.rio_start_date),
                        dayMillis = fromDay,
                        modifier = Modifier.weight(1f),
                        onClick = { showStartPicker = true },
                    )
                    Text(
                        text = stringResource(R.string.rio_date_to),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RioDateField(
                        label = stringResource(R.string.rio_end_date),
                        dayMillis = toDay,
                        modifier = Modifier.weight(1f),
                        onClick = { showEndPicker = true },
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.rio_type_scope_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (selectedTypeIds.isEmpty()) {
                            stringResource(R.string.rio_type_scope_all)
                        } else {
                            stringResource(R.string.rio_type_scope_count, selectedTypeIds.size)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (orderedTypes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rio_type_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                selectedTypeIds = orderedTypes.mapTo(LinkedHashSet()) { it.id }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(stringResource(R.string.rio_select_all))
                        }
                        TextButton(
                            onClick = { selectedTypeIds = emptySet() },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(stringResource(R.string.rio_clear_selection))
                        }
                    }
                    orderedTypes.forEach { type ->
                        RioTypeScopeRow(
                            type = type,
                            checked = type.id in selectedTypeIds,
                            onToggle = { toggleTypeScope(type) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rio_type_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RioPrimaryButton(
                        text = stringResource(R.string.rio_export_file),
                        imageVector = Icons.Filled.Save,
                        enabled = !busyExport,
                        onClick = { exportToFile() },
                        modifier = Modifier.weight(1f),
                    )
                    RioGlassButton(
                        text = stringResource(R.string.rio_export_copy),
                        imageVector = Icons.Filled.ContentCopy,
                        enabled = !busyExport,
                        onClick = { copyToClipboard() },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (busyExport) {
                    Spacer(Modifier.height(10.dp))
                    RioBusyRow(text = stringResource(R.string.rio_import_busy))
                }
                exportSummary?.let { summary ->
                    Spacer(Modifier.height(12.dp))
                    RioExportSummaryPanel(summary)
                }
            }

            // ---------------- 导入 ----------------
            RioSection(title = stringResource(R.string.rio_import_section)) {
                Text(
                    text = stringResource(R.string.rio_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pastedText,
                    onValueChange = { pastedText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                    placeholder = { Text(stringResource(R.string.rio_paste_placeholder)) },
                    enabled = !busyImport,
                    shape = RoundedCornerShape(18.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RioPrimaryButton(
                        text = stringResource(R.string.rio_import_action),
                        imageVector = Icons.Filled.Check,
                        enabled = !busyImport && pastedText.isNotBlank(),
                        onClick = { startImport(pastedText) },
                        modifier = Modifier.weight(1f),
                    )
                    RioGlassButton(
                        text = stringResource(R.string.rio_pick_file),
                        enabled = !busyImport,
                        onClick = {
                            openJsonLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (busyImport) {
                    Spacer(Modifier.height(10.dp))
                    RioBusyRow(text = stringResource(R.string.rio_import_busy))
                }
                errorText?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                importReport?.let { result ->
                    Spacer(Modifier.height(12.dp))
                    RioImportReportPanel(result)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showStartPicker) {
        RioDatePickerDialog(
            initialDayMillis = fromDay,
            onDismiss = { showStartPicker = false },
            onPick = { day ->
                fromDay = day
                if (toDay < day) toDay = day
                showStartPicker = false
            },
        )
    }
    if (showEndPicker) {
        RioDatePickerDialog(
            initialDayMillis = toDay,
            onDismiss = { showEndPicker = false },
            onPick = { day ->
                toDay = day
                if (fromDay > day) fromDay = day
                showEndPicker = false
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 顶部 / 分组 / 按钮
// ---------------------------------------------------------------------------

/** 页面头：玻璃圆形返回钮 + 透明标题（不铺底色） */
@Composable
private fun RecordsIoHeader() {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RioGlassCircleButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            onClick = { backDispatcher?.onBackPressed() },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.rio_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** 一组：分组标题 + GlassCard */
@Composable
private fun RioSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title = title, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                content = content,
            )
        }
    }
}

/** 日期字段（玻璃胶囊，点击打开 M3 日历） */
@Composable
private fun RioDateField(
    label: String,
    dayMillis: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TimeMath.formatDate(dayMillis),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 事件范围的一行：缩进 + 复选框 + 颜色点 + 名称 */
@Composable
private fun RioTypeScopeRow(
    type: ActivityTypeEntity,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (type.depth.coerceIn(0, 2) * 16).dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(type.colorArgb)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = type.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 表格里的忙碌提示行 */
@Composable
private fun RioBusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 导出结果面板（记录条数 / 分类数 / 时间范围） */
@Composable
private fun RioExportSummaryPanel(summary: RecordExportSummary) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(primary.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.rio_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.rio_summary_body,
                    summary.recordCount,
                    summary.typeCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(
                    R.string.rio_summary_range,
                    TimeMath.formatDate(summary.from),
                    TimeMath.formatDate((summary.to - 1L).coerceAtLeast(summary.from)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.recordCount == 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rio_summary_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 导入结果面板 */
@Composable
private fun RioImportReportPanel(result: RecordImportReport) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(primary.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.rio_import_report_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.rio_import_report_body,
                    result.imported,
                    result.skipped,
                    result.createdTypes,
                    result.invalid,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.rio_import_report_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 蓝色渐变主按钮 */
@Composable
private fun RioPrimaryButton(
    text: String,
    imageVector: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    val gradient = if (dark) {
        listOf(TimingColors.BlueSoft, TimingColors.Blue)
    } else {
        listOf(TimingColors.Blue, TimingColors.BlueDeep)
    }
    val contentColor = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = TimingColors.Blue.copy(alpha = 0.30f),
                spotColor = TimingColors.Blue.copy(alpha = 0.30f),
            )
            .clip(shape)
            .background(Brush.horizontalGradient(gradient))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 玻璃胶囊按钮（复制 / 从文件读取） */
@Composable
private fun RioGlassButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageVector: ImageVector? = null,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = primary,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 玻璃圆形图标按钮（返回） */
@Composable
private fun RioGlassCircleButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = CircleShape
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(40.dp)
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = tint.copy(alpha = 0.10f),
                spotColor = tint.copy(alpha = 0.16f),
            )
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------
// 日期选择（M3 DatePicker）
// ---------------------------------------------------------------------------

/** M3 日历弹窗：入参 / 回调都是「本地日 00:00」毫秒，内部换算成 DatePicker 需要的 UTC 毫秒。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RioDatePickerDialog(
    initialDayMillis: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = localDayToUtcMillis(initialDayMillis),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val utcMillis = datePickerState.selectedDateMillis
                    if (utcMillis != null) onPick(utcDateToLocalDayStart(utcMillis))
                },
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** 本地日 00:00 → DatePicker 使用的 UTC 日 00:00 毫秒 */
private fun localDayToUtcMillis(dayStartMillis: Long): Long =
    TimeMath.localDate(dayStartMillis)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

/** DatePicker 的 UTC 日 00:00 毫秒 → 本地日 00:00 毫秒 */
private fun utcDateToLocalDayStart(utcMillis: Long): Long =
    Instant.ofEpochMilli(utcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(TimeMath.zone)
        .toInstant()
        .toEpochMilli()

private fun startOfLocalDay(date: LocalDate): Long =
    date.atStartOfDay(TimeMath.zone).toInstant().toEpochMilli()

/** 文件名：timing-records-YYYYMMDD.json */
private fun recordExportFileName(): String =
    "timing-records-" +
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).format(LocalDate.now(TimeMath.zone)) +
        ".json"

/** 按父子关系展开为「父在前、子在后」的扁平列表（按 sortOrder / id 排序，孤儿节点兜底追加）。 */
private fun flattenTypeTree(types: List<ActivityTypeEntity>): List<ActivityTypeEntity> {
    val visible = types.filter { !it.archived }
    val byParent = visible.groupBy { it.parentId }
    val out = ArrayList<ActivityTypeEntity>(visible.size)
    val visited = HashSet<Long>()

    fun walk(parentId: Long?) {
        byParent[parentId].orEmpty()
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .forEach { type ->
                if (!visited.add(type.id)) return@forEach
                out += type
                walk(type.id)
            }
    }

    walk(null)
    visible.sortedWith(compareBy({ it.depth }, { it.sortOrder }, { it.id }))
        .filter { it.id !in visited }
        .forEach { out += it }
    return out
}

/** 自身 + 全部子孙分类 id */
private fun subtreeIds(all: List<ActivityTypeEntity>, rootId: Long): Set<Long> {
    val byParent = all.groupBy { it.parentId }
    val result = LinkedHashSet<Long>()
    val queue = ArrayDeque<Long>()
    queue += rootId
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!result.add(current)) continue
        byParent[current].orEmpty().forEach { queue += it.id }
    }
    return result
}

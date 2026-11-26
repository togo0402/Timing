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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.togotiming.app.R
import com.togotiming.app.data.ImportConflictStrategy
import com.togotiming.app.data.ImportOutcome
import com.togotiming.app.data.ImportReport
import com.togotiming.app.domain.FlatNode
import com.togotiming.app.domain.ParseResult
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.components.SectionTitle
import com.togotiming.app.ui.theme.TimingColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入 / 导出页 v1.1（Fluid Glass，行动指南 §3.8 与 §9）：功能与 v1.0 完全一致——
 * - 两处导入入口（粘贴文本框 / SAF 文件），同一套解析与冲突处理逻辑；
 * - 冲突时弹「跳过 / 重命名导入 / 整体取消」三选项，不静默覆盖；
 * - 导出 JSON 到文件（CreateDocument）或复制到剪贴板。
 *
 * 视觉：背景由根节点 AppBackground 提供，本页不铺底色；标题透明，返回按钮为玻璃圆钮；
 * 导入 / 导出卡片统一 GlassCard；JSON 输入框限高 220dp、内部滚动；颜色全部走
 * MaterialTheme / TimingColors，深色模式自适应。
 */
@Composable
fun ImportExportScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pastedText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var pendingNodes by remember { mutableStateOf<List<FlatNode>?>(null) }
    var conflictPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var pendingExportText by remember { mutableStateOf<String?>(null) }
    var busyExport by remember { mutableStateOf(false) }

    suspend fun applyImport(nodes: List<FlatNode>, strategy: ImportConflictStrategy) {
        busy = true
        try {
            when (val outcome = vm.importActivities(nodes, strategy)) {
                is ImportOutcome.Success -> {
                    report = outcome.report
                    errorText = null
                }

                is ImportOutcome.Failure -> errorText = outcome.message
            }
        } catch (e: Exception) {
            errorText = e.message ?: context.getString(R.string.ie_import_fail)
        } finally {
            busy = false
        }
    }

    fun startImport(text: String) {
        if (text.isBlank()) {
            errorText = context.getString(R.string.ie_empty_text)
            return
        }
        scope.launch {
            busy = true
            errorText = null
            report = null
            try {
                when (val parsed = vm.parseImportText(text)) {
                    is ParseResult.Error -> errorText = parsed.message
                    is ParseResult.Ok -> {
                        val conflicts = vm.findImportConflicts(parsed.nodes)
                        if (conflicts.isEmpty()) {
                            applyImport(parsed.nodes, ImportConflictStrategy.SKIP)
                        } else {
                            pendingNodes = parsed.nodes
                            conflictPaths = conflicts
                            showConflictDialog = true
                        }
                    }
                }
            } catch (e: Exception) {
                errorText = e.message ?: context.getString(R.string.ie_import_fail)
            } finally {
                busy = false
            }
        }
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val text = pendingExportText
        pendingExportText = null
        if (uri != null && text != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("无法写入文件")
                    }.isSuccess
                }
                vm.toast(
                    context.getString(if (ok) R.string.ie_export_done else R.string.ie_export_fail),
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
                    errorText = context.getString(R.string.ie_read_fail)
                } else {
                    pastedText = text
                    startImport(text)
                }
            }
        }
    }

    fun exportToFile() {
        scope.launch {
            busyExport = true
            try {
                val json = vm.exportJson()
                pendingExportText = json
                createDocLauncher.launch("timing-activities.json")
            } catch (e: Exception) {
                vm.toast(context.getString(R.string.ie_export_fail))
            } finally {
                busyExport = false
            }
        }
    }

    fun copyToClipboard() {
        scope.launch {
            try {
                val json = vm.exportJson()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard == null) {
                    vm.toast(context.getString(R.string.ie_copy_fail))
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("timing-activities", json))
                    vm.toast(context.getString(R.string.ie_copy_done))
                }
            } catch (e: Exception) {
                vm.toast(context.getString(R.string.ie_copy_fail))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ImportExportHeader()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------------- 导入 ----------------
            IeSection(title = stringResource(R.string.ie_import_section)) {
                Text(
                    text = stringResource(R.string.ie_import_hint),
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
                    placeholder = { Text(stringResource(R.string.ie_paste_placeholder)) },
                    enabled = !busy,
                    shape = RoundedCornerShape(18.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IePrimaryButton(
                        text = stringResource(R.string.ie_parse_import),
                        imageVector = Icons.Filled.Check,
                        enabled = !busy && pastedText.isNotBlank(),
                        onClick = { startImport(pastedText) },
                    )
                    IeGlassButton(
                        text = stringResource(R.string.ie_pick_file),
                        enabled = !busy,
                        onClick = {
                            openJsonLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ie_busy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                errorText?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                report?.let { result ->
                    Spacer(Modifier.height(12.dp))
                    ImportReportPanel(result)
                }
            }

            // ---------------- 导出 ----------------
            IeSection(title = stringResource(R.string.ie_export_section)) {
                Text(
                    text = stringResource(R.string.ie_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IeGlassButton(
                        text = stringResource(R.string.ie_export_file),
                        imageVector = Icons.Filled.Save,
                        enabled = !busyExport,
                        onClick = { exportToFile() },
                        modifier = Modifier.weight(1f),
                    )
                    IeGlassButton(
                        text = stringResource(R.string.ie_export_copy),
                        imageVector = Icons.Filled.ContentCopy,
                        enabled = true,
                        onClick = { copyToClipboard() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showConflictDialog) {
        val preview = conflictPaths.take(5).joinToString("、") +
            if (conflictPaths.size > 5) "，等 ${conflictPaths.size} 个" else ""
        AlertDialog(
            onDismissRequest = {
                showConflictDialog = false
                pendingNodes = null
                conflictPaths = emptyList()
            },
            title = { Text(stringResource(R.string.ie_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ie_conflict_body,
                        conflictPaths.size,
                        preview,
                    ),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val nodes = pendingNodes
                        showConflictDialog = false
                        pendingNodes = null
                        conflictPaths = emptyList()
                        if (nodes != null) {
                            scope.launch { applyImport(nodes, ImportConflictStrategy.RENAME) }
                        }
                    }) {
                        Text(stringResource(R.string.ie_conflict_rename))
                    }
                    TextButton(onClick = {
                        val nodes = pendingNodes
                        showConflictDialog = false
                        pendingNodes = null
                        conflictPaths = emptyList()
                        if (nodes != null) {
                            scope.launch { applyImport(nodes, ImportConflictStrategy.SKIP) }
                        }
                    }) {
                        Text(stringResource(R.string.ie_conflict_skip))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConflictDialog = false
                    pendingNodes = null
                    conflictPaths = emptyList()
                    vm.toast(context.getString(R.string.ie_cancelled))
                }) {
                    Text(stringResource(R.string.ie_conflict_cancel))
                }
            },
        )
    }
}

/** 页面头：玻璃圆形返回钮 + 透明标题（不铺底色） */
@Composable
private fun ImportExportHeader() {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IeGlassCircleButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            onClick = { backDispatcher?.onBackPressed() },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.ie_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** 一组：分组标题 + GlassCard */
@Composable
private fun IeSection(
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

/** 导入结果面板（主题色低透明底，深色自适应） */
@Composable
private fun ImportReportPanel(result: ImportReport) {
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
                text = stringResource(R.string.ie_report_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.ie_report_body,
                    result.imported,
                    result.skipped,
                    result.renamed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ie_report_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 蓝色渐变主按钮（解析并导入） */
@Composable
private fun IePrimaryButton(
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

/** 玻璃胶囊按钮（从文件读取 / 导出到文件 / 复制） */
@Composable
private fun IeGlassButton(
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
private fun IeGlassCircleButton(
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

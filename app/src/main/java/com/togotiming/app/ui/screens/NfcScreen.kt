package com.togotiming.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.togotiming.app.R
import com.togotiming.app.nfc.NfcController
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.components.GlassPill
import com.togotiming.app.ui.components.SectionTitle
import com.togotiming.app.ui.theme.TimingColors

/** 待确认的删除请求：单个删除（batch=false）走 vm.deleteBinding，批量删除（batch=true）走 vm.deleteBindings */
private data class DeleteRequest(val ids: List<Long>, val batch: Boolean)

/**
 * NFC 页（v1.1 Fluid Glass）：硬件状态卡、绑定入口、已绑定标签、使用提示。
 *
 * 底色由根导航的 AppBackground 提供，本页不再铺任何背景；
 * 卡片统一用 GlassCard，文字/图标颜色全部取自 MaterialTheme / TimingColors，深色模式自适应。
 * 绑定对话框在 UID 下方显示 NfcController.lastTagInfo 的标签诊断（是否可写、能否后台拉起）。
 * 已绑定标签支持「批量管理」：进入选择模式后行首显示 Checkbox，顶部显示已选/全选/取消，底部悬浮玻璃操作条批量删除。
 * 单个删除与批量删除都必须二次确认，确认后才调用 ViewModel；取消不做任何修改。
 */
@Composable
fun NfcScreen(vm: MainViewModel, nfc: NfcController) {
    val context = LocalContext.current
    val bindings by vm.bindings.collectAsState()
    val types by vm.types.collectAsState()
    val nfcEnabled by vm.nfcEnabled.collectAsState()
    // 绑定模式下扫到的标签诊断结果（可写 / 只读 / 无 NDEF / 写入失败）
    val lastTagInfo by nfc.lastTagInfo.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var supported by remember { mutableStateOf(nfc.isSupported()) }
    var enabled by remember { mutableStateOf(nfc.isEnabled()) }
    var bindingMode by remember { mutableStateOf(false) }
    var discoveredUid by remember { mutableStateOf<String?>(null) }
    // 批量管理
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 删除确认（单个 / 批量共用）
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                supported = nfc.isSupported()
                enabled = nfc.isEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 打开 / 关闭绑定对话框时都清空上一次的标签诊断，避免旧结果串到下一次扫描
    DisposableEffect(bindingMode) {
        if (bindingMode) {
            nfc.clearTagInfo()
            discoveredUid = null
            nfc.setBindingSink { uid -> discoveredUid = uid }
        } else {
            nfc.setBindingSink(null)
            nfc.clearTagInfo()
            discoveredUid = null
        }
        onDispose {
            nfc.setBindingSink(null)
            nfc.clearTagInfo()
        }
    }

    // 绑定列表变化（例如删除成功）后剔除已不存在的选中项，避免计数虚高
    LaunchedEffect(bindings) {
        if (selectedIds.isNotEmpty()) {
            val alive = bindings.mapTo(HashSet()) { it.id }
            selectedIds = selectedIds.filterTo(HashSet()) { it in alive }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 10.dp,
                // 选择模式下给底部悬浮操作条留出空间
                bottom = if (selectionMode) 124.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------- 顶部标题（透明，不加卡片） ----------------
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.nfc_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.nfc_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------------- 状态卡：NFC 硬件 + 计时总开关 ----------------
            item {
                val statusText = when {
                    !supported -> stringResource(R.string.nfc_hardware_unsupported)
                    enabled -> stringResource(R.string.nfc_hardware_ready)
                    else -> stringResource(R.string.nfc_hardware_off)
                }
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassIconBox(Icons.Filled.Nfc)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.nfc_hardware_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (enabled) {
                                    Spacer(Modifier.width(8.dp))
                                    GlassPill(
                                        text = stringResource(R.string.nfc_hardware_on),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassTextAction(
                            text = stringResource(R.string.nfc_action_settings),
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                                }
                            },
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassIconBox(Icons.Filled.Bolt)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.nfc_master_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.nfc_master_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = nfcEnabled, onCheckedChange = { vm.setNfcEnabled(it) })
                    }
                }
            }

            // ---------------- 绑定新标签：蓝色渐变胶囊 ----------------
            item {
                GradientPillButton(
                    text = stringResource(R.string.nfc_bind_new),
                    icon = Icons.Filled.Add,
                    enabled = supported && enabled,
                    onClick = { bindingMode = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------------- 已绑定标签：普通标题 + 批量管理入口 / 选择模式标题 ----------------
            item {
                if (selectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.nfc_selection_count, selectedIds.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        GlassTextAction(
                            text = stringResource(R.string.nfc_select_all),
                            onClick = { selectedIds = bindings.map { it.id }.toSet() },
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassTextAction(
                            text = stringResource(R.string.action_cancel),
                            onClick = {
                                selectionMode = false
                                selectedIds = emptySet()
                            },
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle(
                            title = stringResource(R.string.nfc_section_bound),
                            trailing = stringResource(R.string.nfc_section_bound_count, bindings.size),
                            modifier = Modifier.weight(1f),
                        )
                        if (bindings.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            GlassTextAction(
                                text = stringResource(R.string.nfc_batch_manage),
                                onClick = {
                                    selectionMode = true
                                    selectedIds = emptySet()
                                },
                            )
                        }
                    }
                }
            }

            if (bindings.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Nfc,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.nfc_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                items(bindings, key = { it.id }) { binding ->
                    val type = types.firstOrNull { it.id == binding.typeId }
                    val dotColor = type?.let { Color(it.colorArgb) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                    val selected = binding.id in selectedIds
                    val toggleSelection = {
                        selectedIds = if (selected) selectedIds - binding.id else selectedIds + binding.id
                    }
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        // 选择模式下整行可点，切换选中；普通模式不整行点击
                        onClick = if (selectionMode) toggleSelection else null,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selectionMode) {
                                // onCheckedChange = null：点击交给整行处理，避免与行点击双触发
                                Checkbox(checked = selected, onCheckedChange = null)
                                Spacer(Modifier.width(4.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = type?.name ?: stringResource(R.string.nfc_deleted_type),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (binding.enabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    text = binding.tagUid,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!selectionMode) {
                                Switch(
                                    checked = binding.enabled,
                                    onCheckedChange = { vm.setBindingEnabled(binding.id, it) },
                                )
                                IconButton(onClick = {
                                    // 单个删除也需要二次确认
                                    deleteRequest = DeleteRequest(listOf(binding.id), batch = false)
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.nfc_cd_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- 使用提示（简短 3 条） ----------------
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.nfc_hints_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HintLine(stringResource(R.string.nfc_hint_1))
                    HintLine(stringResource(R.string.nfc_hint_2))
                    HintLine(stringResource(R.string.nfc_hint_3))
                }
            }
        }

        // ---------------- 底部悬浮玻璃操作条（仅选择模式） ----------------
        if (selectionMode) {
            GlassCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.nfc_selection_count, selectedIds.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.nfc_delete_single_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    GlassTextAction(
                        text = stringResource(R.string.nfc_batch_delete, selectedIds.size),
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                deleteRequest = DeleteRequest(selectedIds.toList(), batch = true)
                            }
                        },
                        color = MaterialTheme.colorScheme.error,
                        enabled = selectedIds.isNotEmpty(),
                    )
                }
            }
        }
    }

    // ---------------- 绑定对话框 ----------------
    if (bindingMode) {
        val uid = discoveredUid
        AlertDialog(
            onDismissRequest = { bindingMode = false },
            title = { Text(stringResource(R.string.nfc_bind_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.nfc_bind_dialog_hint))
                    if (uid == null) {
                        Text(
                            text = stringResource(R.string.nfc_bind_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.nfc_bind_uid, uid),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                        // 标签诊断：NfcController 的诊断文案以「可写」开头表示成功，其余为警告
                        val tagInfo = lastTagInfo
                        if (tagInfo != null) {
                            val writable = tagInfo.startsWith("可写")
                            val accent = if (writable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accent.copy(alpha = 0.10f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = tagInfo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accent,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.nfc_bind_pick_type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val parentIds = types
                                .filter { !it.archived }
                                .mapNotNull { it.parentId }
                                .toSet()
                            val leaves = types.filter { !it.archived && it.id !in parentIds }
                            items(leaves, key = { it.id }) { type ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(type.colorArgb)),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = type.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    TextButton(onClick = {
                                        vm.bindNfc(uid, type.id)
                                        bindingMode = false
                                    }) { Text(stringResource(R.string.nfc_bind_action)) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { bindingMode = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        )
    }

    // ---------------- 删除确认对话框（单个 / 批量） ----------------
    deleteRequest?.let { request ->
        val batch = request.batch
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text(stringResource(R.string.nfc_delete_title)) },
            text = {
                Text(
                    text = if (batch) {
                        stringResource(R.string.nfc_delete_batch_desc, request.ids.size)
                    } else {
                        stringResource(R.string.nfc_delete_single_desc)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (batch) {
                        vm.deleteBindings(request.ids)
                        // 批量删除完成后退出选择模式并清空选择
                        selectionMode = false
                        selectedIds = emptySet()
                    } else {
                        request.ids.firstOrNull()?.let { vm.deleteBinding(it) }
                    }
                    deleteRequest = null
                }) {
                    Text(
                        text = stringResource(R.string.nfc_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                // 取消：什么都不做，不删除、不退出选择模式
                TextButton(onClick = { deleteRequest = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        )
    }
}

// ---------------------------------------------------------------------------
// Glass 风小组件
// ---------------------------------------------------------------------------

/** 主题色低透明圆角图标底座，深色/浅色自适应 */
@Composable
private fun GlassIconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 主题色淡底胶囊文字按钮（去设置 / 批量管理 / 全选 / 取消 / 删除 N 个），可禁用 */
@Composable
private fun GlassTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) {
        color
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    val containerColor = if (enabled) {
        color.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    Text(
        text = text,
        color = contentColor,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 蓝色渐变胶囊主按钮（绑定新标签），不可用时转为低饱和玻璃灰 */
@Composable
private fun GradientPillButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    val gradient = when {
        !enabled -> listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        )
        dark -> listOf(TimingColors.BlueSoft, TimingColors.Blue)
        else -> listOf(TimingColors.Blue, TimingColors.BlueDeep)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glow = TimingColors.Blue.copy(alpha = if (enabled) 0.30f else 0f)

    Row(
        modifier = modifier
            .height(48.dp)
            .shadow(
                elevation = if (enabled) 10.dp else 0.dp,
                shape = shape,
                ambientColor = glow,
                spotColor = glow,
            )
            .clip(shape)
            .background(Brush.horizontalGradient(gradient))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 使用提示的单行：主题色小圆点 + 一句短文案 */
@Composable
private fun HintLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

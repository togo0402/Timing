package com.togotiming.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.togotiming.app.R
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.DeleteMode
import com.togotiming.app.domain.ColorPalette
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.components.ColorPickerDialog
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.theme.TimingColors
import kotlin.math.roundToInt

/** 每日目标（分钟）：新建时默认 60，合法范围 1..1440。 */
private const val DEFAULT_TARGET_MINUTES = 60
private const val TARGET_MINUTES_MIN = 1
private const val TARGET_MINUTES_MAX = 24 * 60

/**
 * v1.1 分类管理页（Fluid Glass）。
 *
 * - 背景由 AppNav 的 AppBackground 统一提供（本页不铺底色）；
 * - 顶部「分类」大标题透明（日期式小字 + 标题），右上为蓝色渐变胶囊「新建一级分类」；
 * - 分类行：GlassCard（半透明 + 圆角 + 轻阴影 + 左侧色条），按 depth 缩进；
 *   每行显示名称 + 每日目标（点击可改；父节点额外显示 N 个子分类），叶子不再显示「叶子」；
 * - 行内操作为低饱和玻璃圆钮（添加子级 / 改名 / 改色 / 删除），深色模式自动适配；
 * - 新建 / 改名 / 目标 / 改色 / 删除全部沿用原逻辑，仅做圆角与深色容器适配；
 *   颜色选择器 ColorPickerDialog 本身基于 MaterialTheme，深色自动适配；
 * - 批量模式（勾选 + 玻璃操作条 + 批量删除二选一）之上，长按已选行可拖动移动：
 *   悬停的普通分类行高亮为放置目标，拖到空白处则移到一级；悬停自身/子孙为无效目标。
 */
@Composable
fun TypesScreen(vm: MainViewModel) {
    val allTypes by vm.types.collectAsState()
    val visibleTypes = remember(allTypes) { allTypes.filter { !it.archived } }
    val ordered = remember(visibleTypes) { flattenVisibleTypes(visibleTypes) }
    val childCounts = remember(visibleTypes) { visibleTypes.groupingBy { it.parentId }.eachCount() }

    var dialog by remember { mutableStateOf<TypeDialog?>(null) }
    // 批量选择模式（选择期间行点击=勾选，不触发编辑）
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val visibleIds = remember(ordered) { ordered.mapTo(HashSet()) { it.id } }
    val allSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)

    // 不可作为放置目标的节点：已选集合自身及其全部子孙（数据层会拒绝移动到自身/子分类下）
    val blockedTargetIds = remember(selectedIds, visibleTypes) {
        if (selectedIds.isEmpty()) {
            emptySet()
        } else {
            val childrenByParent = visibleTypes.groupBy { it.parentId }
            val blocked = HashSet<Long>()
            fun markSubtree(id: Long) {
                if (!blocked.add(id)) return
                childrenByParent[id].orEmpty().forEach { markSubtree(it.id) }
            }
            selectedIds.forEach { markSubtree(it) }
            blocked
        }
    }

    // 批量拖拽移动：长按已选行 → 拖动 → 松手按悬停目标调用 moveTypes
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val dragController = remember { BatchDragController() }
    var listAreaBounds by remember { mutableStateOf(Rect.Zero) }
    val moveInvalidMessage = stringResource(R.string.types_move_invalid_target)
    val exitSelectionMode = {
        selectionMode = false
        selectedIds = emptySet()
        dragController.cancel()
    }
    // 行间空隙 12dp：8dp 内吸附到最近行，超过则视为空白区域（→ 移到一级）
    dragController.snapPx = with(density) { 8.dp.toPx() }
    dragController.dragThresholdPx = with(density) { 10.dp.toPx() }
    dragController.selectedIdsProvider = { selectedIds }
    dragController.blockedIdsProvider = { blockedTargetIds }
    dragController.onDrop = { ids, targetParentId ->
        vm.moveTypes(ids, targetParentId)
        exitSelectionMode()
    }
    dragController.onInvalidDrop = { vm.toast(moveInvalidMessage) }
    dragController.onStartFeedback = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TypesHeader(
            count = ordered.size,
            selectionMode = selectionMode,
            selectedCount = selectedIds.size,
            allSelected = allSelected,
            canSelect = ordered.isNotEmpty(),
            onEnterSelection = {
                dragController.cancel()
                selectionMode = true
                selectedIds = emptySet()
            },
            onToggleSelectAll = {
                selectedIds = if (allSelected) emptySet() else visibleIds
            },
            onExitSelection = exitSelectionMode,
            onCreateRoot = {
                dialog = TypeDialog.Create(
                    parent = null,
                    defaultColor = ColorPalette.rootColor(vm.defaultColorIndexForNewRoot()),
                )
            },
        )

        if (ordered.isEmpty()) {
            EmptyTypesHint(modifier = Modifier.weight(1f))
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { listAreaBounds = it.boundsInRoot() },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ordered, key = { it.id }) { type ->
                        val childCount = childCounts[type.id] ?: 0
                        TypeRow(
                            type = type,
                            childCount = childCount,
                            canAddChild = type.depth < 2,
                            selectionMode = selectionMode,
                            selected = type.id in selectedIds,
                            isDropTarget = dragController.hoverTargetId == type.id,
                            dragController = if (selectionMode) dragController else null,
                            onToggleSelect = {
                                if (!dragController.consumeClickSuppression()) {
                                    selectedIds = if (type.id in selectedIds) {
                                        selectedIds - type.id
                                    } else {
                                        selectedIds + type.id
                                    }
                                }
                            },
                            onAddChild = {
                                dialog = TypeDialog.Create(
                                    parent = type,
                                    defaultColor = vm.defaultColorForChild(type),
                                )
                            },
                            onRename = { dialog = TypeDialog.Rename(type) },
                            onEditTarget = { dialog = TypeDialog.EditTarget(type) },
                            onRecolor = {
                                dialog = if (childCount > 0) {
                                    TypeDialog.RecolorCascade(type)
                                } else {
                                    TypeDialog.PickColor(type, cascadeDerived = false)
                                }
                            },
                            onDelete = {
                                dialog = if (childCount > 0) {
                                    TypeDialog.DeleteChoice(type)
                                } else {
                                    TypeDialog.ConfirmDelete(type)
                                }
                            },
                        )
                    }
                }
                // 拖动中的浮动玻璃徽标：跟随手指显示「移动 N 个」
                if (dragController.isDragging) {
                    DragBadge(
                        count = dragController.activeIds.size,
                        invalid = dragController.hoverInvalid,
                        modifier = Modifier.offset {
                            val local = dragController.pointer - listAreaBounds.topLeft
                            IntOffset(
                                (local.x + 8.dp.toPx()).roundToInt(),
                                (local.y + 10.dp.toPx()).roundToInt(),
                            )
                        },
                    )
                }
            }
        }

        if (selectionMode) {
            BatchActionBar(
                selectedCount = selectedIds.size,
                onDelete = {
                    if (selectedIds.isNotEmpty()) {
                        dialog = TypeDialog.BatchDelete(selectedIds.toList())
                    }
                },
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            )
        }
    }

    when (val current = dialog) {
        null -> Unit
        is TypeDialog.Create -> CreateTypeDialog(
            parent = current.parent,
            defaultColor = current.defaultColor,
            onDismiss = { dialog = null },
            onCreate = { name, customColor, targetMinutes ->
                vm.createType(
                    parentId = current.parent?.id,
                    name = name,
                    colorArgb = customColor,
                    targetMinutes = targetMinutes,
                )
                dialog = null
            },
        )
        is TypeDialog.EditTarget -> TargetMinutesDialog(
            node = current.node,
            onDismiss = { dialog = null },
            onConfirm = { minutes ->
                vm.updateTypeTarget(current.node.id, minutes)
                dialog = null
            },
        )
        is TypeDialog.Rename -> RenameTypeDialog(
            node = current.node,
            onDismiss = { dialog = null },
            onConfirm = { newName ->
                vm.renameType(current.node.id, newName)
                dialog = null
            },
        )
        is TypeDialog.RecolorCascade -> ThreeOptionDialog(
            title = stringResource(R.string.types_recolor_title),
            message = stringResource(
                R.string.types_recolor_has_children_message,
                current.node.name,
                childCounts[current.node.id] ?: 0,
            ),
            primaryText = stringResource(R.string.types_recolor_cascade),
            secondaryText = stringResource(R.string.types_recolor_parent_only),
            onPrimary = { dialog = TypeDialog.PickColor(current.node, cascadeDerived = true) },
            onSecondary = { dialog = TypeDialog.PickColor(current.node, cascadeDerived = false) },
            onCancel = { dialog = null },
        )
        is TypeDialog.PickColor -> ColorPickerDialog(
            initialColor = current.node.colorArgb,
            onDismiss = { dialog = null },
            onConfirm = { newColor ->
                vm.recolorType(current.node.id, newColor, current.cascadeDerived)
                dialog = null
            },
        )
        is TypeDialog.DeleteChoice -> ThreeOptionDialog(
            title = stringResource(R.string.types_delete_title),
            message = stringResource(
                R.string.types_delete_choice_message,
                current.node.name,
                childCounts[current.node.id] ?: 0,
            ),
            primaryText = stringResource(R.string.types_delete_promote),
            secondaryText = stringResource(R.string.types_delete_subtree),
            onPrimary = {
                vm.deleteType(current.node.id, DeleteMode.PROMOTE_CHILDREN)
                dialog = null
            },
            onSecondary = {
                vm.deleteType(current.node.id, DeleteMode.DELETE_SUBTREE)
                dialog = null
            },
            onCancel = { dialog = null },
        )
        is TypeDialog.ConfirmDelete -> ConfirmDeleteDialog(
            node = current.node,
            onDismiss = { dialog = null },
            onConfirm = {
                vm.deleteType(current.node.id, DeleteMode.DELETE_SUBTREE)
                dialog = null
            },
        )
        is TypeDialog.BatchDelete -> ThreeOptionDialog(
            title = stringResource(R.string.types_batch_delete_title),
            message = stringResource(R.string.types_batch_delete_message, current.ids.size),
            primaryText = stringResource(R.string.types_batch_delete_promote),
            secondaryText = stringResource(R.string.types_batch_delete_subtree),
            onPrimary = {
                vm.deleteTypes(current.ids, DeleteMode.PROMOTE_CHILDREN)
                exitSelectionMode()
                dialog = null
            },
            onSecondary = {
                vm.deleteTypes(current.ids, DeleteMode.DELETE_SUBTREE)
                exitSelectionMode()
                dialog = null
            },
            onCancel = { dialog = null },
        )
    }
}

/** 页面内互斥的对话框状态。 */
private sealed interface TypeDialog {
    data class Create(val parent: ActivityTypeEntity?, val defaultColor: Int) : TypeDialog
    data class Rename(val node: ActivityTypeEntity) : TypeDialog
    data class EditTarget(val node: ActivityTypeEntity) : TypeDialog
    data class RecolorCascade(val node: ActivityTypeEntity) : TypeDialog
    data class PickColor(val node: ActivityTypeEntity, val cascadeDerived: Boolean) : TypeDialog
    data class DeleteChoice(val node: ActivityTypeEntity) : TypeDialog
    data class ConfirmDelete(val node: ActivityTypeEntity) : TypeDialog
    data class BatchDelete(val ids: List<Long>) : TypeDialog
}

// ---------------------------------------------------------------------------
// 顶部标题栏（透明，右侧蓝色渐变胶囊）
// ---------------------------------------------------------------------------

@Composable
private fun TypesHeader(
    count: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    canSelect: Boolean,
    onEnterSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onExitSelection: () -> Unit,
    onCreateRoot: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.types_header_count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp),
            )
            Text(
                text = if (selectionMode) {
                    stringResource(R.string.types_selected_count, selectedCount)
                } else {
                    stringResource(R.string.nav_types)
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        if (selectionMode) {
            TextButton(onClick = onToggleSelectAll) {
                Text(stringResource(if (allSelected) R.string.types_unselect_all else R.string.types_select_all))
            }
            TextButton(onClick = onExitSelection) {
                Text(stringResource(R.string.action_cancel))
            }
        } else {
            if (canSelect) {
                GlassPillButton(
                    text = stringResource(R.string.types_batch_entry),
                    onClick = onEnterSelection,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            GradientPillButton(
                text = stringResource(R.string.types_new_root),
                imageVector = Icons.Filled.Add,
                onClick = onCreateRoot,
            )
        }
    }
}

/** 蓝色渐变胶囊按钮（新建一级分类） */
@Composable
private fun GradientPillButton(
    text: String,
    imageVector: ImageVector,
    onClick: () -> Unit,
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
        modifier = Modifier
            .height(40.dp)
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = TimingColors.Blue.copy(alpha = 0.30f),
                spotColor = TimingColors.Blue.copy(alpha = 0.30f),
            )
            .clip(shape)
            .background(Brush.horizontalGradient(gradient))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 玻璃胶囊按钮（顶栏「批量」入口） */
@Composable
private fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 底部批量操作条（玻璃条 + 红色删除胶囊） */
@Composable
private fun BatchActionBar(
    selectedCount: Int,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(24.dp)
    val enabled = selectedCount > 0
    val errorColor = MaterialTheme.colorScheme.error
    val shadowColor = errorColor.copy(alpha = 0.18f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 14.dp, shape = shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(
                if (dark) {
                    Brush.linearGradient(listOf(TimingColors.DarkGlass, TimingColors.DarkGlassSoft))
                } else {
                    Brush.linearGradient(listOf(TimingColors.LightGlass, TimingColors.LightGlassSoft))
                }
            )
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.types_selected_count, selectedCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.types_batch_drag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(50))
                .background(errorColor.copy(alpha = if (enabled) 1f else 0.35f))
                .clickable(enabled = enabled, onClick = onDelete)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onError,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.types_batch_delete_action, selectedCount),
                color = MaterialTheme.colorScheme.onError,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** 批量拖拽移动的浮动玻璃徽标（跟随手指，显示「移动 N 个」） */
@Composable
private fun DragBadge(
    count: Int,
    invalid: Boolean,
    modifier: Modifier = Modifier,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    val accent = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val shadowColor = accent.copy(alpha = 0.35f)
    Row(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(width = 1.5.dp, color = accent.copy(alpha = 0.85f), shape = shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.types_move_badge, count),
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 批量拖拽移动控制：行位置登记 + 长按拖动 + 命中检测 + 松手落点。
 *
 * 放置目标规则：
 * - 悬停在未选中的普通分类行 → 移动到该分类下；
 * - 悬停在空白区域（行间空隙 16dp 内自动吸附到最近行）→ 无目标行，移到一级；
 * - 悬停在已选集合 / 自身子孙 → 不可放置（取消并提示）。
 */
private class BatchDragController {
    /** 选择模式下所有行的可见区域（root 坐标），由每行 onGloballyPositioned 写入 */
    val rowBounds = mutableMapOf<Long, Rect>()

    var snapPx: Float = 0f
    var dragThresholdPx: Float = 1f
    var selectedIdsProvider: () -> Set<Long> = { emptySet() }
    var blockedIdsProvider: () -> Set<Long> = { emptySet() }
    var onDrop: (List<Long>, Long?) -> Unit = { _, _ -> }
    var onInvalidDrop: () -> Unit = {}
    /** 长按进入拖动时触发（用于震动反馈） */
    var onStartFeedback: () -> Unit = {}

    var pointer by mutableStateOf(Offset.Zero)
        private set
    var activeIds by mutableStateOf<List<Long>>(emptyList())
        private set
    var hoverTargetId by mutableStateOf<Long?>(null)
        private set
    var hoverInvalid by mutableStateOf(false)
        private set

    private var invalidBlocked = false
    private var totalDrag = 0f
    /** 长按进入拖动后抑制同一次手势产生的「点击=勾选」 */
    private var pendingClickSuppression = false

    val isDragging: Boolean get() = activeIds.isNotEmpty()

    /** 若刚进入过长按拖动，消费掉随后到来的点击（返回 true 表示本次点击应忽略） */
    fun consumeClickSuppression(): Boolean {
        if (!pendingClickSuppression) return false
        pendingClickSuppression = false
        return true
    }

    fun start(rowId: Long, localOffset: Offset) {
        val bounds = rowBounds[rowId] ?: return
        val ids = selectedIdsProvider().toList()
        if (ids.isEmpty()) return
        activeIds = ids
        pointer = bounds.topLeft + localOffset
        totalDrag = 0f
        hoverTargetId = null
        hoverInvalid = false
        invalidBlocked = false
        pendingClickSuppression = true
        onStartFeedback()
    }

    fun drag(delta: Offset) {
        if (activeIds.isEmpty()) return
        totalDrag += delta.getDistance()
        pointer += delta
        resolve()
    }

    fun end() {
        val ids = activeIds
        val target = hoverTargetId
        val blocked = invalidBlocked
        val invalid = hoverInvalid
        val moved = totalDrag >= dragThresholdPx
        reset()
        if (ids.isEmpty() || !moved) return
        when {
            blocked -> onInvalidDrop()
            invalid -> Unit // 落在被拖动集合上：静默取消
            else -> onDrop(ids, target)
        }
    }

    fun cancel() = reset()

    private fun resolve() {
        val selected = selectedIdsProvider()
        val blocked = blockedIdsProvider()
        val containing = rowBounds.entries.firstOrNull { it.value.contains(pointer) }
        val effective = containing ?: rowBounds.entries
            .map { it to verticalGap(it.value, pointer.y) }
            .filter { it.second <= snapPx }
            .minByOrNull { it.second }
            ?.first
        if (effective == null) {
            hoverTargetId = null
            hoverInvalid = false
            invalidBlocked = false
            return
        }
        val id = effective.key
        when {
            id in selected -> {
                hoverTargetId = null
                hoverInvalid = true
                invalidBlocked = false
            }
            id in blocked -> {
                hoverTargetId = null
                hoverInvalid = true
                invalidBlocked = true
            }
            else -> {
                hoverTargetId = id
                hoverInvalid = false
                invalidBlocked = false
            }
        }
    }

    private fun reset() {
        activeIds = emptyList()
        pointer = Offset.Zero
        hoverTargetId = null
        hoverInvalid = false
        invalidBlocked = false
        totalDrag = 0f
        pendingClickSuppression = false
    }
}

/** 行与手指的垂直距离（用于行间空隙吸附） */
private fun verticalGap(rect: Rect, y: Float): Float = when {
    y < rect.top -> rect.top - y
    y > rect.bottom -> y - rect.bottom
    else -> 0f
}

// ---------------------------------------------------------------------------
// 分类行（GlassCard）
// ---------------------------------------------------------------------------

@Composable
private fun TypeRow(
    type: ActivityTypeEntity,
    childCount: Int,
    canAddChild: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    isDropTarget: Boolean,
    dragController: BatchDragController?,
    onToggleSelect: () -> Unit,
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onEditTarget: () -> Unit,
    onRecolor: () -> Unit,
    onDelete: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val eventColor = Color(type.colorArgb)
    val indent = (type.depth.coerceIn(0, 2) * 16).dp
    val targetText = if (childCount > 0) {
        stringResource(R.string.types_row_children_target, childCount, type.targetMinutes)
    } else {
        stringResource(R.string.types_row_target, type.targetMinutes)
    }

    // 选择模式下登记行位置（拖拽命中检测用），移出组合时清理，避免残留旧坐标
    DisposableEffect(type.id, dragController) {
        onDispose { dragController?.rowBounds?.remove(type.id) }
    }
    val dragEnabled = selectionMode && selected && dragController != null
    val gestureModifier = if (dragController != null) {
        Modifier
            .onGloballyPositioned { dragController.rowBounds[type.id] = it.boundsInRoot() }
            .then(
                if (dragEnabled) {
                    Modifier.pointerInput(type.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> dragController.start(type.id, offset) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragController.drag(dragAmount)
                            },
                            onDragEnd = { dragController.end() },
                            onDragCancel = { dragController.cancel() },
                        )
                    }
                } else {
                    Modifier
                },
            )
    } else {
        Modifier
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .then(gestureModifier)
            .then(
                if (isDropTarget) {
                    Modifier.border(2.dp, primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(
            start = if (selectionMode) 6.dp else 12.dp,
            end = 8.dp,
            top = 9.dp,
            bottom = 9.dp,
        ),
        onClick = if (selectionMode) onToggleSelect else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                )
            }
            // 左侧色条（数据色）
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(eventColor),
            )
            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = type.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (selectionMode) {
                    // 选择模式下仅展示目标信息，不响应点击（点击行=勾选）
                    Text(
                        text = targetText,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // 副标题：目标分钟（父节点保留「N 个子分类」），点击可编辑目标
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                onClickLabel = stringResource(R.string.types_target_edit),
                                onClick = onEditTarget,
                            )
                            .padding(start = 1.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Flag,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = primary.copy(alpha = 0.85f),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = targetText,
                            style = MaterialTheme.typography.labelSmall,
                            color = primary.copy(alpha = 0.90f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!selectionMode) {
                TypeActionButton(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.types_add_child),
                    onClick = onAddChild,
                    enabled = canAddChild,
                    tint = primary,
                )
                TypeActionButton(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.types_rename),
                    onClick = onRename,
                    tint = muted,
                )
                TypeActionButton(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = stringResource(R.string.types_recolor),
                    onClick = onRecolor,
                    tint = muted,
                )
                TypeActionButton(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.types_delete),
                    onClick = onDelete,
                    tint = errorColor.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/** 低饱和玻璃圆钮（行内操作） */
@Composable
private fun TypeActionButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .size(32.dp)
            .clip(CircleShape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) tint else tint.copy(alpha = 0.30f),
        )
    }
}

/** 空状态：玻璃背景上居中提示（文字用 onSurfaceVariant） */
@Composable
private fun EmptyTypesHint(modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Category,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = muted.copy(alpha = 0.55f),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.types_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 对话框（逻辑不变，仅圆角 / 深色容器适配）
// ---------------------------------------------------------------------------

/** 新建（一级 / 子级）对话框：名称 + 每日目标（默认 60 分钟）+ 可自定义颜色。 */
@Composable
private fun CreateTypeDialog(
    parent: ActivityTypeEntity?,
    defaultColor: Int,
    onDismiss: () -> Unit,
    onCreate: (name: String, customColor: Int?, targetMinutes: Int) -> Unit,
) {
    val parentId = parent?.id
    var name by remember(parentId) { mutableStateOf("") }
    var targetText by remember(parentId) { mutableStateOf(DEFAULT_TARGET_MINUTES.toString()) }
    var pickedColor by remember(parentId) { mutableStateOf<Int?>(null) }
    var showColorPicker by remember(parentId) { mutableStateOf(false) }
    val displayColor = pickedColor ?: defaultColor
    val targetMinutes = targetText.toIntOrNull()
        ?.takeIf { it in TARGET_MINUTES_MIN..TARGET_MINUTES_MAX }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                stringResource(
                    if (parent == null) R.string.types_new_root else R.string.types_new_child,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (parent != null) {
                    Text(
                        text = stringResource(
                            R.string.types_parent_label,
                            parent.path.replace("/", " / "),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.types_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { raw ->
                        // 只接受数字，最多 4 位（合法范围 1..1440）
                        if (raw.length <= 4 && raw.all { it.isDigit() }) targetText = raw
                    },
                    label = { Text(stringResource(R.string.types_target_label)) },
                    singleLine = true,
                    isError = targetMinutes == null,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (targetMinutes == null) {
                    Text(
                        text = stringResource(R.string.types_target_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(displayColor)),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = ColorPalette.toHex(displayColor),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showColorPicker = true }) {
                        Text(stringResource(R.string.types_pick_color))
                    }
                }
                Text(
                    text = stringResource(R.string.types_name_required_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        name.trim(),
                        pickedColor,
                        targetMinutes ?: DEFAULT_TARGET_MINUTES,
                    )
                },
                enabled = name.isNotBlank() && targetMinutes != null,
            ) {
                Text(stringResource(R.string.types_action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = displayColor,
            onDismiss = { showColorPicker = false },
            onConfirm = { picked ->
                pickedColor = picked
                showColorPicker = false
            },
        )
    }
}

@Composable
private fun RenameTypeDialog(
    node: ActivityTypeEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(node.id) { mutableStateOf(node.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.types_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.types_name_label)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 每日目标（分钟）编辑对话框：保存时调 [MainViewModel.updateTypeTarget]。 */
@Composable
private fun TargetMinutesDialog(
    node: ActivityTypeEntity,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember(node.id) { mutableStateOf(node.targetMinutes.toString()) }
    val minutes = text.toIntOrNull()
        ?.takeIf { it in TARGET_MINUTES_MIN..TARGET_MINUTES_MAX }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.types_target_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = node.path.replace("/", " / "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        if (raw.length <= 4 && raw.all { it.isDigit() }) text = raw
                    },
                    label = { Text(stringResource(R.string.types_target_label)) },
                    singleLine = true,
                    isError = minutes == null,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (minutes == null) {
                    Text(
                        text = stringResource(R.string.types_target_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { minutes?.let { onConfirm(it) } },
                enabled = minutes != null,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 三选项对话框：主选项 / 次选项 / 取消（改色级联、删除方式共用）。 */
@Composable
private fun ThreeOptionDialog(
    title: String,
    message: String,
    primaryText: String,
    secondaryText: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.size(4.dp))
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(primaryText)
                }
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(secondaryText)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    node: ActivityTypeEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.types_delete_title)) },
        text = { Text(stringResource(R.string.types_delete_leaf_message, node.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 按 parentId 深度优先展开（先 sortOrder 再 id），父节点缺失的异常数据兜底追加。 */
private fun flattenVisibleTypes(visible: List<ActivityTypeEntity>): List<ActivityTypeEntity> {
    val childrenByParent = visible.groupBy { it.parentId }
    val ordered = ArrayList<ActivityTypeEntity>(visible.size)
    val visited = HashSet<Long>()

    fun walk(parentId: Long?) {
        childrenByParent[parentId].orEmpty()
            .sortedWith(compareBy<ActivityTypeEntity> { it.sortOrder }.thenBy { it.id })
            .forEach { node ->
                if (visited.add(node.id)) {
                    ordered += node
                    walk(node.id)
                }
            }
    }

    walk(null)
    if (ordered.size < visible.size) {
        visible.filter { it.id !in visited }
            .sortedWith(
                compareBy<ActivityTypeEntity> { it.depth }
                    .thenBy { it.sortOrder }
                    .thenBy { it.id },
            )
            .forEach { ordered += it }
    }
    return ordered
}

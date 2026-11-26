package com.togotiming.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.togotiming.app.R
import com.togotiming.app.data.ActivityTypeEntity

/**
 * 分类选择器（树形）：
 * - 展示未归档分类的完整层级（缩进 + 路径）；
 * - 只有叶子（没有未归档子节点）可选，父节点置灰；
 * - 返回被选中的 [ActivityTypeEntity]。
 *
 * 供 Home / 补记 / NFC 等页面复用。
 */
@Composable
fun TypePickerDialog(
    types: List<ActivityTypeEntity>,
    title: String,
    onDismiss: () -> Unit,
    onPick: (ActivityTypeEntity) -> Unit,
) {
    val visible = remember(types) { types.filter { !it.archived } }
    val ordered = remember(visible) { flattenPickerTree(visible) }
    val childCounts = remember(visible) { visible.groupingBy { it.parentId }.eachCount() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.type_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (ordered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.type_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ordered.forEachIndexed { index, type ->
                            val isLeaf = (childCounts[type.id] ?: 0) == 0
                            TypePickerRow(
                                type = type,
                                selectable = isLeaf,
                                onClick = { onPick(type) },
                            )
                            if (index != ordered.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = (type.depth.coerceIn(0, 2) * 16).dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun TypePickerRow(
    type: ActivityTypeEntity,
    selectable: Boolean,
    onClick: () -> Unit,
) {
    val contentAlpha = if (selectable) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable, onClick = onClick)
            .padding(
                start = (type.depth.coerceIn(0, 2) * 16).dp,
                top = 10.dp,
                bottom = 10.dp,
                end = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(type.colorArgb).copy(alpha = contentAlpha)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = type.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            Text(
                text = if (type.depth <= 0) {
                    stringResource(R.string.type_picker_root)
                } else {
                    stringResource(R.string.type_picker_path, type.path.replace("/", " / "))
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
        if (!selectable) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.type_picker_has_children),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
    }
}

/** 按 parentId 深度优先展开（先 sortOrder 再 id），父节点缺失的异常数据兜底追加。 */
private fun flattenPickerTree(visible: List<ActivityTypeEntity>): List<ActivityTypeEntity> {
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

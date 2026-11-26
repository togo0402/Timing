package com.togotiming.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.domain.TimeMath

/** 新建 / 编辑时间块（v1.2 起由横向 Gantt 的点击色条触发，签名保持不变）。 */
@Composable
fun RecordEditDialog(
    record: TimeRecordEntity?,
    defaultStart: Long,
    defaultTypeId: Long?,
    types: List<ActivityTypeEntity>,
    onDismiss: () -> Unit,
    onSave: (typeId: Long, startAt: Long, endAt: Long?, comment: String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val leaves = remember(types) {
        val parentIds = types.filter { !it.archived }.mapNotNull { it.parentId }.toSet()
        types.filter { !it.archived && it.id !in parentIds }
    }
    val initialTypeId = record?.typeId ?: defaultTypeId ?: leaves.firstOrNull()?.id
    var typeId by remember { mutableLongStateOf(initialTypeId ?: 0L) }
    var typeMenu by remember { mutableStateOf(false) }
    var startText by remember {
        mutableStateOf(TimeMath.formatClock(record?.startAt ?: defaultStart))
    }
    var endText by remember {
        mutableStateOf(
            when {
                record?.endAt != null -> TimeMath.formatClock(record.endAt)
                record != null -> "进行中"
                else -> TimeMath.formatClock(defaultStart + 30 * TimeMath.MINUTE)
            }
        )
    }
    var comment by remember { mutableStateOf(record?.comment ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val isActive = record != null && record.endAt == null
    val baseDay = TimeMath.dayStart(record?.startAt ?: defaultStart)
    val selectedType = leaves.firstOrNull { it.id == typeId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (record == null) "补记时间" else "编辑时间块") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedType?.name ?: "选择活动类型")
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        leaves.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(type.colorArgb)),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(type.name)
                                    }
                                },
                                onClick = {
                                    typeId = type.id
                                    typeMenu = false
                                },
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("开始 HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("结束 HH:mm") },
                        singleLine = true,
                        enabled = !isActive,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                if (isActive) {
                    Text(
                        "进行中：可修改开始时间；结束请点「停止」",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (typeId == 0L) {
                    error = "请选择活动类型"
                    return@TextButton
                }
                val start = parseClock(startText, baseDay)
                if (start == null) {
                    error = "开始时间格式应为 HH:mm"
                    return@TextButton
                }
                var end: Long? = null
                if (!isActive) {
                    var parsed = parseClock(endText, baseDay)
                    if (parsed == null) {
                        error = "结束时间格式应为 HH:mm"
                        return@TextButton
                    }
                    if (parsed <= start) parsed += TimeMath.DAY
                    if (parsed <= start) {
                        error = "结束时间必须晚于开始时间"
                        return@TextButton
                    }
                    end = parsed
                }
                onSave(typeId, start, end, comment.ifBlank { null })
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun parseClock(text: String, baseDay: Long): Long? {
    val parts = text.trim().split(":", "：")
    if (parts.isEmpty()) return null
    val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    if (h !in 0..23 || m !in 0..59) return null
    return baseDay + h * 3_600_000L + m * 60_000L
}

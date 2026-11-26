package com.togotiming.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.togotiming.app.R
import com.togotiming.app.domain.ColorPalette
import kotlin.math.roundToInt

/**
 * 三联动颜色选择器（无 alpha）：
 * HSL 三个滑杆 ↔ 当前颜色预览块 ↔ HEX 输入框（#RRGGBB / RRGGBB，大小写不限）。
 *
 * - 滑杆改动：预览与 HEX 同步；
 * - HEX 合法：预览与滑杆同步；
 * - HEX 非法：提示「请输入 #RRGGBB」，颜色保持上次有效值，失焦/确认时回滚到上次有效值。
 */
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsl = remember(initialColor) { ColorPalette.rgbToHsl(initialColor) }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsl[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(initialHsl[1]) }
    var lightness by remember(initialColor) { mutableFloatStateOf(initialHsl[2]) }
    var color by remember(initialColor) { mutableIntStateOf(initialColor) }
    var hexText by remember(initialColor) { mutableStateOf(ColorPalette.toHex(initialColor)) }
    var hexError by remember(initialColor) { mutableStateOf(false) }

    fun applyHsl(h: Float, s: Float, l: Float) {
        hue = h
        saturation = s
        lightness = l
        val next = ColorPalette.hslToColor(h, s, l)
        color = next
        hexText = ColorPalette.toHex(next)
        hexError = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 当前颜色预览（只读）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ColorPalette.toHex(color),
                        color = Color(ColorPalette.contrastText(color)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                ColorSliderRow(
                    label = stringResource(R.string.color_picker_hue),
                    valueText = "${hue.roundToInt()}°",
                    value = hue,
                    valueRange = 0f..360f,
                    onValueChange = { applyHsl(it, saturation, lightness) },
                )
                ColorSliderRow(
                    label = stringResource(R.string.color_picker_saturation),
                    valueText = "${(saturation * 100f).roundToInt()}%",
                    value = saturation,
                    valueRange = 0f..1f,
                    onValueChange = { applyHsl(hue, it, lightness) },
                )
                ColorSliderRow(
                    label = stringResource(R.string.color_picker_lightness),
                    valueText = "${(lightness * 100f).roundToInt()}%",
                    value = lightness,
                    valueRange = 0f..1f,
                    onValueChange = { applyHsl(hue, saturation, it) },
                )

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        hexText = raw
                        val parsed = ColorPalette.parseHex(raw)
                        if (parsed == null) {
                            hexError = true
                        } else {
                            val hsl = ColorPalette.rgbToHsl(parsed)
                            hue = hsl[0]
                            saturation = hsl[1]
                            lightness = hsl[2]
                            color = parsed
                            hexError = false
                        }
                    },
                    label = { Text(stringResource(R.string.color_picker_hex_label)) },
                    singleLine = true,
                    isError = hexError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            // 非法输入在失焦时回滚到上次有效值
                            if (!focusState.isFocused && hexError) {
                                hexText = ColorPalette.toHex(color)
                                hexError = false
                            }
                        },
                )
                if (hexError) {
                    Text(
                        text = stringResource(R.string.color_picker_hex_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }) {
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

@Composable
private fun ColorSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

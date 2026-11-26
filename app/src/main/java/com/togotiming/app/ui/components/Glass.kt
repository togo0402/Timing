package com.togotiming.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.togotiming.app.ui.theme.TimingColors

/** 浅色/深色共用的流体玻璃背景：柔和渐层 + 三处光斑 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val brush = Brush.verticalGradient(
        if (dark) listOf(TimingColors.DarkBgTop, TimingColors.DarkBgBottom)
        else listOf(TimingColors.LightBgTop, TimingColors.LightBgBottom)
    )
    Box(modifier.fillMaxSize().background(brush)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (dark) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x3D3B6FD8), Color.Transparent),
                        center = Offset(w * 0.08f, -h * 0.02f),
                        radius = w * 0.9f,
                    ),
                    radius = w * 0.9f,
                    center = Offset(w * 0.08f, -h * 0.02f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x33115C72), Color.Transparent),
                        center = Offset(w * 1.04f, h * 0.14f),
                        radius = w * 0.85f,
                    ),
                    radius = w * 0.85f,
                    center = Offset(w * 1.04f, h * 0.14f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x3D2A1B52), Color.Transparent),
                        center = Offset(w * 0.5f, h * 1.06f),
                        radius = w * 0.95f,
                    ),
                    radius = w * 0.95f,
                    center = Offset(w * 0.5f, h * 1.06f),
                )
            } else {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x66CFE2FF), Color.Transparent),
                        center = Offset(w * 0.08f, -h * 0.02f),
                        radius = w * 0.9f,
                    ),
                    radius = w * 0.9f,
                    center = Offset(w * 0.08f, -h * 0.02f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x59D8F3FF), Color.Transparent),
                        center = Offset(w * 1.04f, h * 0.12f),
                        radius = w * 0.85f,
                    ),
                    radius = w * 0.85f,
                    center = Offset(w * 1.04f, h * 0.12f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x4DE9E3FF), Color.Transparent),
                        center = Offset(w * 0.5f, h * 1.06f),
                        radius = w * 0.95f,
                    ),
                    radius = w * 0.95f,
                    center = Offset(w * 0.5f, h * 1.06f),
                )
            }
        }
        content()
    }
}

/** Fluid Glass 卡片：半透明渐变 + 高光描边 + 柔和彩色阴影 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shadowColor = if (dark) Color(0x78000000) else Color(0x33315AA6)
    val gradient = if (dark) {
        Brush.linearGradient(listOf(TimingColors.DarkGlass, TimingColors.DarkGlassSoft))
    } else {
        Brush.linearGradient(listOf(TimingColors.LightGlass, TimingColors.LightGlassSoft))
    }
    Column(
        modifier = modifier
            .shadow(18.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(gradient)
            .border(1.dp, if (dark) TimingColors.DarkBorder else TimingColors.LightBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    container: Color = color.copy(alpha = 0.13f),
    fontSize: Int = 11,
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun RingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = 0.14f),
    strokeWidth: Dp = 8.dp,
) {
    Canvas(modifier) {
        val sw = strokeWidth.toPx()
        val diameter = minOf(size.width, size.height) - sw
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (trailing != null) {
            Box(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 胶囊分段控件（主题模式 / 统计周期） */
@Composable
fun GlassSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (dark) Color(0x14FFFFFF) else Color(0x8AFFFFFF))
            .border(
                1.dp,
                if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                RoundedCornerShape(50),
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) {
                            if (dark) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else Color.White
                        } else {
                            Color.Transparent
                        }
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
fun RowScope.GlassRowContent(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

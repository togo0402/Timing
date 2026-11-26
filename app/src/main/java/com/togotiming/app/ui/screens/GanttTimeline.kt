package com.togotiming.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.togotiming.app.R
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.domain.TimeMath
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.theme.TimingColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 行高 / 条高（设计稿：行约 42dp、条 26dp，行高压缩到 38dp 以多显示几条） */
private val ROW_HEIGHT = 38.dp
private val BAR_HEIGHT = 26.dp
private val BAR_SHAPE = RoundedCornerShape(8.dp)
private val MIN_BAR_WIDTH = 6.dp
private val RULER_HEIGHT = 26.dp
private val LEGEND_HEIGHT = 16.dp

/** 双指缩放：最小 30 分钟窗口，最大一整天 */
private const val MIN_WINDOW_MS = 30L * 60L * 1000L
private val SNAP_MS = TimeMath.FIVE_MINUTES

private data class GanttRow(
    val record: TimeRecordEntity,
    val start: Long,
    val end: Long,
    val type: ActivityTypeEntity,
)

private data class Tick(val time: Long, val label: String)

/**
 * v1.2 横向 Gantt 时间轴（只读查看 + 点击编辑 / 空白补记）。
 *
 * 交互：
 * - 双指缩放可视窗口 [viewStartMs, viewEndMs]（默认全天 0–24h，最小 30 分钟），以双指中心为锚点；
 * - 单指横拖平移窗口；单指竖拖滚动行列表（轴向锁定，优先保证缩放/平移）；
 * - 点击色条 → 编辑该记录；点击空白 → 按 x 坐标换算时间补记（5 分钟吸附）；
 * - 纵向行列表可滚动；顶部刻度随窗口自适应（整日 6h 一格，6h 窗口 1h，30min 窗口 5min）。
 *
 * 记录按当前日期裁剪；start >= end 的异常数据跳过。
 */
@Composable
fun GanttTimeline(
    dayStart: Long,
    records: List<TimeRecordEntity>,
    types: List<ActivityTypeEntity>,
    now: Long,
    onEdit: (TimeRecordEntity) -> Unit,
    onBlankTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayEnd = TimeMath.nextDayStart(dayStart)
    val dayMs = (dayEnd - dayStart).coerceAtLeast(1L)
    val isToday = TimeMath.dayStart(now) == dayStart
    val typeMap = remember(types) { types.associateBy { it.id } }

    val rows = remember(records, types, dayStart, now) {
        records.mapNotNull { r ->
            val type = typeMap[r.typeId] ?: return@mapNotNull null
            val clippedStart = max(r.startAt, dayStart)
            val realEnd = r.endAt ?: now
            val clippedEnd = min(realEnd, dayEnd)
            // 异常数据（开始 >= 结束）跳过
            if (clippedEnd <= clippedStart) return@mapNotNull null
            GanttRow(record = r, start = clippedStart, end = clippedEnd, type = type)
        }.sortedBy { it.start }
    }

    // 可视时间窗口（毫秒）与纵向滚动（像素）
    var viewStart by remember(dayStart) { mutableLongStateOf(dayStart) }
    var viewEnd by remember(dayStart) { mutableLongStateOf(dayEnd) }
    var rowScroll by remember(dayStart) { mutableFloatStateOf(0f) }

    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val dividerColor = if (dark) TimingColors.DarkDivider else TimingColors.LightDivider
    val rulerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val nowColor = MaterialTheme.colorScheme.error
    val glassBorder = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部刻度：00/06/12/18/24（整日）或缩放后的 HH:mm，并标注当前时间
            GanttRuler(
                dayStart = dayStart,
                viewStart = viewStart,
                viewEnd = viewEnd,
                now = now,
                isToday = isToday,
                labelColor = rulerColor,
                lineColor = dividerColor,
                nowColor = nowColor,
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val widthDp = maxWidth.value
                val viewportHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
                val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
                val contentHeightPx = rows.size * rowHeightPx
                val maxScrollPx = (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)
                val effectiveScroll = rowScroll.coerceIn(0f, maxScrollPx)
                val span = (viewEnd - viewStart).coerceAtLeast(1L)
                val ticks = remember(viewStart, viewEnd, dayStart) {
                    computeTicks(dayStart, viewStart, viewEnd)
                }
                val showBarDetails = span <= 6L * 60L * 60L * 1000L

                // 状态快照：手势/点击闭包读取最新值，避免 pointerInput 频繁重启
                val rowsState = rememberUpdatedState(rows)
                val windowState = rememberUpdatedState(viewStart to viewEnd)
                val scrollState = rememberUpdatedState(effectiveScroll)
                val maxScrollState = rememberUpdatedState(maxScrollPx)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                ) {
                    // 1) 竖向网格线
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        for (tick in ticks) {
                            val fraction =
                                (tick.time - viewStart).toFloat() / span.toFloat()
                            if (fraction < 0f || fraction > 1f) continue
                            val x = fraction * w
                            drawLine(
                                color = dividerColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }

                    // 2) 行列表（随纵向滚动平移）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = -effectiveScroll },
                    ) {
                        rows.forEach { row ->
                            key(row.record.id) {
                                GanttRowView(
                                    row = row,
                                    viewStart = viewStart,
                                    viewEnd = viewEnd,
                                    widthDp = widthDp,
                                    showDetails = showBarDetails,
                                    dark = dark,
                                    glassBorder = glassBorder,
                                    dividerColor = dividerColor,
                                )
                            }
                        }
                    }

                    // 3) 当前时间红线 + 圆点
                    if (isToday && now in viewStart until viewEnd) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val fraction =
                                (now - viewStart).toFloat() / span.toFloat()
                            val x = fraction * size.width
                            drawLine(
                                color = nowColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                            drawCircle(
                                color = nowColor,
                                radius = 4.dp.toPx(),
                                center = Offset(x, 4.dp.toPx()),
                            )
                        }
                    }

                    if (rows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_gantt_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp),
                        )
                    }

                    // 4) 手势层：点击编辑/补记 + 双指缩放 + 单指平移/纵向滚动
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(dayStart) {
                                detectTapGestures { offset ->
                                    val (vs, ve) = windowState.value
                                    val currentSpan = (ve - vs).coerceAtLeast(1L)
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val rowH = rowHeightPx.coerceAtLeast(1f)
                                    val rowIndex =
                                        ((offset.y + scrollState.value) / rowH).toInt()
                                    val row = rowsState.value.getOrNull(rowIndex)
                                    if (row != null) {
                                        val geometry = barGeometry(
                                            start = row.start,
                                            end = row.end,
                                            viewStart = vs,
                                            viewEnd = ve,
                                            widthPx = w,
                                            minBarPx = MIN_BAR_WIDTH.toPx(),
                                        )
                                        if (geometry != null &&
                                            offset.x >= geometry.first - 4.dp.toPx() &&
                                            offset.x <= geometry.first + geometry.second + 4.dp.toPx()
                                        ) {
                                            onEdit(row.record)
                                            return@detectTapGestures
                                        }
                                    }
                                    val fraction = (offset.x / w).coerceIn(0f, 1f)
                                    val t = vs + (fraction * currentSpan).toLong()
                                    onBlankTap(
                                        TimeMath.snap(t, SNAP_MS)
                                            .coerceIn(dayStart, dayEnd - SNAP_MS)
                                    )
                                }
                            }
                            .pointerInput(dayStart) {
                                // 双指缩放 + 横向平移 + 单指纵向滚动（自定义 transform，解决与纵向滚动的冲突）
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val viewportWidthPx = size.width.toFloat().coerceAtLeast(1f)
                                    var mode = 0 // 0=未定 1=横向平移 2=纵向滚动 3=双指变换
                                    var accum = Offset.Zero
                                    var prevPointers: List<Pair<PointerId, Offset>> = emptyList()

                                    fun applyWindowPan(panPx: Float) {
                                        val curSpan = (viewEnd - viewStart).coerceAtLeast(1L)
                                        val deltaMs = (-panPx / viewportWidthPx * curSpan).toLong()
                                        val newStart = (viewStart + deltaMs)
                                            .coerceIn(dayStart, (dayEnd - curSpan).coerceAtLeast(dayStart))
                                        viewStart = newStart
                                        viewEnd = newStart + curSpan
                                    }

                                    fun applyZoom(zoom: Float, centroidX: Float) {
                                        if (zoom <= 0f || !zoom.isFinite() || zoom == 1f) return
                                        val curSpan = (viewEnd - viewStart).coerceAtLeast(1L)
                                        val fraction = (centroidX / viewportWidthPx).coerceIn(0f, 1f)
                                        val newSpan = (curSpan / zoom).toLong()
                                            .coerceIn(MIN_WINDOW_MS, dayMs)
                                        val anchorTime = viewStart + (fraction * curSpan).toLong()
                                        val newStart = (anchorTime - (fraction * newSpan).toLong())
                                            .coerceIn(dayStart, (dayEnd - newSpan).coerceAtLeast(dayStart))
                                        viewStart = newStart
                                        viewEnd = newStart + newSpan
                                    }

                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                        if (pressed.size >= 2) {
                                            mode = 3
                                            val current =
                                                pressed.map { it.id to it.position }
                                            if (prevPointers.size >= 2) {
                                                val prevMap = prevPointers.toMap()
                                                val common = current.filter { prevMap.containsKey(it.first) }
                                                if (common.size >= 2) {
                                                    val (idA, curA) = common[0]
                                                    val (idB, curB) = common[1]
                                                    val prevA = prevMap[idA]
                                                    val prevB = prevMap[idB]
                                                    if (prevA != null && prevB != null) {
                                                        val prevDistance =
                                                            (prevA - prevB).getDistance()
                                                        val curDistance =
                                                            (curA - curB).getDistance()
                                                        if (prevDistance > 1f && curDistance > 1f) {
                                                            applyZoom(
                                                                zoom = curDistance / prevDistance,
                                                                centroidX = (curA.x + curB.x) / 2f,
                                                            )
                                                        }
                                                        val prevCentroidX = (prevA.x + prevB.x) / 2f
                                                        val curCentroidX = (curA.x + curB.x) / 2f
                                                        applyWindowPan(curCentroidX - prevCentroidX)
                                                    }
                                                }
                                            }
                                            prevPointers = current
                                            event.changes.forEach {
                                                if (it.position != it.previousPosition) it.consume()
                                            }
                                        } else {
                                            val change = pressed[0]
                                            val delta = change.positionChange()
                                            prevPointers = emptyList()
                                            when (mode) {
                                                3 -> {
                                                    if (delta.x != 0f) {
                                                        applyWindowPan(delta.x)
                                                        change.consume()
                                                    }
                                                }

                                                0 -> {
                                                    accum += delta
                                                    if (accum.getDistance() > viewConfiguration.touchSlop) {
                                                        mode = if (abs(accum.x) >= abs(accum.y)) 1 else 2
                                                        if (mode == 1) {
                                                            applyWindowPan(accum.x)
                                                        } else {
                                                            rowScroll = (rowScroll + accum.y)
                                                                .coerceIn(0f, maxScrollState.value)
                                                        }
                                                        change.consume()
                                                    }
                                                }

                                                1 -> {
                                                    if (delta.x != 0f) {
                                                        applyWindowPan(delta.x)
                                                        change.consume()
                                                    }
                                                }

                                                2 -> {
                                                    if (delta.y != 0f) {
                                                        rowScroll = (rowScroll + delta.y)
                                                            .coerceIn(0f, maxScrollState.value)
                                                        change.consume()
                                                    }
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    )
                }
            }

            Text(
                text = stringResource(R.string.home_gantt_legend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LEGEND_HEIGHT)
                    .padding(top = 3.dp),
            )
        }
    }
}

/**
 * 顶部时间刻度：整日时显示 00/06/12/18/24，缩放后显示 HH:mm；
 * 同时绘制当前时间标签（红色）。
 */
@Composable
private fun GanttRuler(
    dayStart: Long,
    viewStart: Long,
    viewEnd: Long,
    now: Long,
    isToday: Boolean,
    labelColor: Color,
    lineColor: Color,
    nowColor: Color,
) {
    val ticks = remember(viewStart, viewEnd, dayStart) {
        computeTicks(dayStart, viewStart, viewEnd)
    }
    val span = (viewEnd - viewStart).coerceAtLeast(1L)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT),
    ) {
        val w = size.width
        val h = size.height
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = 9.5.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        for (tick in ticks) {
            val fraction = (tick.time - viewStart).toFloat() / span.toFloat()
            if (fraction < 0f || fraction > 1f) continue
            val x = fraction * w
            val half = labelPaint.measureText(tick.label) / 2f + 1.dp.toPx()
            val labelX = x.coerceIn(half, (w - half).coerceAtLeast(half))
            drawContext.canvas.nativeCanvas.drawText(
                tick.label,
                labelX,
                h - 5.dp.toPx(),
                labelPaint,
            )
            // 刻度短线
            drawLine(
                color = lineColor,
                start = Offset(x, h - 2.dp.toPx()),
                end = Offset(x, h),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (isToday && now in viewStart until viewEnd) {
            val fraction = (now - viewStart).toFloat() / span.toFloat()
            val x = fraction * w
            val tag = TimeMath.formatClock(now)
            val tagPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = nowColor.toArgb()
                textSize = 9.5.sp.toPx()
                isFakeBoldText = true
            }
            val tagHalf = tagPaint.measureText(tag) / 2f
            val tagX = (x - tagHalf).coerceIn(
                2.dp.toPx(),
                (w - tagPaint.measureText(tag) - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()),
            )
            drawContext.canvas.nativeCanvas.drawText(tag, tagX, 11.dp.toPx(), tagPaint)
            drawLine(
                color = nowColor,
                start = Offset(x, 12.dp.toPx()),
                end = Offset(x, h),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/** 一行：淡虚线分隔 + 色条（或右侧小标签）。点击由外层手势层统一处理。 */
@Composable
private fun GanttRowView(
    row: GanttRow,
    viewStart: Long,
    viewEnd: Long,
    widthDp: Float,
    showDetails: Boolean,
    dark: Boolean,
    glassBorder: Color,
    dividerColor: Color,
) {
    val span = (viewEnd - viewStart).coerceAtLeast(1L)
    val intersects = row.end > viewStart && row.start < viewEnd
    val typeColor = Color(row.type.colorArgb)
    val textOnBar =
        if (typeColor.luminance() > 0.55f) Color(0xFF1F2430) else Color.White
    val durationText = formatShortDuration(row.end - row.start)
    val clockText = TimeMath.formatClock(row.start) + "–" + TimeMath.formatClock(row.end)
    val ongoing = row.record.endAt == null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT),
    ) {
        // 行分隔淡虚线
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.BottomCenter),
        ) {
            drawLine(
                color = dividerColor,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 5.dp.toPx()),
                    0f,
                ),
            )
        }

        if (!intersects) return@Box

        val geometry = barGeometry(
            start = row.start,
            end = row.end,
            viewStart = viewStart,
            viewEnd = viewEnd,
            widthPx = widthDp,
            minBarPx = MIN_BAR_WIDTH.value,
        ) ?: return@Box
        val barLeft = geometry.first
        val barWidth = geometry.second

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = barLeft.dp)
                .width(barWidth.dp)
                .height(BAR_HEIGHT)
                .shadow(
                    elevation = 5.dp,
                    shape = BAR_SHAPE,
                    ambientColor = typeColor.copy(alpha = 0.35f),
                    spotColor = typeColor.copy(alpha = 0.35f),
                )
                .clip(BAR_SHAPE)
                .background(typeColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (barWidth >= 64f) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.type.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = textOnBar,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ongoing) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.home_live),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = textOnBar.copy(alpha = 0.9f),
                            maxLines = 1,
                        )
                    }
                    if (showDetails && barWidth >= 120f) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "$clockText · $durationText",
                            fontSize = 9.sp,
                            color = textOnBar.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }

        // 短事件：名称 + 时长放到条右侧的小标签里；空间不足则省略，避免重叠
        if (barWidth < 64f) {
            val labelLeft = barLeft + barWidth + 4f
            val available = widthDp - labelLeft - 4f
            if (available >= 52f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = labelLeft.dp)
                        .widthIn(max = available.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
                        .border(1.dp, glassBorder, RoundedCornerShape(7.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (ongoing) {
                            "${row.type.name} $durationText ${stringResource(R.string.home_live)}"
                        } else {
                            "${row.type.name} $durationText"
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 色条几何：返回 (leftPx, widthPx)；完全不在窗口内返回 null。
 * 最小宽度从右端向左补足，保证短事件也有可点区域且不出界。
 */
private fun barGeometry(
    start: Long,
    end: Long,
    viewStart: Long,
    viewEnd: Long,
    widthPx: Float,
    minBarPx: Float,
): Pair<Float, Float>? {
    if (end <= viewStart || start >= viewEnd) return null
    val span = (viewEnd - viewStart).coerceAtLeast(1L)
    val startFraction = ((start - viewStart).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    val endFraction = ((end - viewStart).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    val leftRaw = startFraction * widthPx
    val rightRaw = endFraction * widthPx
    val width = (rightRaw - leftRaw).coerceAtLeast(minBarPx)
    val left = (rightRaw - width).coerceIn(0f, max(0f, widthPx - width))
    return left to width
}

/** 自适应刻度：全日 6h 一格；6h 窗口 1h；30min 窗口 5min。 */
private fun computeTicks(dayStart: Long, viewStart: Long, viewEnd: Long): List<Tick> {
    val span = (viewEnd - viewStart).coerceAtLeast(1L)
    val stepCandidates = intArrayOf(5, 10, 15, 30, 60, 120, 180, 360)
    val targetMinutes = span / 6L / 60_000L
    val stepMinutes =
        stepCandidates.firstOrNull { it.toLong() >= targetMinutes } ?: 360
    val stepMs = stepMinutes * 60_000L
    val first = dayStart +
        (Math.ceil((viewStart - dayStart).toDouble() / stepMs) * stepMs).toLong()
    val out = ArrayList<Tick>(8)
    var t = first
    var guard = 0
    while (t <= viewEnd && guard++ < 64) {
        val label = if (stepMinutes >= 360) {
            val hour = ((t - dayStart) / 3_600_000L).toInt().coerceIn(0, 24)
            if (hour == 24) "24" else String.format(Locale.US, "%02d", hour)
        } else {
            TimeMath.formatClock(t)
        }
        out += Tick(t, label)
        t += stepMs
    }
    return out
}

/** 紧凑时长：45m / 1h15 / 2h */
private fun formatShortDuration(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

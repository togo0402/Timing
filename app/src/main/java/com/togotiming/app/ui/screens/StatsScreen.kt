package com.togotiming.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.togotiming.app.R
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.domain.StatsAggregator
import com.togotiming.app.domain.TimeMath
import com.togotiming.app.domain.TypeTotal
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.components.GlassSegmented
import com.togotiming.app.ui.theme.TimingColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 统计页（v1.1 Fluid Glass 风格，背景由 AppNav 的 AppBackground 提供，本页不再铺底色）：
 *
 * - 周期：今日 / 本周（周一起始）/ 本月，用 [GlassSegmented] 切换，区间全部用 [TimeMath] 计算；
 * - 主视图以根节点为单位：父节点时长 = 自身 + 所有子孙（[StatsAggregator.byRoot]）；
 * - 点击根节点在同一列表内缩进展开子节点，再点叶子弹出记录明细（起止时间 + 时长，倒序）；
 * - 进行中记录按 now 统计，页面停留期间每 30 秒自动刷新；
 * - 环形占比图用 Canvas.drawArc 绘制，最多 8 段，其余归入灰色「其它」；
 * - 所有占比统一以「周期总时长」（to - from）为分母；环形图剩余灰段与列表底部
 *   不可点击的「未统计」灰行表示周期内没有记录的时间
 *   （未统计 = 周期总时长 - 已统计，重叠记录等极端情况下最小为 0）。
 */
@Composable
fun StatsScreen(vm: MainViewModel) {
    val context = LocalContext.current

    var period by remember { mutableStateOf(StatsPeriod.DAY) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // tick 只用于驱动「每 30 秒重新查询」，refreshKey 用于手动刷新 / 失败重试
    var tick by remember { mutableStateOf(0) }
    var refreshKey by remember { mutableStateOf(0) }
    var snapshot by remember { mutableStateOf<StatsSnapshot?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var detailNodeId by remember { mutableStateOf<Long?>(null) }
    // 选定日期锚点（本地日 00:00）：今日 / 本周 / 本月都基于它计算区间，支持查看其它天 / 周 / 月
    var anchor by remember { mutableStateOf(TimeMath.dayStart(System.currentTimeMillis())) }
    var showDatePicker by remember { mutableStateOf(false) }

    // 页面停留期间每 30 秒刷新一次：进行中记录的时长按 now 计算。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
            tick += 1
        }
    }

    // 切换周期 / 切换日期 / 自动刷新 / 手动刷新 / 失败重试都会重新查询。
    LaunchedEffect(period, anchor, tick, refreshKey) {
        val current = System.currentTimeMillis()
        now = current
        // 区间由「选定日期锚点」决定，now 只用于进行中记录与 30 秒刷新
        val range = statsRange(period, anchor)
        // 周期变化或跨天（区间起点变化）时先清掉旧数据，避免短暂展示错误区间。
        val stale = snapshot == null || snapshot?.period != period || snapshot?.from != range.first
        if (stale) {
            snapshot = null
            errorMessage = null
        }
        try {
            val records = vm.recordsIn(range.first, range.second)
            val types = vm.allTypesIncludingArchived()
            snapshot = StatsSnapshot(
                period = period,
                from = range.first,
                to = range.second,
                records = records,
                types = types,
            )
            errorMessage = null
        } catch (e: CancellationException) {
            // LaunchedEffect 被取消（切换周期 / 页面离开）时不要误报查询失败
            throw e
        } catch (e: Exception) {
            errorMessage = e.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.stats_error_unknown)
        }
    }

    // 切换周期 / 选择日期后收起展开行与明细弹窗，避免旧区间状态残留
    LaunchedEffect(period, anchor) {
        expandedIds = emptySet()
        detailNodeId = null
    }

    val currentSnapshot = snapshot
    val currentError = errorMessage
    val selectedRange = statsRange(period, anchor)
    val isCurrentPeriod = selectedRange.first == statsRange(period, now).first
    val rangeLabel = statsRangeLabel(period, selectedRange.first, selectedRange.second)

    val periodOptions = listOf(
        stringResource(R.string.stats_tab_today),
        stringResource(R.string.stats_tab_week),
        stringResource(R.string.stats_tab_month),
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：透明标题 + 玻璃圆刷新按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            GlassCircleIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.stats_refresh),
                onClick = { refreshKey += 1 },
                size = 42.dp,
            )
        }

        // 日期导航：‹ 当前日期 / 区间（点击打开日历）›，可查看其它天 / 周 / 月
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.stats_nav_prev),
                onClick = { anchor = shiftAnchor(period, anchor, -1) },
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showDatePicker = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rangeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.DateRange,
                    contentDescription = stringResource(R.string.stats_pick_date),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            GlassCircleIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.stats_nav_next),
                onClick = { anchor = shiftAnchor(period, anchor, 1) },
            )
        }

        // 不在当前周期（今天 / 本周 / 本月）时显示「回到今天」
        if (!isCurrentPeriod) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
                        .clickable { anchor = TimeMath.dayStart(System.currentTimeMillis()) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.stats_back_to_today),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 今日 / 本周 / 本月：玻璃胶囊分段控件
        GlassSegmented(
            options = periodOptions,
            selectedIndex = period.ordinal,
            onSelect = { index ->
                if (index in StatsPeriod.entries.indices) {
                    val target = StatsPeriod.entries[index]
                    if (target != period) {
                        period = target
                        // 立刻清空旧周期数据，让下方显示加载态而不是旧数据
                        snapshot = null
                        errorMessage = null
                        expandedIds = emptySet()
                        detailNodeId = null
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                currentSnapshot == null && currentError != null -> StatsErrorState(
                    message = currentError,
                    onRetry = { refreshKey += 1 },
                )

                currentSnapshot == null -> StatsLoadingState()

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // 已有旧数据时的刷新失败：只提示，不清空页面
                    if (currentError != null) {
                        StatsErrorBanner(
                            message = currentError,
                            onRetry = { refreshKey += 1 },
                        )
                    }
                    StatsContent(
                        modifier = Modifier.weight(1f),
                        snapshot = currentSnapshot,
                        now = now,
                        period = period,
                        expandedIds = expandedIds,
                        onToggleExpanded = { id ->
                            expandedIds = if (id in expandedIds) {
                                expandedIds - id
                            } else {
                                expandedIds + id
                            }
                        },
                        onOpenDetail = { id -> detailNodeId = id },
                    )
                }
            }
        }
    }

    // 叶子 / 直接记录明细弹窗（Fluid Glass 卡片）
    val detail = detailNodeId?.let { id ->
        currentSnapshot?.let { data ->
            val type = data.types.firstOrNull { it.id == id }
            if (type == null) {
                null
            } else {
                val rows = data.records
                    .filter { it.typeId == id }
                    .mapNotNull { record ->
                        val millis = StatsAggregator.overlap(record, data.from, data.to, now)
                        if (millis > 0L) record to millis else null
                    }
                    .sortedByDescending { it.first.startAt }
                StatsDetail(type = type, rows = rows)
            }
        }
    }
    if (detail != null) {
        StatsDetailDialog(
            detail = detail,
            period = period,
            onDismiss = { detailNodeId = null },
        )
    }

    // 自绘玻璃月历：选择任意一天（本周 / 本月按所选日期定位）
    if (showDatePicker) {
        StatsDatePickerDialog(
            initialDate = TimeMath.localDate(anchor),
            onDismiss = { showDatePicker = false },
            onPick = { date ->
                anchor = startOfDayMillis(date)
                showDatePicker = false
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 数据 / 周期
// ---------------------------------------------------------------------------

private data class StatsSnapshot(
    val period: StatsPeriod,
    val from: Long,
    val to: Long,
    val records: List<TimeRecordEntity>,
    val types: List<ActivityTypeEntity>,
)

private data class StatsDetail(
    val type: ActivityTypeEntity,
    /** 记录 + 与查询区间的交集时长 */
    val rows: List<Pair<TimeRecordEntity, Long>>,
)

private enum class StatsPeriod(val emptyRes: Int) {
    DAY(R.string.stats_empty_today),
    WEEK(R.string.stats_empty_week),
    MONTH(R.string.stats_empty_month),
}

/** 以 [anchor] 所在的天 / 周（周一起始）/ 月计算 [from, to) 区间。 */
private fun statsRange(period: StatsPeriod, anchor: Long): Pair<Long, Long> {
    val start = when (period) {
        StatsPeriod.DAY -> TimeMath.dayStart(anchor)
        StatsPeriod.WEEK -> TimeMath.weekStart(anchor)
        StatsPeriod.MONTH -> TimeMath.monthStart(anchor)
    }
    val end = when (period) {
        StatsPeriod.DAY -> TimeMath.nextDayStart(anchor)
        StatsPeriod.WEEK -> TimeMath.localDate(start)
            .plusWeeks(1)
            .atStartOfDay(TimeMath.zone)
            .toInstant()
            .toEpochMilli()

        StatsPeriod.MONTH -> TimeMath.localDate(start)
            .plusMonths(1)
            .atStartOfDay(TimeMath.zone)
            .toInstant()
            .toEpochMilli()
    }
    return start to end
}

/** 按周期单位平移锚点：今日 ±1 天 / 本周 ±1 周 / 本月 ±1 月。 */
private fun shiftAnchor(period: StatsPeriod, anchor: Long, delta: Int): Long {
    val date = TimeMath.localDate(anchor)
    val shifted = when (period) {
        StatsPeriod.DAY -> date.plusDays(delta.toLong())
        StatsPeriod.WEEK -> date.plusWeeks(delta.toLong())
        // 月份先归到 1 号，避免 31 号 +1 月被截断到下月 28/30 号造成漂移
        StatsPeriod.MONTH -> date.withDayOfMonth(1).plusMonths(delta.toLong())
    }
    return startOfDayMillis(shifted)
}

private fun startOfDayMillis(date: LocalDate): Long =
    date.atStartOfDay(TimeMath.zone).toInstant().toEpochMilli()

private val statsWeekdayFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
private val statsMonthDayFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val statsYearMonthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)

private fun statsRangeLabel(period: StatsPeriod, from: Long, to: Long): String = when (period) {
    StatsPeriod.DAY -> TimeMath.localDate(from).format(statsWeekdayFormatter)
    StatsPeriod.WEEK ->
        TimeMath.localDate(from).format(statsMonthDayFormatter) +
            " - " +
            TimeMath.localDate(to - 1L).format(statsMonthDayFormatter)

    StatsPeriod.MONTH -> TimeMath.localDate(from).format(statsYearMonthFormatter)
}

private fun formatPercent(millis: Long, totalMillis: Long): String =
    if (totalMillis <= 0L) {
        "0.0%"
    } else {
        String.format(Locale.US, "%.1f%%", millis.toDouble() * 100.0 / totalMillis.toDouble())
    }

// ---------------------------------------------------------------------------
// 玻璃组件 / 日期选择
// ---------------------------------------------------------------------------

/** 玻璃圆形图标按钮（顶部刷新、日期左右切换、月历翻月共用）。 */
@Composable
private fun GlassCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 38.dp,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** 自绘玻璃月历（周一为每周首日）：选择任意一天，选中后按当前周期定位。 */
@Composable
private fun StatsDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    var shownMonth by remember { mutableStateOf(initialDate.withDayOfMonth(1)) }
    val today = remember { TimeMath.localDate(System.currentTimeMillis()) }
    val weekdayLabels = stringResource(R.string.stats_calendar_weekdays)
        .split(" ")
        .filter { it.isNotBlank() }
    // 周一起始：周一 dayOfWeek.value = 1，前面补的空格数 = value - 1
    val leadingBlanks = shownMonth.dayOfWeek.value - 1
    val daysInMonth = shownMonth.lengthOfMonth()
    val cells: List<LocalDate?> = buildList {
        repeat(leadingBlanks) { add(null) }
        for (day in 1..daysInMonth) add(shownMonth.withDayOfMonth(day))
        while (size % 7 != 0) add(null)
    }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(18.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_pick_date),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.stats_calendar_prev_month),
                    onClick = { shownMonth = shownMonth.minusMonths(1) },
                    size = 32.dp,
                )
                Text(
                    text = statsYearMonthFormatter.format(shownMonth),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                GlassCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.stats_calendar_next_month),
                    onClick = { shownMonth = shownMonth.plusMonths(1) },
                    size = 32.dp,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(modifier = Modifier.weight(1f).height(40.dp))
                        } else {
                            val selected = date == initialDate
                            val isToday = date == today
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(2.dp)
                                    .height(38.dp)
                                    .clip(CircleShape)
                                    .then(
                                        when {
                                            selected -> Modifier.background(
                                                MaterialTheme.colorScheme.primary
                                            )

                                            isToday -> Modifier.border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape,
                                            )

                                            else -> Modifier
                                        }
                                    )
                                    .clickable { onPick(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected || isToday) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onBackground
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.stats_close))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 内容：总览 + 根节点列表 + 展开子节点
// ---------------------------------------------------------------------------

@Composable
private fun StatsContent(
    modifier: Modifier,
    snapshot: StatsSnapshot,
    now: Long,
    period: StatsPeriod,
    expandedIds: Set<Long>,
    onToggleExpanded: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val totals = remember(snapshot, now) {
        StatsAggregator.byRoot(snapshot.records, snapshot.types, snapshot.from, snapshot.to, now)
    }
    // 已统计 = 各根节点时长之和
    val trackedMillis = remember(totals) { StatsAggregator.totalMillis(totals) }
    // 周期总时长 = to - from（今日 24h / 本周 7 天 / 本月天数），作为占比的统一分母
    val periodTotalMillis = (snapshot.to - snapshot.from).coerceAtLeast(0L)
    // 未统计 = 周期内没有记录的时间（重叠补记等极端情况下最小为 0）
    val untrackedMillis = (periodTotalMillis - trackedMillis).coerceAtLeast(0L)
    // 有（未归档或已归档）子节点的节点 id：点击后展开而不是直接看明细
    val parentIds = remember(snapshot) {
        snapshot.types.mapNotNull { it.parentId }.toSet()
    }
    // 直接挂在某个父节点自身上的记录时长（历史数据 / 手动补记可能出现）
    val directMillis = remember(snapshot, now) {
        StatsAggregator.directTotals(snapshot.records, snapshot.types, snapshot.from, snapshot.to, now)
    }
    val hasOngoing = remember(snapshot) { snapshot.records.any { it.endAt == null } }
    // 「未统计」灰点：浅/深主题下都保持可见的灰
    val untrackedDotColor = if (TimingColors.isDark(MaterialTheme.colorScheme.background)) {
        Color(0xFF8FA3C4)
    } else {
        Color(0xFF9AA6BA)
    }

    // 仅当「已统计 = 0 且周期总时长 = 0」才显示传统空状态；
    // 只有未统计时展示整圈灰环 + 底部「未统计」行。
    if (trackedMillis <= 0L && periodTotalMillis <= 0L) {
        StatsEmptyState(period = period)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary") {
            StatsSummaryCard(
                totals = totals,
                trackedMillis = trackedMillis,
                untrackedMillis = untrackedMillis,
                periodTotalMillis = periodTotalMillis,
                hasOngoing = hasOngoing,
            )
        }
        items(totals, key = { total -> total.type.id }) { total ->
            val expanded = total.type.id in expandedIds
            val hasChildren = total.type.id in parentIds
            Column {
                StatsNodeRow(
                    name = total.type.name,
                    color = Color(total.type.colorArgb),
                    millis = total.millis,
                    percentBase = periodTotalMillis,
                    depth = 0,
                    expandable = hasChildren,
                    expanded = expanded,
                    onClick = {
                        if (hasChildren) onToggleExpanded(total.type.id) else onOpenDetail(total.type.id)
                    },
                )
                if (hasChildren && expanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    StatsChildNodes(
                        nodeId = total.type.id,
                        depth = 1,
                        snapshot = snapshot,
                        now = now,
                        percentBase = periodTotalMillis,
                        parentIds = parentIds,
                        directMillis = directMillis,
                        expandedIds = expandedIds,
                        onToggleExpanded = onToggleExpanded,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }
        // 列表底部：不可点击的灰色「未统计」行，占比分母同样是周期总时长
        if (untrackedMillis > 0L) {
            item(key = "untracked") {
                StatsNodeRow(
                    name = stringResource(R.string.stats_untracked),
                    color = untrackedDotColor,
                    millis = untrackedMillis,
                    percentBase = periodTotalMillis,
                    depth = 0,
                    expandable = false,
                    expanded = false,
                    onClick = null,
                )
            }
        }
    }
}

/** 展开某个节点后的子节点列表（递归，最多 3 层）。 */
@Composable
private fun StatsChildNodes(
    nodeId: Long,
    depth: Int,
    snapshot: StatsSnapshot,
    now: Long,
    percentBase: Long,
    parentIds: Set<Long>,
    directMillis: Map<Long, Long>,
    expandedIds: Set<Long>,
    onToggleExpanded: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val children = remember(snapshot, now, nodeId) {
        StatsAggregator.childrenTotals(
            snapshot.records,
            snapshot.types,
            nodeId,
            snapshot.from,
            snapshot.to,
            now,
        )
    }
    val direct = directMillis[nodeId] ?: 0L
    val nodeColor = remember(snapshot, nodeId) {
        Color(snapshot.types.firstOrNull { it.id == nodeId }?.colorArgb ?: FALLBACK_COLOR)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        children.forEach { child ->
            val childExpanded = child.type.id in expandedIds
            val childHasChildren = child.type.id in parentIds
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsNodeRow(
                    name = child.type.name,
                    color = Color(child.type.colorArgb),
                    millis = child.millis,
                    percentBase = percentBase,
                    depth = depth,
                    expandable = childHasChildren,
                    expanded = childExpanded,
                    onClick = {
                        if (childHasChildren) {
                            onToggleExpanded(child.type.id)
                        } else {
                            onOpenDetail(child.type.id)
                        }
                    },
                )
                if (childHasChildren && childExpanded) {
                    StatsChildNodes(
                        nodeId = child.type.id,
                        depth = depth + 1,
                        snapshot = snapshot,
                        now = now,
                        percentBase = percentBase,
                        parentIds = parentIds,
                        directMillis = directMillis,
                        expandedIds = expandedIds,
                        onToggleExpanded = onToggleExpanded,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }

        // 父节点自身的直接记录（不在任何子节点里），单独一行，保证时长能对上
        if (direct > 0L) {
            StatsNodeRow(
                name = stringResource(R.string.stats_direct_records),
                color = nodeColor,
                millis = direct,
                percentBase = percentBase,
                depth = depth,
                expandable = false,
                expanded = false,
                onClick = { onOpenDetail(nodeId) },
            )
        }

        if (children.isEmpty() && direct <= 0L) {
            Text(
                text = stringResource(R.string.stats_no_child_records),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = (depth * 14).dp,
                    top = 2.dp,
                    bottom = 2.dp,
                ),
            )
        }
    }
}

/**
 * 一行统计（半透明玻璃行卡片）：色点 + 名称 + HH:MM + 横向占比条 + 占比百分比。
 * [color] 为该行颜色；[percentBase] 为占比分母（统一为周期总时长）；
 * [depth] 用于缩进（0 = 根节点）；[onClick] 为 null 时整行不可点击（用于「未统计」行）。
 */
@Composable
private fun StatsNodeRow(
    name: String,
    color: Color,
    millis: Long,
    percentBase: Long,
    depth: Int,
    expandable: Boolean,
    expanded: Boolean,
    onClick: (() -> Unit)?,
) {
    val fraction = if (percentBase > 0L) {
        (millis.toFloat() / percentBase.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp),
        shape = RoundedCornerShape(if (depth == 0) 20.dp else 15.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(if (depth == 0) 12.dp else 10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = if (depth == 0) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = TimeMath.formatDuration(millis),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                // 0 宽度会让占比条看起来「没画出来」，极小时给一个可见下限
                                .fillMaxWidth(fraction.coerceIn(0.002f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatPercent(millis, percentBase),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(52.dp),
                    )
                }
            }
            if (expandable) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Spacer(modifier = Modifier.width(20.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 总览卡片 + 环形占比图
// ---------------------------------------------------------------------------

@Composable
private fun StatsSummaryCard(
    totals: List<TypeTotal>,
    trackedMillis: Long,
    untrackedMillis: Long,
    periodTotalMillis: Long,
    hasOngoing: Boolean,
) {
    val untrackedAccent = MaterialTheme.colorScheme.outline
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatsShareRing(
                totals = totals,
                periodTotalMillis = periodTotalMillis,
                modifier = Modifier.size(118.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_total_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = TimeMath.formatDuration(trackedMillis),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(2.dp))
                // 总时长仍显示已统计时长，并额外显示「未统计 HH:MM」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(untrackedAccent),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.stats_untracked) + " " +
                            TimeMath.formatDuration(untrackedMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.stats_share_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasOngoing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.stats_ongoing_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (totals.size > MAX_RING_SEGMENTS) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.outline),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.stats_others),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 环形占比图：Canvas.drawArc 绘制。
 *
 * - 分母 = 周期总时长（[periodTotalMillis]）；
 * - 已统计各根节点按占比画弧，最多 [MAX_RING_SEGMENTS] 段，其余归入灰色「其它」；
 * - 整圈先铺「未统计」灰底，已统计段覆盖在上面，剩余部分即灰色「未统计」段（灰色段在最后）；
 * - 无已统计时就是整圈灰。
 */
@Composable
private fun StatsShareRing(
    totals: List<TypeTotal>,
    periodTotalMillis: Long,
    modifier: Modifier,
) {
    // 浅色下接近 #E8EDF5、深色下自动变暗；两种主题都可辨认
    val untrackedColor = if (TimingColors.isDark(MaterialTheme.colorScheme.background)) {
        Color(0xFF3C4658)
    } else {
        Color(0xFFD3DAE6)
    }
    val othersColor = MaterialTheme.colorScheme.outline
    val segments: List<Pair<Int?, Long>> = remember(totals) {
        buildList {
            totals.take(MAX_RING_SEGMENTS).forEach { add(it.type.colorArgb to it.millis) }
            val rest = totals.drop(MAX_RING_SEGMENTS).sumOf { it.millis }
            if (rest > 0L) add(null to rest)
        }
    }
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.14f
        val inset = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val stroke = Stroke(width = strokeWidth)
        // 整圈「未统计」灰底：没有已统计时即整圈灰；已统计段从 -90° 顺时针覆盖
        drawArc(
            color = untrackedColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        if (periodTotalMillis <= 0L) return@Canvas
        var angle = -90f
        var remaining = 360f
        for ((argb, millis) in segments) {
            if (remaining <= 0f) break
            // 重叠补记等异常数据下已统计可能超过周期总时长，这里截断避免画弧重叠
            val sweep = (360f * millis.toFloat() / periodTotalMillis.toFloat())
                .coerceAtMost(remaining)
            if (sweep <= 0f) continue
            drawArc(
                color = argb?.let { Color(it) } ?: othersColor,
                startAngle = angle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            angle += sweep
            remaining -= sweep
        }
    }
}

private const val MAX_RING_SEGMENTS = 8
private val FALLBACK_COLOR = 0xFF90A4AE.toInt()

// ---------------------------------------------------------------------------
// 明细弹窗（Fluid Glass）
// ---------------------------------------------------------------------------

@Composable
private fun StatsDetailDialog(
    detail: StatsDetail,
    period: StatsPeriod,
    onDismiss: () -> Unit,
) {
    val totalMillis = detail.rows.sumOf { it.second }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            Text(
                text = detail.type.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (detail.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_detail_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.stats_detail_summary,
                        TimeMath.formatDuration(totalMillis),
                        detail.rows.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(detail.rows, key = { row -> row.first.id }) { row ->
                        StatsRecordRow(
                            record = row.first,
                            millis = row.second,
                            period = period,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.stats_close))
                }
            }
        }
    }
}

@Composable
private fun StatsRecordRow(
    record: TimeRecordEntity,
    millis: Long,
    period: StatsPeriod,
) {
    val endAt = record.endAt
    val startText = if (period == StatsPeriod.DAY) {
        TimeMath.formatClock(record.startAt)
    } else {
        TimeMath.formatDateTime(record.startAt)
    }
    val endText = when {
        endAt == null -> stringResource(R.string.stats_ongoing)
        period == StatsPeriod.DAY -> TimeMath.formatClock(endAt)
        else -> TimeMath.formatDateTime(endAt)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$startText - $endText",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val comment = record.comment
            if (!comment.isNullOrBlank()) {
                Text(
                    text = comment,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = TimeMath.formatDuration(millis),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// ---------------------------------------------------------------------------
// 空 / 加载 / 错误
// ---------------------------------------------------------------------------

@Composable
private fun StatsEmptyState(period: StatsPeriod) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(22.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(period.emptyRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.stats_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatsLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.stats_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatsErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(22.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.stats_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.stats_retry))
                }
            }
        }
    }
}

@Composable
private fun StatsErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.stats_error_banner, message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.stats_retry))
        }
    }
}

package com.togotiming.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.togotiming.app.R
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.domain.TimeMath
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.components.GlassPill
import com.togotiming.app.ui.components.RingProgress
import com.togotiming.app.ui.components.SectionTitle
import com.togotiming.app.ui.theme.TimingColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/** 目标默认值（分钟）与合法范围（与数据层一致：1 ~ 24h） */
private const val DEFAULT_TARGET_MINUTES = 60
private const val MAX_TARGET_MINUTES = 24 * 60

private fun coerceTargetMinutes(minutes: Int?): Int =
    (minutes ?: DEFAULT_TARGET_MINUTES).coerceIn(1, MAX_TARGET_MINUTES)

/**
 * v1.2 首页（Fluid Glass × 横向 Gantt）。
 *
 * - 顶部：日期胶囊（日期 + 星期，点击展开月历）+ 「今天」大标题 + 日历玻璃圆钮 + 前后日切换；
 * - 月历：Compose 自绘 6 行 × 7 列（周一起始），可切上/下月，选日期后自动收起；
 * - Hero：GlassCard（30dp 圆角）事件名 + 父级路径 + 进行中胶囊、超大等宽计时、
 *   88dp 目标进度环（已用 / 本次目标，超目标封顶 100% 并显示「已超时」）、
 *   渐变「结束计时 / 开始计时」+ 玻璃「补记」；
 * - 目标可配：开始计时对话框可填本次目标（默认取所选分类的 targetMinutes），
 *   计时中点进度环或小铅笔可修改本次目标（vm.updateRecordTarget）；
 * - 快捷 chip 行：叶子事件，当前事件高亮；
 * - 「时间轴」分区：GanttTimeline（横向 Gantt，双指缩放 / 单指横拖 / 纵向滚动 / 点击编辑、空白补记）。
 *
 * 背景由 AppNav 的 AppBackground 提供，本页不再铺大色块。
 */
@Composable
fun HomeScreen(vm: MainViewModel, navController: NavHostController) {
    val active by vm.activeRecord.collectAsState()
    val activeType by vm.activeType.collectAsState()
    val dayRecords by vm.dayRecords.collectAsState()
    val selectedDay by vm.selectedDay.collectAsState()
    val leaves by vm.leaves.collectAsState()
    val types by vm.types.collectAsState()

    // 每秒推进：Hero 计时 + 时间轴 LIVE 块都依赖 now
    val now by produceState(System.currentTimeMillis(), active?.id) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    var showStartDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TimeRecordEntity?>(null) }
    var backfillAt by remember { mutableLongStateOf(-1L) }
    // 正在修改「本次目标」的记录 id（-1 = 未打开；记录停止后对话框自动消失）
    var targetEditRecordId by remember { mutableLongStateOf(-1L) }
    // 月历展开状态 / 当前翻到的月份 / 顶部区域高度（月历弹层定位用）
    var showCalendar by remember { mutableStateOf(false) }
    var calendarMonth by remember {
        mutableStateOf(YearMonth.from(TimeMath.localDate(selectedDay)))
    }
    var headerHeightPx by remember { mutableIntStateOf(0) }

    BackHandler(enabled = showCalendar) { showCalendar = false }

    // 计时停止（记录变为 null）时关闭目标编辑状态，避免下次计时复用到过期 id
    LaunchedEffect(active?.id) {
        if (active == null) targetEditRecordId = -1L
    }

    val dayEnd = TimeMath.nextDayStart(selectedDay)
    val dayTotal = dayRecords.sumOf {
        TimeMath.clippedMillis(it.startAt, it.endAt, selectedDay, dayEnd, now)
    }
    val todayStart = TimeMath.dayStart(now)
    val isToday = todayStart == selectedDay
    val dateLabel = TimeMath.formatDateCn(selectedDay).replace("星期", "周")
    val dateShort = dateLabel.substringBefore(" ")
    val pageTitle = when {
        isToday -> stringResource(R.string.home_title_today)
        selectedDay == TimeMath.dayStart(now - TimeMath.DAY) -> stringResource(R.string.home_title_yesterday)
        else -> dateShort
    }
    // 顶部日期胶囊副标题：周四 · 今天 / 周四 · 昨天 / 周四
    val yesterdayStart = TimeMath.dayStart(now - TimeMath.DAY)
    val weekText = dateLabel.substringAfter(" ", "").trim()
    val chipSubtitle = when {
        isToday -> "$weekText · ${stringResource(R.string.home_title_today)}"
        selectedDay == yesterdayStart -> "$weekText · ${stringResource(R.string.home_title_yesterday)}"
        else -> weekText
    }
    // 空闲时展示「默认目标」：当前分类（无则第一个叶子）的目标分钟数
    val idleTargetMinutes = coerceTargetMinutes(
        activeType?.targetMinutes ?: leaves.firstOrNull()?.targetMinutes
    )
    val backfill = { backfillAt = now - 30 * TimeMath.MINUTE }
    val openCalendar = {
        if (!showCalendar) {
            calendarMonth = YearMonth.from(TimeMath.localDate(selectedDay))
        }
        showCalendar = !showCalendar
    }
    val density = LocalDensity.current
    val sheetTop = with(density) { headerHeightPx.toDp() } + 2.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                dateBig = dateShort,
                weekSubtitle = chipSubtitle,
                pageTitle = pageTitle,
                showTodayChip = !isToday,
                onPrevDay = { vm.shiftDay(-1) },
                onNextDay = { vm.shiftDay(1) },
                onBackToToday = { vm.setSelectedDay(System.currentTimeMillis()) },
                onToggleCalendar = openCalendar,
                modifier = Modifier.onSizeChanged { headerHeightPx = it.height },
            )

            HeroTimerCard(
                active = active,
                activeType = activeType,
                types = types,
                now = now,
                idleTargetMinutes = idleTargetMinutes,
                onStartClick = { showStartDialog = true },
                onStopClick = { vm.stopTimer() },
                onBackfillClick = backfill,
                onEditTarget = {
                    active?.let { record -> targetEditRecordId = record.id }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 2.dp),
            )

            if (leaves.isNotEmpty()) {
                QuickChipRow(
                    leaves = leaves,
                    activeTypeId = active?.typeId,
                    onPick = { type ->
                        // 未在计时：直接用该分类的默认目标开始（数据层 targetMinutes = null 时回退到分类目标）
                        vm.startTimer(type.id)
                    },
                )
            }

            SectionTitle(
                title = stringResource(R.string.home_section_timeline),
                trailing = if (isToday) {
                    stringResource(R.string.home_total_today, TimeMath.formatDuration(dayTotal))
                } else {
                    stringResource(
                        R.string.home_total_day,
                        dateShort,
                        TimeMath.formatDuration(dayTotal),
                    )
                },
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
            )

            GanttTimeline(
                dayStart = selectedDay,
                records = dayRecords,
                types = types,
                now = now,
                onEdit = { editing = it },
                onBlankTap = { backfillAt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
            )
        }

        // 月历弹层：点击空白关闭；选日期后自动收起
        if (showCalendar) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f))
                        .pointerInput(Unit) {
                            detectTapGestures { showCalendar = false }
                        },
                )
                CalendarSheet(
                    yearMonth = calendarMonth,
                    selectedDayStart = selectedDay,
                    todayStart = todayStart,
                    onPrevMonth = { calendarMonth = calendarMonth.minusMonths(1) },
                    onNextMonth = { calendarMonth = calendarMonth.plusMonths(1) },
                    onPickDay = { dayStartMs ->
                        vm.setSelectedDay(dayStartMs)
                        showCalendar = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = sheetTop)
                        .pointerInput(Unit) { detectTapGestures { } },
                )
            }
        }
    }

    if (showStartDialog) {
        StartTypeDialog(
            leaves = leaves,
            activeTypeId = active?.typeId,
            onDismiss = { showStartDialog = false },
            onPick = { type, targetMinutes ->
                vm.startTimer(type.id, targetMinutes = targetMinutes)
                showStartDialog = false
            },
        )
    }

    // 计时中修改「本次目标」
    val targetRecord = active
    if (targetRecord != null && targetEditRecordId == targetRecord.id) {
        TargetMinutesDialog(
            initialMinutes = coerceTargetMinutes(
                targetRecord.targetMinutes ?: activeType?.targetMinutes
            ),
            onDismiss = { targetEditRecordId = -1L },
            onSave = { minutes ->
                vm.updateRecordTarget(targetRecord.id, minutes)
                targetEditRecordId = -1L
            },
        )
    }

    editing?.let { record ->
        RecordEditDialog(
            record = record,
            defaultStart = record.startAt,
            defaultTypeId = record.typeId,
            types = types,
            onDismiss = { editing = null },
            onSave = { typeId, start, end, comment ->
                vm.updateRecord(record.id, typeId, start, end, comment)
                editing = null
            },
            onDelete = {
                vm.deleteRecord(record.id)
                editing = null
            },
        )
    }

    if (backfillAt >= 0) {
        RecordEditDialog(
            record = null,
            defaultStart = backfillAt,
            defaultTypeId = leaves.firstOrNull()?.id,
            types = types,
            onDismiss = { backfillAt = -1L },
            onSave = { typeId, start, end, comment ->
                if (end != null) {
                    vm.addManual(typeId, start, end, comment)
                }
                backfillAt = -1L
            },
            onDelete = null,
        )
    }
}

// ---------------------------------------------------------------------------
// 顶部：日期胶囊 + 今天 + 日历按钮
// ---------------------------------------------------------------------------

@Composable
private fun HomeHeader(
    dateBig: String,
    weekSubtitle: String,
    pageTitle: String,
    showTodayChip: Boolean,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onBackToToday: () -> Unit,
    onToggleCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 16.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevDay, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ChevronLeft,
                        contentDescription = stringResource(R.string.home_cd_prev_day),
                        modifier = Modifier.size(16.dp),
                        tint = muted,
                    )
                }
                DateChip(
                    dateBig = dateBig,
                    weekSubtitle = weekSubtitle,
                    onClick = onToggleCalendar,
                )
                IconButton(onClick = onNextDay, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.home_cd_next_day),
                        modifier = Modifier.size(16.dp),
                        tint = muted,
                    )
                }
                // 非今天：日期胶囊旁出现「今天」小玻璃胶囊，一键回到今天
                if (showTodayChip) {
                    Spacer(Modifier.width(4.dp))
                    TodayChip(onClick = onBackToToday)
                }
            }
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        GlassCircleButton(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = stringResource(R.string.home_calendar_open),
            onClick = onToggleCalendar,
        )
    }
}

/** 日期胶囊：日历图标 +「11月26日 / 周四·今天」；点击展开月历。 */
@Composable
private fun DateChip(
    dateBig: String,
    weekSubtitle: String,
    onClick: () -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
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
            .clickable(
                onClickLabel = stringResource(R.string.home_calendar_open),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = dateBig,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = weekSubtitle,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 小玻璃胶囊「今天」：仅当选中日期不是今天时显示，点击回到今天。 */
@Composable
private fun TodayChip(onClick: () -> Unit) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .height(26.dp)
            .shadow(
                elevation = 6.dp,
                shape = shape,
                ambientColor = primary.copy(alpha = 0.10f),
                spotColor = primary.copy(alpha = 0.14f),
            )
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .clickable(
                onClickLabel = stringResource(R.string.home_back_today),
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_today_chip),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = primary,
            maxLines = 1,
        )
    }
}

/** 玻璃圆形图标按钮（日历） */
@Composable
private fun GlassCircleButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = CircleShape
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(44.dp)
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
// 月历弹层（Compose 自绘，6 行 × 7 列，周一起始）
// ---------------------------------------------------------------------------

@Composable
private fun CalendarSheet(
    yearMonth: YearMonth,
    selectedDayStart: Long,
    todayStart: Long,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onPickDay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(26.dp)
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val weekLabels = stringArrayResource(R.array.home_calendar_weekdays)
    val selectedDate = TimeMath.localDate(selectedDayStart)
    val todayDate = TimeMath.localDate(todayStart)
    val firstOfMonth = yearMonth.atDay(1)
    // 周一起始：把 1 号往前推到所在周的周一
    val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())

    Box(
        modifier = modifier
            .shadow(
                elevation = 28.dp,
                shape = shape,
                ambientColor = primary.copy(alpha = 0.16f),
                spotColor = primary.copy(alpha = 0.20f),
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.94f else 0.96f))
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.home_calendar_month_title,
                        yearMonth.year,
                        yearMonth.monthValue,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPrevMonth, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ChevronLeft,
                        contentDescription = stringResource(R.string.home_calendar_prev_month),
                        modifier = Modifier.size(16.dp),
                        tint = muted,
                    )
                }
                IconButton(onClick = onNextMonth, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.home_calendar_next_month),
                        modifier = Modifier.size(16.dp),
                        tint = muted,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                weekLabels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            for (week in 0 until 6) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (dayOfWeek in 0 until 7) {
                        val date = gridStart.plusDays((week * 7 + dayOfWeek).toLong())
                        CalendarDayCell(
                            date = date,
                            inMonth = date.year == yearMonth.year && date.month == yearMonth.month,
                            selected = date == selectedDate,
                            today = date == todayDate,
                            onClick = {
                                onPickDay(
                                    date.atStartOfDay(TimeMath.zone).toInstant().toEpochMilli()
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_calendar_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (selectedDate != todayDate) {
                    Text(
                        text = stringResource(R.string.home_back_today),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onPickDay(todayStart) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    today: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = when {
        selected -> onPrimary
        inMonth -> onSurface
        else -> muted.copy(alpha = 0.45f)
    }
    Column(
        modifier = modifier
            .padding(2.dp)
            .height(34.dp)
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.background(
                        Brush.linearGradient(listOf(primary, primary.copy(alpha = 0.82f)))
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (today && !selected) {
                    Modifier.border(1.dp, primary.copy(alpha = 0.45f), shape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = 11.sp,
            fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
        )
        if (today) {
            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(if (selected) onPrimary else primary),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Hero 计时卡
// ---------------------------------------------------------------------------

@Composable
private fun HeroTimerCard(
    active: TimeRecordEntity?,
    activeType: ActivityTypeEntity?,
    types: List<ActivityTypeEntity>,
    now: Long,
    idleTargetMinutes: Int,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onBackfillClick: () -> Unit,
    onEditTarget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isRunning = active != null
    val elapsed = if (active != null) (now - active.startAt).coerceAtLeast(0L) else 0L

    // 当前目标 = 本次记录目标 ?: 分类目标 ?: 60（空闲时展示默认目标）
    val targetMinutes = coerceTargetMinutes(
        if (isRunning) {
            active?.targetMinutes ?: activeType?.targetMinutes
        } else {
            idleTargetMinutes
        }
    )
    val targetMs = targetMinutes * 60_000L
    val ratio = if (targetMs > 0L) elapsed.toFloat() / targetMs.toFloat() else 0f
    val progress = ratio.coerceIn(0f, 1f)
    val percent = (ratio * 100f).roundToInt().coerceIn(0, 100)
    val isOver = isRunning && elapsed > targetMs

    val eventColor = activeType?.let { Color(it.colorArgb) } ?: primary
    val parentPath = remember(types, activeType) { buildParentPath(types, activeType) }
    val ringColor = if (isOver) errorColor else primary
    val subtitle = when {
        isOver -> stringResource(R.string.home_over_by, TimeMath.formatDuration(elapsed - targetMs))
        isRunning -> stringResource(R.string.home_goal_hint, targetMinutes)
        else -> stringResource(R.string.home_idle_hint, targetMinutes)
    }

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        onClick = if (isRunning) null else onStartClick,
    ) {
        // 事件名 + 父级路径 + 进行中胶囊
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) eventColor else muted.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isRunning) {
                    activeType?.name ?: stringResource(R.string.home_idle_title)
                } else {
                    stringResource(R.string.home_idle_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isRunning && parentPath.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = parentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (isRunning) {
                GlassPill(
                    text = stringResource(R.string.home_ongoing),
                    color = primary,
                    container = primary.copy(alpha = 0.13f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 超大等宽计时 + 88dp 目标进度环（已用 / 目标；计时中可点击修改目标）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = TimeMath.formatDurationSeconds(elapsed),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1.2).sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = if (isRunning) onSurface else onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isRunning) {
                        Spacer(Modifier.width(4.dp))
                        // 小铅笔：与点击圆环等效的「修改本次目标」入口
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(
                                    onClickLabel = stringResource(R.string.home_edit_target),
                                    onClick = onEditTarget,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.home_edit_target),
                                modifier = Modifier.size(14.dp),
                                tint = primary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .then(
                        if (isRunning) {
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.home_edit_target),
                                onClick = onEditTarget,
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                RingProgress(
                    progress = progress,
                    modifier = Modifier.fillMaxSize(),
                    color = ringColor,
                    trackColor = ringColor.copy(alpha = 0.12f),
                    strokeWidth = 8.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.home_ring_percent, percent),
                        color = ringColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        text = if (isOver) {
                            stringResource(R.string.home_goal_over)
                        } else {
                            stringResource(R.string.home_goal_minutes, targetMinutes)
                        },
                        color = if (isOver) errorColor else muted,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 主操作：结束 / 开始计时的蓝色渐变胶囊 + 补记玻璃胶囊
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientActionButton(
                text = if (isRunning) {
                    stringResource(R.string.home_action_end)
                } else {
                    stringResource(R.string.home_action_start)
                },
                imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                onClick = if (isRunning) onStopClick else onStartClick,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            GlassActionPill(
                text = stringResource(R.string.home_action_backfill),
                imageVector = Icons.Filled.Add,
                onClick = onBackfillClick,
            )
        }
    }
}

/** 蓝色渐变主按钮（结束 / 开始计时） */
@Composable
private fun GradientActionButton(
    text: String,
    imageVector: ImageVector,
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
            .height(46.dp)
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = TimingColors.Blue.copy(alpha = 0.30f),
                spotColor = TimingColors.Blue.copy(alpha = 0.30f),
            )
            .clip(shape)
            .background(Brush.horizontalGradient(gradient))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
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
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 玻璃次按钮（补记） */
@Composable
private fun GlassActionPill(
    text: String,
    imageVector: ImageVector,
    onClick: () -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 快捷 chip 行
// ---------------------------------------------------------------------------

@Composable
private fun QuickChipRow(
    leaves: List<ActivityTypeEntity>,
    activeTypeId: Long?,
    onPick: (ActivityTypeEntity) -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val primary = MaterialTheme.colorScheme.primary
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(leaves, key = { it.id }) { type ->
            val selected = activeTypeId == type.id
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (selected) {
                            primary.copy(alpha = if (dark) 0.22f else 0.13f)
                        } else {
                            if (dark) TimingColors.DarkGlass else TimingColors.LightGlass
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) {
                            primary.copy(alpha = 0.35f)
                        } else {
                            if (dark) TimingColors.DarkBorder else TimingColors.LightBorder
                        },
                        shape = shape,
                    )
                    .clickable { onPick(type) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(type.colorArgb)),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = type.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 父级路径，如「学习 / 英语」；无父级返回空串 */
private fun buildParentPath(
    types: List<ActivityTypeEntity>,
    type: ActivityTypeEntity?,
): String {
    if (type == null) return ""
    val byId = types.associateBy { it.id }
    val names = mutableListOf<String>()
    var cursor = type.parentId?.let { byId[it] }
    var guard = 0
    while (cursor != null && guard++ < 10) {
        names.add(0, cursor.name)
        cursor = cursor.parentId?.let { byId[it] }
    }
    return names.joinToString(" / ")
}

// ---------------------------------------------------------------------------
// 选择活动弹窗（含「本次目标（分钟）」输入）
// ---------------------------------------------------------------------------

@Composable
private fun StartTypeDialog(
    leaves: List<ActivityTypeEntity>,
    activeTypeId: Long?,
    onDismiss: () -> Unit,
    onPick: (ActivityTypeEntity, Int) -> Unit,
) {
    val defaultType = leaves.firstOrNull { it.id == activeTypeId } ?: leaves.firstOrNull()
    var selectedId by remember { mutableLongStateOf(defaultType?.id ?: 0L) }
    var targetText by remember {
        mutableStateOf(coerceTargetMinutes(defaultType?.targetMinutes).toString())
    }
    var error by remember { mutableStateOf<String?>(null) }
    val selectedType = leaves.firstOrNull { it.id == selectedId } ?: defaultType
    val invalidMessage = stringResource(R.string.home_target_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_start_dialog_title)) },
        text = {
            if (leaves.isEmpty()) {
                Text(stringResource(R.string.home_start_empty))
            } else {
                Column {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(leaves, key = { it.id }) { type ->
                            val selected = type.id == selectedType?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        selectedId = type.id
                                        targetText =
                                            coerceTargetMinutes(type.targetMinutes).toString()
                                        error = null
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(type.colorArgb)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = type.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.home_goal_minutes,
                                        coerceTargetMinutes(type.targetMinutes),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { value ->
                            targetText = value.filter { ch -> ch.isDigit() }.take(4)
                            error = null
                        },
                        label = { Text(stringResource(R.string.home_start_target_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.home_start_target_default,
                                    coerceTargetMinutes(selectedType?.targetMinutes),
                                )
                            )
                        },
                        singleLine = true,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (leaves.isNotEmpty()) {
                TextButton(onClick = {
                    val type = selectedType ?: return@TextButton
                    val minutes = targetText.toIntOrNull()
                    if (minutes == null || minutes !in 1..MAX_TARGET_MINUTES) {
                        error = invalidMessage
                        return@TextButton
                    }
                    onPick(type, minutes)
                }) { Text(stringResource(R.string.home_start_action)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// 修改「本次目标」弹窗
// ---------------------------------------------------------------------------

@Composable
private fun TargetMinutesDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(coerceTargetMinutes(initialMinutes).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidMessage = stringResource(R.string.home_target_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_target_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        text = value.filter { ch -> ch.isDigit() }.take(4)
                        error = null
                    },
                    label = { Text(stringResource(R.string.home_target_input_label)) },
                    supportingText = { Text(stringResource(R.string.home_target_input_hint)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = text.toIntOrNull()
                if (minutes == null || minutes !in 1..MAX_TARGET_MINUTES) {
                    error = invalidMessage
                    return@TextButton
                }
                onSave(coerceTargetMinutes(minutes))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

package com.togotiming.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.togotiming.app.R
import com.togotiming.app.appContainer
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity
import com.togotiming.app.domain.StatsAggregator
import com.togotiming.app.domain.TimeMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * W1 2×2「当前事件计时环」。
 *
 * - Canvas 画圆角渐变卡片 + 秒表式进度环（1h 满环，圆头）；
 * - 环中央叠真实控件：叶子事件名 + 原生 Chronometer（系统每秒自走）+「点击停止」；
 * - 点击整卡 = 停止当前计时；空闲 = 「未在计时 / 点击开始」，点击打开 App。
 */
class TimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refreshAsync(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun refreshAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                update(context, manager, ids)
            } catch (_: Exception) {
                // 数据层异常时保持上一次的 RemoteViews，不崩
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        /** 记录 / 分类都没有目标时的兜底目标（分钟） */
        private const val FALLBACK_TARGET_MINUTES = 60
        private const val MINUTE_MS = 60_000L

        suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val repository = context.appContainer.repository
            val now = System.currentTimeMillis()
            val active: TimeRecordEntity? = repository.activeNow()
            val activeType: ActivityTypeEntity? = active?.let { repository.typeById(it.typeId) }
            val scale = WidgetScale(context)
            ids.forEach { widgetId ->
                val (widthPx, heightPx) = WidgetSizes.px(context, manager, widgetId, 155, 155)
                manager.updateAppWidget(
                    widgetId,
                    buildViews(context, scale, active, activeType, now, widthPx, heightPx),
                )
            }
        }

        private fun buildViews(
            context: Context,
            scale: WidgetScale,
            active: TimeRecordEntity?,
            activeType: ActivityTypeEntity?,
            now: Long,
            widthPx: Int,
            heightPx: Int,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_timer)
            if (active != null && activeType != null) {
                val elapsed = (now - active.startAt).coerceAtLeast(0L)
                // 目标：本次记录覆盖值 > 分类默认目标 > 60 分钟
                val targetMinutes = (active.targetMinutes ?: activeType.targetMinutes)
                    .coerceAtLeast(1)
                val targetMs = targetMinutes.toLong() * MINUTE_MS
                val ink = WidgetRenderer.timerInk(activeType.colorArgb)
                views.setImageViewBitmap(
                    R.id.widget_timer_bg,
                    WidgetRenderer.renderTimerCard(
                        scale,
                        activeType.colorArgb,
                        elapsed,
                        targetMs,
                        true,
                        widthPx,
                        heightPx,
                    ),
                )
                views.setTextViewText(R.id.widget_timer_name, activeType.name)
                views.setTextColor(R.id.widget_timer_name, ink)
                // 原生 Chronometer：base 用 elapsedRealtime 时基，Service 不需要每秒刷新
                views.setChronometer(
                    R.id.widget_timer_chrono,
                    SystemClock.elapsedRealtime() - elapsed,
                    null,
                    true,
                )
                views.setViewVisibility(R.id.widget_timer_chrono, View.VISIBLE)
                views.setTextColor(R.id.widget_timer_chrono, ink)
                views.setTextViewText(
                    R.id.widget_timer_hint,
                    context.getString(R.string.widget_timer_stop_hint),
                )
                views.setTextColor(
                    R.id.widget_timer_hint,
                    WidgetDraw.withAlpha(ink, 0.72f),
                )
                views.setOnClickPendingIntent(R.id.widget_timer_root, WidgetIntents.stop(context))
            } else {
                val ink = WidgetDraw.TEXT_PRIMARY
                views.setImageViewBitmap(
                    R.id.widget_timer_bg,
                    WidgetRenderer.renderTimerCard(
                        scale,
                        WidgetDraw.BRAND,
                        0L,
                        FALLBACK_TARGET_MINUTES * MINUTE_MS,
                        false,
                        widthPx,
                        heightPx,
                    ),
                )
                views.setTextViewText(
                    R.id.widget_timer_name,
                    context.getString(R.string.widget_timer_idle_title),
                )
                views.setTextColor(R.id.widget_timer_name, ink)
                views.setViewVisibility(R.id.widget_timer_chrono, View.GONE)
                views.setTextViewText(
                    R.id.widget_timer_hint,
                    context.getString(R.string.widget_timer_idle_hint),
                )
                views.setTextColor(
                    R.id.widget_timer_hint,
                    WidgetDraw.withAlpha(ink, 0.60f),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_timer_root,
                    WidgetIntents.openApp(context),
                )
            }
            return views
        }
    }
}

/**
 * W2 2×2「今日 24h 分布环」。
 *
 * - 纯 Canvas：白色圆角卡片 + 24h 环；
 * - 底轨为「未记录时间」，已记录事件按叶子色画弧（平头 BUTT），进行中画到 now；
 * - 只有进行中事件的前进端（now）补一个同色的圆形圆头，整环没有额外白色圆点；
 * - 无文字；点击打开统计页。
 */
class RingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refreshAsync(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun refreshAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                update(context, manager, ids)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val repository = context.appContainer.repository
            val now = System.currentTimeMillis()
            val from = TimeMath.dayStart(now)
            val to = TimeMath.nextDayStart(now)
            val records = repository.recordsIn(from, to)
            val types = repository.allTypes(includeArchived = true)
            val scale = WidgetScale(context)
            ids.forEach { widgetId ->
                val (widthPx, heightPx) = WidgetSizes.px(context, manager, widgetId, 155, 155)
                val bitmap = WidgetRenderer.renderDayRing(
                    scale = scale,
                    records = records,
                    types = types,
                    from = from,
                    to = to,
                    now = now,
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
                val views = RemoteViews(context.packageName, R.layout.widget_ring)
                views.setImageViewBitmap(R.id.widget_ring_image, bitmap)
                views.setOnClickPendingIntent(R.id.widget_ring_root, WidgetIntents.openStats(context))
                manager.updateAppWidget(widgetId, views)
            }
        }
    }
}

/**
 * W3 4×2 / 4×4「今日一级分类磁贴」。
 *
 * - GridView + RemoteViewsService + RemoteViewsFactory，2 列网格；
 * - 只展示一级节点：子节点时长累加到父节点（StatsAggregator.byRoot）；
 * - 每块磁贴整块 Canvas 渲染：事件名 + 今日累计 + 右侧百分比环；
 * - 点击叶子 = 开始/切换（fill-in + ACTION_START_TYPE）；点击非叶子 = 打开 App；
 * - 空状态「今天还没有记录」。
 */
class ListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refreshAsync(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun refreshAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                update(context, manager, ids)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val repository = context.appContainer.repository
            val now = System.currentTimeMillis()
            val from = TimeMath.dayStart(now)
            val to = TimeMath.nextDayStart(now)
            val records = repository.recordsIn(from, to)
            val types = repository.allTypes(includeArchived = true)
            val totals = StatsAggregator.byRoot(records, types, from, to, now)
            val totalMillis = totals.sumOf { it.millis }
            val scale = WidgetScale(context)
            val title = context.getString(R.string.widget_tiles_title)

            ids.forEach { widgetId ->
                val (widthPx, heightPx) = WidgetSizes.px(context, manager, widgetId, 250, 140)
                val views = RemoteViews(context.packageName, R.layout.widget_list)
                views.setImageViewBitmap(
                    R.id.widget_list_bg,
                    WidgetRenderer.renderWidgetCard(scale, widthPx, heightPx),
                )
                views.setTextViewText(R.id.widget_list_title, title)
                views.setTextViewText(
                    R.id.widget_list_total,
                    if (totals.isEmpty()) "" else TimeMath.formatDuration(totalMillis),
                )
                views.setGridAdapter(context, widgetId)
                views.setEmptyView(R.id.widget_grid, R.id.widget_list_empty)
                views.setPendingIntentTemplate(
                    R.id.widget_grid,
                    WidgetIntents.startTypeTemplate(context, widgetId),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_list_root,
                    WidgetIntents.openAppForTile(context, widgetId),
                )
                manager.updateAppWidget(widgetId, views)
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_grid)
            }
        }

        /** setRemoteAdapter 在 API 31 起提供带 appWidgetId 的重载；两种重载在 API 36 均标记 deprecated，但仍是唯一可用路径 */
        @Suppress("DEPRECATION")
        private fun RemoteViews.setGridAdapter(context: Context, widgetId: Int) {
            val serviceIntent = WidgetIntents.gridService(context, widgetId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRemoteAdapter(widgetId, R.id.widget_grid, serviceIntent)
            } else {
                setRemoteAdapter(R.id.widget_grid, serviceIntent)
            }
        }
    }
}

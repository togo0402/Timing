package com.togotiming.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.togotiming.app.MainActivity
import com.togotiming.app.timer.TimerActionReceiver

/** 小部件点击用的 PendingIntent 工厂（requestCode 分区间，避免互相覆盖） */
internal object WidgetIntents {

    private const val RC_STOP = 100
    private const val RC_OPEN_TIMER = 101
    private const val RC_OPEN_STATS = 102

    /** W1：停止当前计时（TimerActionReceiver.ACTION_STOP） */
    fun stop(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_STOP,
        Intent(context, TimerActionReceiver::class.java).setAction(TimerActionReceiver.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 打开 App（W1 空闲 / W3 整体空白处 / W3 非叶子磁贴） */
    fun openApp(context: Context, requestCode: Int = RC_OPEN_TIMER): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** W2：打开统计页（MainActivity.EXTRA_ROUTE = "stats"） */
    fun openStats(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        RC_OPEN_STATS,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, "stats")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * W3 磁贴点击模板：ACTION_START_TYPE。
     * 需要 FLAG_MUTABLE，否则 fill-in intent（EXTRA_TYPE_ID）无法合并进来。
     */
    fun startTypeTemplate(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            9_000 + widgetId.coerceAtLeast(0),
            Intent(context, TimerActionReceiver::class.java)
                .setAction(TimerActionReceiver.ACTION_START_TYPE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** W3 非叶子磁贴：直接打开 App */
    fun openAppForTile(context: Context, widgetId: Int): PendingIntent =
        openApp(context, 8_000 + widgetId.coerceAtLeast(0))

    /** W3 RemoteViewsService 的 Intent（用 data 区分 widget 实例，避免复用同一个 factory） */
    fun gridService(context: Context, widgetId: Int): Intent =
        Intent(context, ListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("timing://widget/tiles/$widgetId")
        }
}

/** 小部件当前尺寸（px）：优先用 AppWidgetManager 给出的 options，拿不到时用兜底 dp */
internal object WidgetSizes {

    fun px(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        fallbackWidthDp: Int,
        fallbackHeightDp: Int,
    ): Pair<Int, Int> {
        val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val widthDp = options
            ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            ?.takeIf { it > 0 }
            ?: fallbackWidthDp
        val heightDp = options
            ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            ?.takeIf { it > 0 }
            ?: fallbackHeightDp
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt().coerceIn(96, 2_400)
        val heightPx = (heightDp * density).toInt().coerceIn(96, 2_400)
        return widthPx to heightPx
    }
}

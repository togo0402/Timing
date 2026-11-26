package com.togotiming.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.togotiming.app.appContainer
import com.togotiming.app.data.SettingsStore
import com.togotiming.app.data.TimingRepository
import com.togotiming.app.timer.WidgetAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 统一的小部件刷新入口：计时服务每分钟调用一次 */
class WidgetUpdater(
    private val context: Context,
    private val repository: TimingRepository,
    private val settings: SettingsStore,
) {

    suspend fun updateAll() {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val timerIds = manager.getAppWidgetIds(ComponentName(context, TimerWidgetProvider::class.java))
        if (timerIds.isNotEmpty()) TimerWidgetProvider.update(context, manager, timerIds)

        val ringIds = manager.getAppWidgetIds(ComponentName(context, RingWidgetProvider::class.java))
        if (ringIds.isNotEmpty()) RingWidgetProvider.update(context, manager, ringIds)

        val listIds = manager.getAppWidgetIds(ComponentName(context, ListWidgetProvider::class.java))
        if (listIds.isNotEmpty()) ListWidgetProvider.update(context, manager, listIds)
    }
}

/** 精确闹钟降级刷新：前台服务被停掉时兜底 */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.appContainer
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.widgetUpdater.updateAll()
                if (container.repository.activeNow() != null) {
                    WidgetAlarm.schedule(context)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.togotiming.app.action.WIDGET_TICK"
    }
}

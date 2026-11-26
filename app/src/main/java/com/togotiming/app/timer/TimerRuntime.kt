package com.togotiming.app.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.togotiming.app.MainActivity
import com.togotiming.app.appContainer
import com.togotiming.app.data.SOURCE_WIDGET
import com.togotiming.app.data.SettingsStore
import com.togotiming.app.data.TimingRepository
import com.togotiming.app.widget.WidgetTickReceiver
import com.togotiming.app.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TimerController(
    private val context: Context,
    private val repository: TimingRepository,
    private val widgets: WidgetUpdater,
    private val settings: SettingsStore,
) {
    suspend fun start(typeId: Long, source: String, targetMinutes: Int? = null) {
        settings.setPausedRecordId(null)
        repository.start(typeId, source, targetMinutes = targetMinutes)
        TimerService.start(context)
        WidgetAlarm.schedule(context)
        widgets.updateAll()
    }

    suspend fun stop() {
        repository.stop()
        settings.setPausedRecordId(null)
        TimerService.stop(context)
        WidgetAlarm.cancel(context)
        widgets.updateAll()
    }

    suspend fun pause() {
        val active = repository.activeNow() ?: return
        if (repository.pauseRecord(active.id)) {
            settings.setPausedRecordId(active.id)
            TimerService.start(context)
            widgets.updateAll()
        }
    }

    suspend fun resume() {
        val id = settings.pausedRecordId.value ?: return
        if (repository.resumeRecord(id)) {
            settings.setPausedRecordId(null)
            TimerService.start(context)
            WidgetAlarm.schedule(context)
            widgets.updateAll()
        }
    }

    suspend fun togglePause() {
        if (settings.pausedRecordId.value != null) resume() else pause()
    }

    fun ensureServiceRunning() {
        TimerService.start(context)
        WidgetAlarm.schedule(context)
    }
}

/** 小部件 / 通知上的点击 */
class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.appContainer
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_STOP -> container.timerController.stop()
                    ACTION_PAUSE -> container.timerController.pause()
                    ACTION_RESUME -> container.timerController.resume()
                    ACTION_TOGGLE_PAUSE -> container.timerController.togglePause()
                    ACTION_START_TYPE -> {
                        val typeId = intent.getLongExtra(EXTRA_TYPE_ID, -1L)
                        if (typeId > 0) {
                            val active = container.repository.activeNow()
                            if (active?.typeId == typeId) {
                                container.timerController.stop()
                            } else {
                                container.timerController.start(typeId, SOURCE_WIDGET)
                            }
                        }
                    }
                    ACTION_OPEN -> {
                        val launch = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(launch)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.togotiming.app.action.STOP"
        const val ACTION_PAUSE = "com.togotiming.app.action.PAUSE"
        const val ACTION_RESUME = "com.togotiming.app.action.RESUME"
        const val ACTION_TOGGLE_PAUSE = "com.togotiming.app.action.TOGGLE_PAUSE"
        const val ACTION_START_TYPE = "com.togotiming.app.action.START_TYPE"
        const val ACTION_OPEN = "com.togotiming.app.action.OPEN"
        const val EXTRA_TYPE_ID = "type_id"
    }
}

/** 开机 / 应用更新后恢复计时服务与刷新闹钟 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val container = context.appContainer
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.widgetUpdater.updateAll()
                if (container.repository.activeNow() != null || container.settings.pausedRecordId.value != null) {
                    container.timerController.ensureServiceRunning()
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * 精确闹钟降级刷新：前台服务被系统停掉时，约每 15 分钟刷新一次小部件。
 * 时长始终按 now - startAt 计算，刷新延迟不影响数据。
 */
object WidgetAlarm {

    private const val INTERVAL_MS = 15 * 60_000L
    private const val REQUEST_CODE = 4242

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WidgetTickReceiver::class.java).setAction(WidgetTickReceiver.ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + INTERVAL_MS
        val pi = pendingIntent(context)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.cancel(pendingIntent(context))
        } catch (_: Exception) {
        }
    }
}

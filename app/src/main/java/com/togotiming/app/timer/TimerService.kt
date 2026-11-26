package com.togotiming.app.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.togotiming.app.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 计时前台服务：
 * - 通知栏 RemoteViews 内嵌原生 Chronometer，系统每秒自动走秒，服务不轮询秒数；
 * - 支持暂停 / 继续（暂停时记录 endAt=now，继续时平移 startAt，有效时长不含暂停）；
 * - 每分钟刷新一次小部件。
 */
class TimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var lastMinute = Long.MIN_VALUE
    private var lastTypeId = Long.MIN_VALUE
    private var lastPausedId = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        startInForeground(NotificationHelper.placeholderNotification(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob == null) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private fun startInForeground(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.NOTIFICATION_RUNNING_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_RUNNING_ID, notification)
            }
        } catch (_: Exception) {
            // 通知权限缺失 / 系统限制时降级：服务仍可运行
        }
    }

    private suspend fun runLoop() {
        val container = appContainer
        while (scope.isActive) {
            val record = runCatching { container.repository.activeNow() }.getOrNull()
            if (record == null) {
                val pausedId = container.settings.pausedRecordId.value
                if (pausedId == null) {
                    stopSelfSafely()
                    return
                }
                if (pausedId != lastPausedId) {
                    val pausedRecord = runCatching { container.repository.recordById(pausedId) }.getOrNull()
                    if (pausedRecord == null || pausedRecord.endAt == null) {
                        container.settings.setPausedRecordId(null)
                        stopSelfSafely()
                        return
                    }
                    val type = runCatching { container.repository.typeById(pausedRecord.typeId) }.getOrNull()
                    val parentName = type?.parentId?.let {
                        runCatching { container.repository.typeById(it)?.name }.getOrNull()
                    }
                    val notification = NotificationHelper.runningNotification(
                        this, type, parentName, pausedRecord, paused = true,
                    )
                    startInForeground(notification)
                    lastPausedId = pausedId
                    runCatching { container.widgetUpdater.updateAll() }
                }
                delay(1_000L)
                continue
            }

            lastPausedId = Long.MIN_VALUE
            val type = runCatching { container.repository.typeById(record.typeId) }.getOrNull()
            val parentName = type?.parentId?.let {
                runCatching { container.repository.typeById(it)?.name }.getOrNull()
            }
            val minute = (System.currentTimeMillis() - record.startAt) / 60_000L
            if (minute != lastMinute || record.typeId != lastTypeId) {
                lastMinute = minute
                lastTypeId = record.typeId
                val notification = NotificationHelper.runningNotification(
                    this, type, parentName, record, paused = false,
                )
                startInForeground(notification)
                runCatching { container.widgetUpdater.updateAll() }
                WidgetAlarm.schedule(this)
            }
            delay(1_000L)
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TimerService::class.java),
                )
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TimerService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

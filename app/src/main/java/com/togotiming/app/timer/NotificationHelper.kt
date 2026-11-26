package com.togotiming.app.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.togotiming.app.MainActivity
import com.togotiming.app.R
import com.togotiming.app.data.ActivityTypeEntity
import com.togotiming.app.data.TimeRecordEntity

object NotificationHelper {

    const val CHANNEL_RUNNING = "timing_running_v4"
    const val NOTIFICATION_RUNNING_ID = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_RUNNING,
            "计时进行中",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "计时期间在通知栏/锁屏显示当前活动与已用时长"
            setShowBadge(false)
            enableVibration(false)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
        // 旧版通道不再使用
        listOf("timing_running", "timing_running_v2", "timing_running_v3").forEach {
            runCatching { nm.deleteNotificationChannel(it) }
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        10,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun toggleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        11,
        Intent(context, TimerActionReceiver::class.java)
            .setAction(TimerActionReceiver.ACTION_TOGGLE_PAUSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        12,
        Intent(context, TimerActionReceiver::class.java).setAction(TimerActionReceiver.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun runningNotification(
        context: Context,
        type: ActivityTypeEntity?,
        parentName: String?,
        record: TimeRecordEntity,
        paused: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Notification {
        val elapsed = ((if (paused) (record.endAt ?: now) else now) - record.startAt).coerceAtLeast(0L)
        val leaf = type?.name ?: "计时"
        val suffix = if (parentName.isNullOrBlank()) "" else " · $parentName"
        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("【$leaf】$suffix".trim())
            .setContentText(
                if (paused) "已暂停 ${formatCn(elapsed)} · 点击继续" else "计时中 · 点击暂停"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(toggleIntent(context))
            .addAction(0, if (paused) "继续" else "暂停", toggleIntent(context))
            .addAction(0, "结束", stopIntent(context))
            .addAction(0, "打开", openAppIntent(context))
        type?.let { builder.setColor(it.colorArgb) }

        if (paused) {
            builder.setWhen(System.currentTimeMillis() - elapsed)
            builder.setUsesChronometer(false)
        } else {
            builder.setWhen(record.startAt)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(false)
        }
        return builder.build()
    }

    fun placeholderNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Timing")
            .setContentText("正在恢复计时…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun notifyRunning(
        context: Context,
        type: ActivityTypeEntity?,
        parentName: String?,
        record: TimeRecordEntity,
        paused: Boolean,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(
                NOTIFICATION_RUNNING_ID,
                runningNotification(context, type, parentName, record, paused),
            )
        } catch (_: SecurityException) {
            // 通知权限被拒时静默降级，计时仍以数据库为准
        }
    }

    fun formatCn(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }
}

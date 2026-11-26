package com.togotiming.app.util

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限状态查询 + 「跳系统设置」工具（行动指南 §8.5）。
 *
 * 约定：权限只做引导，不阻塞主流程；所有跳转都做兜底（ActivityNotFound / SecurityException），
 * 绝不把异常抛给 Compose UI。
 */
object PermissionUtils {

    /** Android 12（精确闹钟 / 悬浮窗相关 API 起点） */
    const val API_S = 31

    /** Android 13（POST_NOTIFICATIONS 运行时权限起点） */
    const val API_TIRAMISU = 33

    fun notificationsGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** API < 31 时无精确闹钟开关，视为已授予；API 31+ 查询 canScheduleExactAlarms。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < API_S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun ignoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 系统 NFC 开关状态（NFC 是普通权限，无运行时请求）。 */
    fun nfcEnabled(context: Context): Boolean = try {
        NfcAdapter.getDefaultAdapter(context)?.isEnabled == true
    } catch (e: Exception) {
        false
    }

    fun hasNfcHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

    /** specialUse 前台服务类型只在清单声明，没有运行时权限可查。 */
    fun specialUseDeclared(): Boolean = true

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < API_TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun openAppDetails(context: Context) {
        startSafely(
            context,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!startSafely(context, intent)) openAppDetails(context)
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= API_S) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            )
            if (startSafely(context, intent)) return
        }
        openAppDetails(context)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        if (!startSafely(context, intent)) openAppDetails(context)
    }

    /** 请求电池优化白名单；部分 ROM 不支持直接请求，降级到白名单列表页，再失败则打开应用详情。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (startSafely(context, direct)) return
        if (startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetails(context)
    }

    fun openNfcSettings(context: Context) {
        if (!startSafely(context, Intent(Settings.ACTION_NFC_SETTINGS))) openAppDetails(context)
    }

    /** 打开系统页面；失败返回 false 由调用方兜底。 */
    fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}

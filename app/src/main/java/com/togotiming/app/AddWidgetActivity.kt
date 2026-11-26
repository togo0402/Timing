package com.togotiming.app

import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * 桌面长按图标 → 快捷方式 → 添加对应小部件。
 * HyperOS 桌面会检查 MIUI 的「桌面快捷方式」AppOp；未授权时系统会静默失败，
 * 因此这里做三层兜底：requestPinAppWidget → MIUI INSTALL_WIDGET 广播 → 引导授权/手动添加。
 */
class AddWidgetActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = intent.getStringExtra(EXTRA_WIDGET) ?: "timer"
        val providerClass = when (which) {
            "ring" -> "com.togotiming.app.widget.RingWidgetProvider"
            "list" -> "com.togotiming.app.widget.ListWidgetProvider"
            else -> "com.togotiming.app.widget.TimerWidgetProvider"
        }
        val provider = ComponentName(packageName, providerClass)
        val manager = AppWidgetManager.getInstance(this)
        val before = manager.getAppWidgetIds(provider).size

        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.isRequestPinAppWidgetSupported
        if (supported) {
            val callback = PendingIntent.getBroadcast(
                this,
                200,
                Intent(this, WidgetPinResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { manager.requestPinAppWidget(provider, null, callback) }
        }

        // 1.5s 后仍没有新实例：改走 MIUI 桌面 INSTALL_WIDGET 广播
        handler.postDelayed({
            if (count(manager, provider) <= before) {
                sendMiiuInstall(provider)
                // 再等 1.5s：仍失败则引导用户开启 MiUI「桌面快捷方式」权限
                handler.postDelayed({
                    if (count(manager, provider) <= before) {
                        showPermissionGuide()
                    } else {
                        finish()
                    }
                }, 1_500L)
            } else {
                finish()
            }
        }, 1_500L)
    }

    private fun count(manager: AppWidgetManager, provider: ComponentName): Int =
        runCatching { manager.getAppWidgetIds(provider).size }.getOrDefault(0)

    private fun sendMiiuInstall(provider: ComponentName) {
        val miui = Intent("com.miui.home.launcher.action.INSTALL_WIDGET").apply {
            component = ComponentName(
                "com.miui.home",
                "com.miui.home.launcher.InstallWidgetReceiver",
            )
            putExtra("miui.intent.extra.provider_component_name", provider)
            putExtra(
                "miui.intent.extra.result_receiver_component_name",
                ComponentName(packageName, WidgetPinResultReceiver::class.java.name),
            )
            putExtra("miui.intent.extra.USER_ID", 0)
        }
        runCatching { sendBroadcast(miui) }
    }

    private fun showPermissionGuide() {
        AlertDialog.Builder(this)
            .setTitle("还差一个桌面权限")
            .setMessage(
                "HyperOS 需要「桌面快捷方式」权限才能一键添加小部件。\n\n" +
                    "开启后重试，或长按桌面空白处 → 小部件 → Timing 手动添加。"
            )
            .setPositiveButton("去开启") { _, _ ->
                openShortcutPermission()
                finish()
            }
            .setNeutralButton("手动添加") { _, _ ->
                Toast.makeText(
                    this,
                    "长按桌面空白处 → 小部件 → Timing → 选择「计时器 / 今日占比 / 今日汇总」",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
            .setNegativeButton("关闭") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openShortcutPermission() {
        // MIUI 权限编辑页；失败则退到应用详情页
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            putExtra("extra_pkgname", packageName)
        }
        val ok = runCatching { startActivity(miui) }.isSuccess
        if (!ok) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WIDGET = "widget"
    }
}

/** requestPinAppWidget 成功回调 */
class WidgetPinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(context, "小部件已添加到桌面", Toast.LENGTH_SHORT).show()
    }
}

package com.togotiming.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.togotiming.app.R
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.components.GlassPill
import com.togotiming.app.ui.theme.TimingColors
import com.togotiming.app.util.PermissionUtils

/**
 * 权限引导页 v1.1（Fluid Glass）：逐项显示「已授予 / 未授予」彩色 pill + 中文说明 + 玻璃按钮跳设置。
 *
 * - 背景沿用根节点的 AppBackground，本页不铺底色；
 * - 标题透明，返回按钮为玻璃圆钮；权限项统一 GlassCard；
 * - 深色模式颜色全部来自 MaterialTheme / TimingColors；
 * - 不阻塞主流程，从系统设置页返回（ON_RESUME）时自动刷新每项状态。
 */
@Composable
fun PermissionsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    // onResume 刷新：跳系统设置回来后状态自动更新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        val lifecycle: Lifecycle? = lifecycleOwner?.lifecycle
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshKey++
        vm.toast(
            context.getString(
                if (granted) R.string.perm_notification_granted else R.string.perm_notification_denied,
            ),
        )
    }

    val notifications = remember(refreshKey) { PermissionUtils.notificationsGranted(context) }
    val exactAlarm = remember(refreshKey) { PermissionUtils.canScheduleExactAlarms(context) }
    val overlay = remember(refreshKey) { PermissionUtils.canDrawOverlays(context) }
    val battery = remember(refreshKey) { PermissionUtils.ignoringBatteryOptimizations(context) }
    val nfcHardware = remember { PermissionUtils.hasNfcHardware(context) }
    val nfc = remember(refreshKey) { PermissionUtils.nfcEnabled(context) }
    val specialUse = PermissionUtils.specialUseDeclared()

    Column(modifier = Modifier.fillMaxSize()) {
        PermissionHeader()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.perm_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            PermissionCard(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.perm_notifications_title),
                description = stringResource(R.string.perm_notifications_desc),
                statusOk = notifications,
                statusText = stringResource(if (notifications) R.string.perm_granted else R.string.perm_denied),
                actionLabel = stringResource(
                    if (Build.VERSION.SDK_INT >= PermissionUtils.API_TIRAMISU && !notifications) {
                        R.string.perm_action_request
                    } else {
                        R.string.perm_action_settings
                    },
                ),
                onAction = {
                    if (Build.VERSION.SDK_INT >= PermissionUtils.API_TIRAMISU &&
                        !PermissionUtils.hasPostNotificationsPermission(context)
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        PermissionUtils.openNotificationSettings(context)
                    }
                },
            )

            PermissionCard(
                icon = Icons.Filled.Alarm,
                title = stringResource(R.string.perm_exact_alarm_title),
                description = stringResource(R.string.perm_exact_alarm_desc),
                statusOk = exactAlarm,
                statusText = stringResource(if (exactAlarm) R.string.perm_granted else R.string.perm_denied),
                actionLabel = stringResource(R.string.perm_action_settings),
                onAction = { PermissionUtils.openExactAlarmSettings(context) },
            )

            PermissionCard(
                icon = Icons.Filled.Layers,
                title = stringResource(R.string.perm_overlay_title),
                description = stringResource(R.string.perm_overlay_desc),
                statusOk = overlay,
                statusText = stringResource(if (overlay) R.string.perm_granted else R.string.perm_denied),
                actionLabel = stringResource(R.string.perm_action_settings),
                onAction = { PermissionUtils.openOverlaySettings(context) },
            )

            PermissionCard(
                icon = Icons.Filled.BatterySaver,
                title = stringResource(R.string.perm_battery_title),
                description = stringResource(R.string.perm_battery_desc),
                statusOk = battery,
                statusText = stringResource(if (battery) R.string.perm_granted else R.string.perm_denied),
                actionLabel = stringResource(R.string.perm_action_settings),
                onAction = { PermissionUtils.requestIgnoreBatteryOptimizations(context) },
            )

            PermissionCard(
                icon = Icons.Filled.Nfc,
                title = stringResource(R.string.perm_nfc_title),
                description = stringResource(
                    if (nfcHardware) R.string.perm_nfc_desc else R.string.perm_nfc_no_hardware,
                ),
                statusOk = nfcHardware && nfc,
                statusText = stringResource(
                    if (nfcHardware && nfc) R.string.perm_granted else R.string.perm_denied,
                ),
                actionLabel = stringResource(R.string.perm_action_settings),
                onAction = { PermissionUtils.openNfcSettings(context) },
            )

            PermissionCard(
                icon = Icons.Filled.Bolt,
                title = stringResource(R.string.perm_fgs_title),
                description = stringResource(R.string.perm_fgs_desc),
                statusOk = specialUse,
                statusText = stringResource(R.string.perm_declared),
                actionLabel = null,
                onAction = null,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 页面头：玻璃圆形返回钮 + 透明标题（no 底色） */
@Composable
private fun PermissionHeader() {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PermissionGlassCircleButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            onClick = { backDispatcher?.onBackPressed() },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.perm_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** 单个权限：GlassCard + 图标底座 + 彩色状态 pill + 玻璃跳转按钮 */
@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    statusOk: Boolean,
    statusText: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val statusColor = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PermissionIconBadge(icon)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                GlassPill(
                    text = statusText,
                    color = statusColor,
                    container = statusColor.copy(alpha = if (dark) 0.20f else 0.13f),
                )
            }
            if (onAction != null && actionLabel != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    PermissionGlassButton(text = actionLabel, onClick = onAction)
                }
            }
        }
    }
}

/** 图标底座：主题色低透明圆角底 + 主题色图标（深色自适应） */
@Composable
private fun PermissionIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 玻璃胶囊按钮（去设置 / 允许） */
@Composable
private fun PermissionGlassButton(text: String, onClick: () -> Unit) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(shape)
            .background(if (dark) TimingColors.DarkGlass else TimingColors.LightGlass)
            .border(
                width = 1.dp,
                color = if (dark) TimingColors.DarkBorder else TimingColors.LightBorder,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 玻璃圆形图标按钮（返回） */
@Composable
private fun PermissionGlassCircleButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val dark = TimingColors.isDark(MaterialTheme.colorScheme.background)
    val shape = CircleShape
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(40.dp)
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

package com.togotiming.app.ui.screens

import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.togotiming.app.R
import com.togotiming.app.data.ThemeMode
import com.togotiming.app.ui.MainViewModel
import com.togotiming.app.ui.Routes
import com.togotiming.app.ui.components.GlassCard
import com.togotiming.app.ui.components.GlassSegmented
import com.togotiming.app.ui.components.SectionTitle
import com.togotiming.app.util.FontManager
import com.togotiming.app.util.PermissionUtils
import kotlinx.coroutines.launch

/** 主题模式与 GlassSegmented 下标一一对应 */
private val ThemeModes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)

/**
 * 设置页（v1.1 Fluid Glass）：外观（主题模式 + 字体）、NFC 与计时、数据、权限与保活、关于五组。
 *
 * 深色模式下所有颜色都来自 MaterialTheme / TimingColors，玻璃卡片由 GlassCard 自行适配。
 * 字体导入（SAF 选择 → FontManager 校验入库 → 预览 → 清除）沿用 v1.0 逻辑。
 */
@Composable
fun SettingsScreen(vm: MainViewModel, navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nfcEnabled by vm.nfcEnabled.collectAsStateWithLifecycle()
    val fontPath by vm.fontPath.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    var importingFont by remember { mutableStateOf(false) }

    val currentFontPath = fontPath
    val previewTypeface = remember(currentFontPath) { FontManager.load(currentFontPath) }
    val previewText = stringResource(R.string.settings_font_preview)
    val previewColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importingFont = true
                val result = vm.importFont(uri)
                importingFont = false
                result.fold(
                    onSuccess = {
                        vm.toast(context.getString(R.string.settings_font_import_ok))
                    },
                    onFailure = { error ->
                        vm.toast(
                            context.getString(
                                R.string.settings_font_import_fail,
                                error.message ?: "未知错误",
                            ),
                        )
                    },
                )
            }
        }
    }

    val themeLabels = listOf(
        stringResource(R.string.settings2_theme_system),
        stringResource(R.string.settings2_theme_light),
        stringResource(R.string.settings2_theme_dark),
    )
    val themeIndex = ThemeModes.indexOf(themeMode).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 2.dp),
        )
        Text(
            text = stringResource(R.string.settings2_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------- 外观 ----------------
            SettingsGroup(title = stringResource(R.string.settings2_section_appearance)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingIcon(Icons.Filled.Palette)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings2_theme_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.settings2_theme_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                GlassSegmented(
                    options = themeLabels,
                    selectedIndex = themeIndex,
                    onSelect = { index -> vm.setThemeMode(ThemeModes[index]) },
                    modifier = Modifier.fillMaxWidth(),
                )

                SettingsDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingIcon(Icons.Filled.FontDownload)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_font_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.settings_font_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = currentFontPath?.let { path ->
                        stringResource(R.string.settings_font_current, path.substringAfterLast('/'))
                    } ?: stringResource(R.string.settings_font_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (currentFontPath != null) {
                    Spacer(Modifier.height(8.dp))
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { ctx ->
                            TextView(ctx).apply { textSize = 18f }
                        },
                        update = { view ->
                            view.typeface = previewTypeface
                            view.text = previewText
                            view.setTextColor(previewColor)
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            fontPicker.launch(
                                arrayOf(
                                    "font/*",
                                    "application/x-font-ttf",
                                    "application/x-font-opentype",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        enabled = !importingFont,
                    ) {
                        Text(
                            stringResource(
                                if (importingFont) R.string.settings_font_importing else R.string.settings_font_pick,
                            ),
                        )
                    }
                    if (currentFontPath != null) {
                        TextButton(
                            onClick = {
                                vm.clearFont()
                                vm.toast(context.getString(R.string.settings_font_cleared))
                            },
                        ) {
                            Text(stringResource(R.string.settings_font_clear))
                        }
                    }
                }
            }

            // ---------------- NFC 与计时 ----------------
            SettingsGroup(title = stringResource(R.string.settings2_section_nfc)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingIcon(Icons.Filled.Nfc)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_nfc_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.settings_nfc_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = nfcEnabled,
                        onCheckedChange = { vm.setNfcEnabled(it) },
                    )
                }

                SettingsDivider()

                SettingsNavRow(
                    icon = Icons.Filled.Nfc,
                    title = stringResource(R.string.settings2_nfc_bind_title),
                    description = stringResource(R.string.settings2_nfc_bind_desc),
                    onClick = {
                        navController.navigate(Routes.NFC) { launchSingleTop = true }
                    },
                )

                SettingsDivider()

                Row(modifier = Modifier.fillMaxWidth()) {
                    SettingIcon(Icons.Filled.Notifications)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings2_fgs_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings2_fgs_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------- 数据 ----------------
            SettingsGroup(title = stringResource(R.string.settings2_section_data)) {
                SettingsNavRow(
                    icon = Icons.Filled.ImportExport,
                    title = stringResource(R.string.settings_import_export_title),
                    description = stringResource(R.string.settings_import_export_desc),
                    onClick = {
                        navController.navigate(Routes.IMPORT_EXPORT) { launchSingleTop = true }
                    },
                )

                SettingsDivider()

                SettingsNavRow(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.settings_record_io_title),
                    description = stringResource(R.string.settings_record_io_desc),
                    onClick = {
                        navController.navigate(Routes.RECORD_IO) { launchSingleTop = true }
                    },
                )
            }

            // ---------------- 权限与保活 ----------------
            SettingsGroup(title = stringResource(R.string.settings2_section_permission)) {
                SettingsNavRow(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.settings_permission_title),
                    description = stringResource(R.string.settings_permission_desc),
                    onClick = {
                        navController.navigate(Routes.PERMISSIONS) { launchSingleTop = true }
                    },
                )

                SettingsDivider()

                Row(modifier = Modifier.fillMaxWidth()) {
                    SettingIcon(Icons.Filled.BatterySaver)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_keepalive_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_keepalive_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { PermissionUtils.openAppDetails(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_keepalive_open))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_keepalive_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 关于 ----------------
            SettingsGroup(title = stringResource(R.string.settings2_section_about)) {
                SettingsNavRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.settings2_about_entry),
                    description = stringResource(R.string.settings2_about_desc),
                    onClick = {
                        navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                    },
                )
            }

            Text(
                text = stringResource(R.string.settings2_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 2.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一组设置：Group 标题 + 玻璃卡片；卡片内文字统一走 onSurface（GlassCard 自身不提供 contentColor）。 */
@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = title, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            GlassCard(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** 统一的小图标底座：主题色低透明圆角底 + 主题色图标，浅色/深色自适应。 */
@Composable
private fun SettingIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
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

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}

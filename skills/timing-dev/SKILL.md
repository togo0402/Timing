---
name: timing-dev
description: 继续开发 Timing（com.togotiming.app）Android 计时器时的工具链、架构、构建/安装/截图命令、NFC/小部件/Room 约束与发布流程。
---

# Timing 开发指导（AI Skill）

用于后续任何 AI/开发者继续迭代 `D:\ai own\Timing`。先读本文件，再读 `README.md`、`docs/ARCHITECTURE.md`、`docs/DEVELOPMENT.md`、`docs/NFC.md`。

## 0. 项目速览

- Android 计时器，Kotlin 2.1.21 + Compose BOM 2025.06.01 + Material3 1.3.2 + Room 2.7.2（KSP）。
- 包名固定 `com.togotiming.app`；minSdk 26 / target 36；arm64-v8a；**无 INTERNET 权限**。
- 设计语言 Fluid Glass：`ui/components/Glass.kt` + `ui/theme/Theme.kt`；页面不铺底色，背景由 `AppNav` 的 `AppBackground` 提供。
- 核心：分类树 + 计时（开始/暂停/继续/结束）+ 统计 + NFC + 三个桌面小部件 + 分类/记录导入导出。

## 1. 工具链（本机）

```
JAVA_HOME=D:\AndroidDev\jdk17
SDK=D:\AndroidDev\sdk（platform-36, build-tools 36.0.0）
adb=D:\AndroidDev\sdk\platform-tools\adb.exe
```

构建：

```powershell
$env:JAVA_HOME='D:\AndroidDev\jdk17'
Set-Location 'D:\ai own\Timing'
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

安装/截图：

```powershell
$adb='D:\AndroidDev\sdk\platform-tools\adb.exe'
& $adb install -r 'D:\ai own\Timing\dist\Timing-<ver>-release.apk'
& $adb shell am start -n com.togotiming.app/.MainActivity
& $adb shell input keyevent KEYCODE_WAKEUP
& $adb shell wm dismiss-keyguard
& $adb shell screencap -p /sdcard/x.png
& $adb pull /sdcard/x.png 'D:\ai own\Timing\dist\x.png'
& $adb shell rm /sdcard/x.png
```

设备 1440×3200；`input tap/swipe` 用原始像素坐标，不要用预览图坐标。

## 2. 代码地图

```
data/       Room 实体/DAO/迁移 + TimingRepository + SettingsStore/ThemePreferences
domain/     TimeMath、StatsAggregator、ColorPalette、NfcToggle、ImportExport、RecordsImportExport
timer/      TimerService(FGS)、NotificationHelper、TimerRuntime(controller/receivers/alarm)
nfc/        NfcController（ReaderMode、NDEF 写入、三种 intent 分发）
widget/     W1 计时卡、W2 24h 环、W3 GridView 磁贴，RemoteViews/Canvas
ui/         AppNav、MainViewModel、Glass 组件、Theme、screens/*
```

关键事实：

- 计时记录 `endAt=null` 表示进行中；暂停 = `endAt=now` + `SettingsStore.pausedRecordId`；继续 = `startAt += now-endAt`。
- 分类软删除 `archived=1`；`path` 用于导入导出/移动。
- 数据库 version 2；新增字段必须写 `Migration`，禁止 destructive fallback。
- 小部件 W1 用真实 `Chronometer`；W2 弧段平头、仅进行中前进端圆头；W3 文字 ellipsize。
- 导航 tab 切换 `popUpTo(start)+launchSingleTop`；NavHost 转场 `None`；`enableOnBackInvokedCallback=false`。

## 3. 约束（不要违反）

- 不加 INTERNET 权限/联网功能。
- 不改 `applicationIdSuffix`（NFC AAR 包名固定）。
- 不提交 `keystore/`、`keystore.properties`、`local.properties`、`_local/`、构建产物。
- 删 `app/build` 前先 `.\gradlew.bat --stop`。
- 版本发布：`versionCode` 递增，复制 APK 到 `dist/Timing-<versionName>-release.apk`，更新 `dist/BUILD-INFO.txt` 和 README 的 SHA-256。
- 大改分文件给子代理，每个只改自己范围；Lead 统一 Gradle 与真机验证。

## 4. NFC 模型（重要）

| 卡 | 前台 | 后台 |
| --- | --- | --- |
| NTAG213/215/216 可写 | UID | 绑定时写 `timing://nfc/toggle?uid=… + AAR` → **静默拉起** |
| 只读外来 AAR（时间日志写过） | UID | 原 AAR 生效，Timing 无法覆盖 |
| 无 NDEF（身份证/手环/门禁） | UID | `TECH_DISCOVERED`，系统弹「打开应用处理标签」确认；无“始终” |

- 息屏不监听 NFC（Android）。
- 不要把“非空白卡后台静默”当 bug；这是系统限制，官方文档同样承认。
- 绑定时诊断：`NfcController.lastTagInfo`（可写/只读/无 NDEF）。
- 推荐测试硬件：NTAG215 白卡/贴纸。

## 5. 设计规范

- 颜色只用 `MaterialTheme.colorScheme` + `TimingColors`；禁止硬编码浅色。
- 卡片用 `GlassCard`，按钮用玻璃圆钮/渐变胶囊；标题透明。
- 页面水平 padding 约 16–18dp；圆角 20–28dp；阴影轻。
- 深色模式必须检查；状态栏图标由 `MainActivity` SideEffect 控制。

## 6. 调试流程

1. 改数据层 → 编译 → 单测。
2. 涉及 UI：安装 release（或 debug 但先卸载 release，签名不同）→ 截图确认浅色/深色。
3. NFC/小部件只能真机测；NFC 需要 NTAG。
4. 发布前跑 `:app:testDebugUnitTest` + `:app:assembleRelease`，更新文档。

## 7. 常见坑

- HyperOS 小部件需「桌面快捷方式」AppOp；失败时 App 有权限引导。
- 侧滑叠影：NavHost 转场必须 None，且关闭 predictive back。
- 截图黑屏：先唤醒/解锁。
- 安装 release 覆盖 debug 会签名冲突：先卸载 debug。
- Release 被 R8 混淆：新增 kotlinx.serialization 类通常已有规则；若崩溃检查 keep。
- 本机 GitHub：Steam++ 改 hosts，`gh` 联网卡死，`curl` 证书错误；用 `git` + 浏览器建空仓库，见 `D:\ai own\GitHub 提交范本.md`。

## 8. 参考文档

- `README.md`：功能、已知限制、安装。
- `docs/ARCHITECTURE.md`：分层/数据模型/状态机。
- `docs/DEVELOPMENT.md`：命令、迁移、发布清单。
- `docs/NFC.md`：分发机制与卡型。
- `docs/CHANGELOG.md`：版本历史。
- `docs/TOOLS.md`：工具与子代理分工。
- `design/`：设计稿与预览。

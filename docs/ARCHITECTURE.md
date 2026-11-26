# Timing 架构说明

包名：`com.togotiming.app`　语言：Kotlin + Jetpack Compose　最低 API 26 / target 36

## 1. 分层

```
app/src/main/java/com/togotiming/app/
├─ TimingApp.kt / AppContainer.kt      Application 与依赖装配
├─ MainActivity.kt                     单 Activity + Compose，主题、通知权限、NFC 生命周期
├─ data/                               Room + DataStore + Repository
│  ├─ Entities.kt                      ActivityType / TimeRecord / NfcBinding
│  ├─ Daos.kt                          ActivityTypeDao / TimeRecordDao / NfcBindingDao
│  ├─ AppDatabase.kt                   version 2，MIGRATION_1_2
│  ├─ SettingsStore.kt                 DataStore：NFC 开关、暂停记录 id、小部件设置
│  ├─ ThemePreferences.kt              主题模式
│  └─ TimingRepository.kt              所有业务事务入口
├─ domain/                             纯 Kotlin 逻辑（可单测）
│  ├─ TimeMath.kt                      日/周/月边界、格式化、时区
│  ├─ Stats.kt                         StatsAggregator：根聚合、子聚合、未统计
│  ├─ ImportExport.kt                  分类树 JSON 编解码
│  ├─ RecordsImportExport.kt           记录 JSON 编解码（v1.3）
│  ├─ ColorPalette.kt                  根/子颜色派生
│  └─ NfcToggle.kt                     UID 归一化、5s 防抖、切换决策
├─ timer/                              TimerService / NotificationHelper / TimerRuntime
├─ nfc/NfcController.kt                ReaderMode + NDEF 写入 + intent 分发
├─ widget/                             W1/W2/W3 RemoteViews 与 Canvas
├─ ui/
│  ├─ AppNav.kt                        Scaffold + NavHost + 底部导航
│  ├─ MainViewModel.kt                 唯一 VM，暴露 Flow 与操作
│  ├─ components/Glass.kt              Fluid Glass 设计系统
│  ├─ theme/Theme.kt                   TimingColors、深浅色 scheme
│  └─ screens/                         各页面
└─ util/                               FontManager、PermissionUtils
```

## 2. 数据模型

`activity_types`（分类树，软删除）：

- `id`, `parentId`, `name`, `colorArgb`, `icon`, `depth`, `path`, `sortOrder`, `archived`, `targetMinutes`, `colorSeed`
- `path` 用 `/` 连接，导入导出/移动时维护；`archived=1` 表示已删除但历史记录保留。

`time_records`：

- `id`, `typeId`, `startAt`, `endAt`（`null` = 进行中）, `comment`, `source`, `targetMinutes`（可空，回退分类目标）

`nfc_bindings`：

- `id`, `tagUid`（大写 HEX，无冒号）, `typeId`, `enabled`

数据库 version 2；`MIGRATION_1_2` 增加 `targetMinutes` 两列。新增字段必须写迁移，不能 destructive fallback。

## 3. 计时状态机

- 开始：`repository.start(typeId, source, targetMinutes)` 插入 `endAt=null` 的记录；`TimerService.start` + `WidgetAlarm` + 小部件刷新。
- 暂停：`pauseRecord` 把 `endAt=now`，并把记录 id 写入 `SettingsStore.pausedRecordId`；服务通知切换「已暂停」。
- 继续：`resumeRecord` 把 `startAt += now - endAt`，`endAt=null`，清 `pausedRecordId`（暂停时间不计入时长）。
- 结束：`stop` 结束所有 active；清 paused id。
- 通知：标准系统通知 + `setUsesChronometer(true)`；channel `timing_running_v4`（HIGH）；动作 暂停/继续、结束、打开。
- 前台服务：`TimerService`（specialUse），仅计时期间运行；`START_STICKY`/重启恢复。

## 4. 小部件

| 部件 | 实现 |
| --- | --- |
| W1 计时卡 | Canvas 位图背景 + 真实 RemoteViews `Chronometer`；进度环目标 = record/type.targetMinutes，clamp 0–100% |
| W2 今日 24h 环 | Canvas 绘制 24h 底轨 + 事件弧段；平头弧；仅进行中事件的前进端画同色圆头 |
| W3 今日汇总 | `GridView` + `RemoteViewsService`/Factory，一级分类磁贴；Canvas 绘制，文字 ellipsize |

刷新：`WidgetUpdater.updateAll()`；`WidgetAlarm` 周期刷新；主 App 操作后立即刷新。

## 5. NFC

- 前台：`MainActivity.onResume` → `NfcController.enableReaderMode`，flags：NFC_A/B/F/V + `SKIP_NDEF_CHECK`；5s 同 UID 防抖。
- 后台：
  - NDEF 可写卡：绑定/写入时写 `timing://nfc/toggle?uid=<HEX>` + AAR → 系统直接拉起（静默）。
  - 无 NDEF 卡（身份证/手环/门禁）：manifest 注册 `TECH_DISCOVERED` + `res/xml/nfc_tech_filter.xml`，系统会询问是否打开（Android 限制）。
- `NfcController.handleIntent` 同时处理 `NDEF_DISCOVERED` / `TECH_DISCOVERED` / `TAG_DISCOVERED`，从 `EXTRA_TAG` 取 UID。
- 绑定页写卡：`Ndef` 可写则整条覆盖；`NdefFormatable` 则 format；只读提示换 NTAG。

## 6. 导航与主题

- 单 Activity，底部 5 tab：计时 / 统计 / 分类 / NFC / 设置。
- 二级路由：`permissions`、`about`、`import_export`（分类）、`record_io`（记录）。
- tab 切换 `popUpTo(start)` + `launchSingleTop`，无 save/restore；NavHost 转场全 `None`；`enableOnBackInvokedCallback=false` 以避免侧滑叠影。
- Fluid Glass：`AppBackground` 根背景；`GlassCard`/`GlassPill`/`GlassSegmented`/`SectionTitle`/`RingProgress`；所有页面不铺底色。
- 深色通过 `TimingColors.isDark(background)` + `MaterialTheme`，禁止硬编码浅色。

## 7. 导入导出

- 分类：`ImportExport`（version 1，`activities` 扁平节点）。
- 记录：`RecordsImportExport`（v1.3，`kind=timing_records`，`from/to/types/records`）。
  - 导出按时间范围裁剪已结束记录，`typePath` 引用分类。
  - 导入按 path 匹配/补建分类，同 typeId+startAt+endAt 去重。

## 8. 测试

`app/src/test/java/com/togotiming/app/domain/`：

- `TimeMathTest`、`StatsAggregatorTest`、`ColorPaletteTest`、`NfcToggleTest`、`ImportExportTest`、`RecordsImportExportTest`

命令：`.\gradlew.bat :app:testDebugUnitTest`

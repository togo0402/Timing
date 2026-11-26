
# 任务：从零开发 Android 时间记录 App「Timing」

> 项目名：**Timing**
> 包名：**`com.togotiming.app`**（现在定死，写进 AAR 后不可改；debug 不加 `applicationIdSuffix`）
> 目标设备：Android（ HyperOS）手机，arm64
> 作者信息（显示在 App「关于」页）：**togo0402@github**、**DeepSeek**

## 0. 背景（已调研，不要重复分析）

- 参考产品「时间日志」v1.8.3（包名 `me.liujia95.solitude`），加了 360 加固（`com.stub.StubApp` + `assets/libjiagu*.so`），真实业务代码加密。
  → **结论：不要尝试改它 / 脱壳 / 重打包。这条路走不通。**
- 另一个开源同类 `Simple Time Tracker`（`com.razeeman.util.simpletimetracker`）**不作为代码基底**，只可参考数据模型思路。
- **这是一个全新项目，不是二次开发。**

## 1. 目标与非目标

### P0（必须先跑通，按里程碑交付）
1. 记录「活动类型」与「时间块」，支持开始/停止计时，杀进程可恢复。
2. 主界面按天的时间轴，块可拖动调整起止。
3. 统计页：今日/本周/本月，按活动聚合。
4. **NFC 碰触 toggle 计时**（绑叶子节点），含 5s 防抖与切换询问。
5. **三个桌面小部件**：
   - **W1 当前事件（2×2）**：显示当前事件名 + 已用时长；空闲显示上次事件或 `--`；点击=停止/打开 App。
   - **W2 今日占比环（2×2）**：24h 圆环，按叶子事件颜色填充已过时段；**仅颜色，无文字**；点击=打开统计页。
   - **W3 今日多事件（2×4 / 4×4）**：父级 → 叶子列表，行内显示今日累计时长；点击某行=开始/切换该叶子事件。
6. **分类嵌套（≤3 层）+ 颜色体系（12 色一级调色板 + HSL 子色递进 + 三联动颜色选择器）**。
7. **事件导入/导出（JSON）+ 字体导入（文件）**。
8. **权限声明 + 引导页一次性做全**（详见 §8.5）。
9. **「关于」页**显示应用名、版本号、包名、作者 `togo0402@github` 与 `DeepSeek`。

### P1（有余力再做）
10. 标签（Tag）、手动补记、CSV 导出、深色模式、通知「长时间无活动提醒」。
11. 小部件深度个性化（进度环样式、自定义字体应用到小部件、多套主题）。
12. NFC 桌面「从左到右滑动提示」——实现方式为**悬浮窗**（`SYSTEM_ALERT_WINDOW`），权限已在 P0 声明并引导；此动画效果 P1 实现。

### 明确不做（Non-goals）
- 云同步、账号、社交、**任何网络请求**（App 不申请 `INTERNET` 权限）。
- 自动识别 App 使用（`PACKAGE_USAGE_STATS`）。
- 日历/待办四象限。
- **不加载外部 dex/JAR、不执行任意代码**。「导入」= 声明式数据导入（JSON/字体文件）。
- 不修改/反编译「时间日志」APK。

## 2. 数据模型

```text
ActivityType:
  id: Long (PK)
  parentId: Long?              // null=一级节点
  name: String
  iconRes: String?             // 图标 key
  colorArgb: Int               // 最终展示色，ARGB
  colorSeed: Int?              // 若由父色派生，记录种子，便于"跟随父色变化"
  sortOrder: Int
  archived: Boolean
  depth: Int (0..2)            // 冗余，便于查询
  path: String                 // 冗余，"study/english/translate" 唯一，用于导入匹配

TimeRecord:
  id: Long (PK)
  typeId: Long (FK, 指向叶子)
  startAt: Long (ms UTC)
  endAt: Long?                 // null=进行中
  comment: String?
  source: Enum(manual, nfc, widget)

NfcBinding:
  id: Long (PK)
  tagUid: String (唯一索引)    // 完整十六进制，大写无分隔符，不截断、不假设长度
  typeId: Long (FK, 必须为叶子)
  enabled: Boolean
```

**硬约束：**
- 最多 3 层嵌套（一级→二级→三级），插入时校验。
- 同一时刻最多一条 `endAt IS NULL` 记录，用事务 `stopAllActiveThenStart(newTypeId)` 保证。
- 计时只落库，显示时长 = `now - startAt`，**内存不累加**。
- 删除 `ActivityType` 时弹出三选项对话框：「子节点提升为一级」/「连同子节点一起删除」/「取消」。删除方式 P0 用**归档**（`archived=true`），避免历史记录悬空；「连同子节点删除」= 子树全部归档。
- `NfcBinding.typeId` 只允许叶子节点。
- 时间用 `Long` 毫秒 UTC 存，展示转本地时区。

## 3. 功能细节

### 3.1 计时
- 主界面顶部大「开始/停止」区，显示当前事件名（叶子）和已用时长（秒级跳动，`now - startAt`）。
- 计时期间发**前台服务 + 常驻通知**（事件名 + 时长 + 点按停止的 PendingIntent），前台服务类型按 Android 14+ 要求声明 `specialUse` 并写 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`。
- 开始新计时：先 `stopAllActiveThenStart`。
- App 启动查 `endAt IS NULL`，恢复当前计时；若通知已被杀，启动时提示「上次计时 X 仍在进行，是否继续/结束」。

### 3.2 时间轴
- 纵向，0:00~24:00，块按起止绘制。**块显示叶子节点名称**，块颜色 = 叶子色。
- **手势**：长按块上/下缘进入拖拽后**消费手势事件**，与外层 `LazyColumn` 滚动区分；吸附 5 分钟；最小时长 5 分钟；上缘 < 下缘。
- 进行中记录**禁用下缘拖动**（避免与 stop 语义冲突），上缘可拖。
- 点击块 → 编辑（改父/叶子、改起止、写备注）。
- 点击空白 → 补记一段。
- **跨天记录**在时间轴上按本地日界拆成两段绘制。

### 3.3 统计
- 今日/本周/本月切换（**周起始=周一**）。
- **聚合口径**：主视图**以根节点为单位**展示总时长与占比（父节点 = 其自身 + 所有子孙叶子之和）；点击父节点下钻展开其子节点明细，再点叶子看时间块列表。
- **跨天事件按与查询区间的交集计算**。进行中记录统计到 `now`。
- 占比环形图：用 Canvas 画多段圆环，颜色使用对应根节点（或展开后叶子）的颜色。

### 3.4 NFC

**交互语义：**

- **同一标签 toggle**：碰一下 → 若绑定的叶子**正在计时**则结束；否则先结束当前计时（若有）再开始。
- **不同标签切换询问**：碰到的标签对应的叶子 **≠** 当前进行中的事件 → 弹询问「当前正在 [工作 01:23]，是否切换到 [学习]？」[切换] [保持当前]。
  - App 在前台：Material `AlertDialog`。
  - App 不在前台（AAR 拉起）：拉起 App 到前台后弹 `AlertDialog`（同一逻辑）。
- **防抖**：**同一 UID 在 5 秒内重复上报忽略**（ReaderMode 会连发多次 `onTagDiscovered`）。防抖窗口 5s；切换询问不叠加防抖。
- **提示**：所有触发以 **普通 Toast** 提示（「工作 开始」「工作 结束」「已切换到 学习」）。**P0 不做「从左到右滑动」动画**（普通 Toast 不支持动画）；P1 用悬浮窗实现。

**实现：**

- 前台用 `enableReaderMode()`，flags 覆盖 A/B/F/V；绑定页与计时页区分模式（绑定页只读 UID，不触发 toggle）。
- UID 统一**大写、无分隔符、完整十六进制**，做 key。
- 绑定页：靠近标签 → 显示 UID → 选叶子 → 保存；提供绑定列表（增删改、总开关）。
- **后台拉起 = NDEF URI + AAR**（这是原版「后台没运行也能拉起」的原理，不要再纠结）：
  - 标签用 NFC Tools 写：NDEF URI `timing://nfc/toggle?uid=<HEX>` + **AAR（包名 `com.togotiming.app`）**。
  - Manifest 声明 `NDEF_DISCOVERED` intent-filter，匹配 `scheme=timing, host=nfc`；主 Activity 处理 `onCreate` 与 `onNewIntent`，从 Intent 取 Tag UID。取不到 Tag 时按失败提示，不静默。
  - **AAR 包名必须等于 `applicationId`（`com.togotiming.app`）；debug 不加 `applicationIdSuffix`**，否则标签写一次就废。
- NFC 总开关关闭时，后台事件忽略并提示。
- 随机 UID 标签（部分 iPhone/部分 Mifare）不能绑定，需在绑定页提示。

**必须接受的系统限制：**
- 完全静默（不亮屏不跳 App）的 NFC toggle 做不到；P0 在绑定页文字说明「碰标签会亮屏并跳进 App」。

### 3.5 桌面小部件

尺寸自适应（`resizeMode`），支持至少 2x1 / 2x2 / 4x1 / 2x4。

**刷新机制（统一）：**
- 计时期间由**前台服务**每分钟 `updateAppWidget` 推送。
- 若前台服务被系统停 / 精确闹钟不可用 → **降级为 `AlarmManager.setAndAllowWhileIdle` 近似刷新（约 15 分钟一次）**，并在 README 写明。
- **时长显示永远按 `now - startAt` 计算**，不累加；刷新失败也不会丢时间。
- **W2 占比环**与 **W3 列表**刷新频率同上。

**W1 当前事件（2×2）**
- 布局：事件名（叶子）+ 已用时长 `HH:MM` + 色块背景（用当前事件色）。
- 空闲时：显示「上次：<叶子> <时长>」；今天无记录显示 `--`。
- 点击：有计时 → 停止（`BroadcastReceiver goAsync` 或 `Service`）；无计时 → 打开 App 选择事件。
- 只依赖 `RemoteViews` 的 TextView/ImageView。

**W2 今日占比环（2×2）**
- 用 Canvas 在 App 侧画一张 Bitmap，`setImageViewBitmap()` 塞入。
- 24h 圆环，已过时段按**叶子事件色**填充，进行中事件画到 `now`。
- **分母 = 本地时区当天 00:00~次日 00:00**（例：10 月 1 日 00:00 → 10 月 2 日 00:00）。
- 跨天事件按本地日界裁剪到当天部分。
- 段数 ≤ 8 直接展示；> 8 把最小的段合并为「其它」灰段（不影响主色段）。
- 无文字、无图例。点击 → 打开统计页。

**W3 今日多事件（2×4 / 4×4）**
- `AppWidgetProvider` + `RemoteViewsService`（Manifest 声明 `android:permission="android.permission.BIND_REMOTEVIEWS"`）。
- 数据：今天按**叶子**聚合的时长降序 Top N（N 随尺寸自适应，最小 4，最大 8）。
- 每行：色块 + 「父/叶子」文字 + `HH:MM` 时长；进行中的行加「·」后缀。
- 点击某行：开始该叶子事件（已有进行中的先 `stopAllActiveThenStart`）。
- 空状态显示「今天还没有记录」。

### 3.6 颜色体系（**必须按这套实现，不得自由发挥**）

**一级调色板（12 色，按顺序作为新一级事件的默认色，循环使用）：**
```text
#E53935  红
#FB8C00  橙
#FDD835  黄
#7CB342  青柠
#43A047  绿
#00ACC1  青
#1E88E5  蓝
#3949AB  靛
#8E24AA  紫
#D81B60  粉
#6D4C41  棕
#546E7A  蓝灰
```

**子色递进算法（关键：新增子节点不得改变已有子节点颜色）：**
- 父色转 HSL（h ∈ [0,360), s ∈ [0,1], l ∈ [0,1]）。
- 第 `i` 个子节点（`i` 从 0 开始，按创建顺序）：
  ```
  h' = h
  s' = clamp(s - 0.05 * i, 0.55, 1.00)
  l' = clamp(l + (i - 1) * 0.10, 0.30, 0.75)
  color = HSL(h', s', l') → RGB
  ```
- 示例（父 `#E53935` ≈ HSL(1°, 75%, 55%)）：
  - i=0 → `#C62828`（暗红）
  - i=1 → `#E53935`（原色略降饱和）
  - i=2 → `#EF5350`（浅红）
  - i=3 → `#E57373`（更浅）
  - i≥4 clamp 到 l=0.75，允许重复。
- **允许任意颜色重复**，不做唯一性校验。

**父色变化对子色的处理：**
- **新建父节点时**：用递进公式生成默认子色（`colorSeed = 父色`）。
- **修改父色时**：弹三选项「同时更新派生状态的子节点」/「只改父节点」/「取消」。
  - 「同时更新」只更新 `colorSeed` 仍等于「旧父色」的派生状态子节点；**用户手改过颜色的子节点不动**（手改时把 `colorSeed` 置 null）。
  - 「只改父节点」：子节点 `colorSeed` 置 null。
- **修改子色**：独立生效，不影响其他节点。

**颜色选择器（三联动 UI）：**
- 三个组成部分：
  1. **色盘 / 滑块**（HSL 或 HSV 滑杆 + 圆形色盘，任选其一）。
  2. **当前预览色块**（只读展示）。
  3. **16 进制输入框**（可编辑，接受 `#RRGGBB` 或 `RRGGBB`，大小写不限）。
- 联动规则：色盘改动 → 预览与 HEX 同步；HEX 输入合法 → 预览与色盘同步；非法输入 → 提示「请输入 #RRGGBB」并回滚到上次有效值。
- 暂不支持 alpha（避免复杂度）。

### 3.7 分类嵌套（≤3 层）
- 一级节点常用约 12 个（不强制上限，但 P0 UI 优先适配 12 个一级色）。
- **NFC 只绑叶子**；统计主视图按根聚合；时间轴显示叶子。
- 删除父节点三选项：「子节点提升为一级」/「连同子节点一起删除（归档）」/「取消」。

### 3.8 导入 / 导出（P0）

**格式：JSON**（结构化、层级清晰；Android 内置 `JsonReader`/`kotlinx.serialization` 都能处理）。

**schema（v1）：**
```json
{
  "version": 1,
  "exportedAt": "2026-01-15T10:30:00+08:00",
  "activities": [
    {
      "key": "study",
      "name": "学习",
      "color": "#E53935",
      "icon": "book",
      "children": [
        {
          "key": "english",
          "name": "英语",
          "color": "#EF5350",
          "children": [
            { "key": "translate", "name": "翻译", "color": "#E57373" },
            { "key": "writing",   "name": "作文", "color": "#EF9A9A" }
          ]
        }
      ]
    }
  ]
}
```

**导入规则：**
- `key` 是导入内引用；入库时映射为自增 id，`path` = `key1/key2/key3`。
- **同 path 已存在**：默认跳过并报告；对话框提供「跳过」/「重命名导入」/「整体取消」三选项（用户选，不静默覆盖）。
- **事务化**：整份 JSON 全部解析成功才入库；任何一条失败整体回滚。
- **深度校验**：> 3 层拒绝并报告。
- **非法 JSON / 缺字段**：整体拒绝并提示具体错误。
- 导入入口：文件选择（SAF）**或**粘贴文本框（两处入口，同一逻辑）。
- 导出入口：导出为 JSON 文件（SAF，UTF-8，`application/json`）**或**复制到剪贴板。
- **导出内容**：仅 `ActivityType` 树，不含 `TimeRecord`。

**字体导入：**
- 入口：文件选择（SAF），接受 `.ttf` / `.otf`。
- 存到 `filesDir/fonts/<sha1>.<ext>`，单文件 ≤ 5MB，超过拒绝。
- 字体只在**小部件 Bitmap 渲染时**使用 `Typeface.createFromFile`，P0 可在主界面预览应用；应用范围由用户在小部件配置里选择（P0 可以先全局一套）。
- 字体导入失败/损坏：不影响主流程，只提示。

### 3.9「关于」页（P0 必做）
- 显示：应用名 `Timing`、版本号（`BuildConfig.VERSION_NAME`）、包名 `com.togotiming.app`。
- 作者信息：
  - **togo0402@github**
  - **DeepSeek**
- 可附一句简介：「基于 NFC 碰触与个性化小部件的时间记录工具」。
- 不含外链、不申请网络权限。

## 4. 技术约束
- Kotlin + Jetpack Compose（依赖拉不动再回退 View，需在开工前/失败时报用户确认）。
- Room + KSP；`kotlinx.serialization` 用于 JSON 导入/导出。
- `minSdk 26`、`targetSdk 36`、`compileSdk 36`；输出 universal APK（不切 ABI）。
- **App 不申请 `INTERNET` 权限**（构建机可联网拉 Maven 依赖；交付前用 `aapt2 dump permissions app-release.apk` 验证）。
- 语言中文；文案在 `strings.xml`。
- 自签名：`keytool` 生成 keystore（`keystore.properties` 读入，不硬编码），`zipalign -p 4` + `apksigner sign --v2-signing-enabled --v3-signing-enabled`，`apksigner verify --print-certs` 通过。

## 5. 交付物
1. 完整源码 + README（构建命令、签名命令、NFC 写标签说明、小部件添加步骤、权限引导路径）。
2. 每个里程碑产出一个可安装的 signed APK（见 §10）。
3. README 必含：
   - keystore 生成命令、口令位置、重签命令。
   - NFC Tools 写标签步骤：NDEF URI `timing://nfc/toggle?uid=<HEX>` + AAR `com.togotiming.app`。
   - 小部件添加：W1/W2/W3 分别怎么加、尺寸、点击行为。
   - HyperOS 电池白名单 / 自启动 / 通知 / 精确闹钟引导路径。
   - 已知限制（NFC 后台必跳 App、Doze 刷新延迟、Toast 无滑动动画）。

## 6. 验收标准

**自动验证（AI 可做）：**
1. 编译通过；单元测试覆盖：NFC toggle 纯逻辑、5s 防抖、跨天裁剪、统计聚合（父含子孙）、子色递进公式、16 进制校验、导入回滚。
2. DAO 测试：进行中唯一、`NfcBinding.tagUid` 唯一、`ActivityType.path` 唯一、事务回滚。
3. `aapt2 dump permissions app-release.apk` 无 `INTERNET`；`apksigner verify` 通过。
4. 「关于」页显示正确作者信息。

**用户实机验证（AI 不能代劳）：**
1. 在设备上安装成功，新建「工作/学习/休息」，建三级嵌套「学习→英语→翻译」，颜色按规则递进。
2. 点开始 → 锁屏 5 分钟后 → 解锁回来时长正确。
3. 时间轴块位置正确；长按拖上缘能改开始时间；不和外层滚动冲突。
4. NFC 标签绑定「学习」：碰一下开始、再碰一下结束；5s 内连续碰只算一次。
5. 碰另一个绑「工作」的//nfc标签（此时学习进行中）→ 弹切换询问。
6. W1：计时期间每分钟刷新；点击能停止。
7. W2 占比环：颜色与叶子一致，分母是本地日 00:00~24:00。
8. W3：列出今日叶子排行，点某行能开始。
9. 统计页今日数据与时间轴一致；点父节点能展开子节点。
10. 杀进程重进，未结束计时能恢复。
11. 导入一份 3 层 JSON 成功；导入一份 4 层 JSON 被拒并提示；导入非法 JSON 整体回滚。
12. 导入字体文件后小部件/主界面能预览应用。
13. 修改父色弹三选项，选「只改父节点」子色不变；选「同时更新」派生色子节点变化、手改过的子节点不变。
14. 权限引导页显示所有权限状态，可一键跳设置。

## 7. 已知坑（必须遵守）

- **NFC 后台静默做不到**：P0 用 NDEF URI + AAR 拉起 App；桌面滑动提示需悬浮窗，P1 再做。
- **Toast 不能自定义滑入动画**：普通 Toast 是唯一 P0 选项。
- **小部件刷新频率**：`updatePeriodMillis` 最快 30 分钟；P0 靠前台服务每分钟，降级靠精确闹钟（约 15 分钟），**时长永远按 `now - startAt` 计算不累加**。
- **Doze/锁屏**：刷新可能延迟，重新点亮后必须显示正确时长。
- **HyperOS 后台**：只做**文字引导**跳设置页，不承诺自动成功。
- **RemoteViews 白名单**：W1/W3 用标准控件；W2 用 Bitmap 方案。
- **精确闹钟权限**：`SCHEDULE_EXACT_ALARM` 被拒时用 `setAndAllowWhileIdle` 近似。
- **NFC UID**：完整十六进制、大写、不截断。
- **AAR 包名 = applicationId**（`com.togotiming.app`）：debug 不加 `applicationIdSuffix`。
- **动态加载 dex**：不做（Non-goals）。
- **手势冲突**：时间轴长按拖拽后消费事件。
- **Android 13+ 通知权限**：`POST_NOTIFICATIONS` 被拒时降级（不再有通知，但计时仍基于 DB 恢复）。
- **Android 14+ FGS 类型**：声明 `specialUse` 并写 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`。

## 8. 开工前确认

### 8.1 已确认（无需再问）
- 目标设备确定有 NFC 硬件且可开关。
- 允许构建机联网拉 Maven 依赖、安装缺失 SDK、接受 licenses。
- 包名 `com.togotiming.app`，写入 AAR 后不可改。
- App 本身不申请 `INTERNET` 权限（构建机可联网）。
- `SYSTEM_ALERT_WINDOW` 在 P0 声明 + 引导页；滑动提示动画 P1 末实现。

### 8.2 agent 自检并汇报（不用问用户）
- JDK 17、`platform-36`、`build-tools`、`platform-tools`、`apksigner` 是否可用。

- Gradle Wrapper 能否拉通 Compose/Room/KSP。
- 若 Compose 拉不通：报告错误，再按用户指示决定是否回退 View。

### 8.3 默认接受，不需回答
- NFC 后台触发必定亮屏并跳进 App。
- 小部件在 Doze/锁屏下刷新可能延迟，但时长始终按 `now - startAt` 计算。

### 8.5 权限策略（一次性做全，M1 即落地）

M1 就要完成以下所有权限的声明 + 引导页（跳转设置），不要分阶段补：

- `NFC`（普通权限）
- `POST_NOTIFICATIONS`（Android 13+ 运行时权限）
- `FOREGROUND_SERVICE`（前台服务）
- `FOREGROUND_SERVICE_SPECIAL_USE`（Android 14+ FGS 类型）
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`（精确闹钟，被拒降级 `setAndAllowWhileIdle`）
- `SYSTEM_ALERT_WINDOW`（悬浮窗，P0 声明+引导；滑动提示功能 P1 末实现）
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（电池优化白名单）

权限引导页要求：
- 首次启动检查每项权限状态，逐项显示「已授予/未授予」+ 一键跳设置。
- 每项附一句中文说明「为什么需要 + 拒绝后的影响」。
- 不阻塞主流程；用户拒绝仍可用降级路径。
- HyperOS 额外引导：自启动、后台弹出界面、省电策略，只做文字引导 + 跳设置，不承诺自动成功。

## 9. 语义锁死清单（**逐条实现，不得自由发挥**）

- **嵌套**：≤3 层；统计按根聚合、可展开；时间轴显示叶子；NFC 只绑叶子；删除父节点弹三选项。
- **颜色**：一级 12 色按序循环；子色按 §3.6 公式（新增不影响已有）；父改色弹三选项，只更新派生状态子色；手改过的子色不级联；16 进制接受 `#RRGGBB`/`RRGGBB`；允许重复；三联动 UI。
- **NFC**：同 UID 5s 防抖；不同标签切换用 Dialog（App 在前台或被 AAR 拉起后）；AAR 包名 = `com.togotiming.app`。
- **小部件**：W1/W2/W3 内容、尺寸、点击、刷新按 §3.5；W2 分母 = 本地日 00:00~24:00，跨天裁剪；时长按 `now - startAt` 计算。
- **导入导出**：JSON，schema 见 §3.8；`path` 唯一；≤3 层；事务化；同 `path` 默认跳过并报告；两处入口（文件/粘贴）。
- **字体导入**：SAF 文件，≤5MB，存 `filesDir/fonts/`。
- **无网络**：App 无 `INTERNET` 权限，交付前 aapt2 验证。
- **关于页**：显示应用名、版本号、包名、作者 `togo0402@github` 与 `DeepSeek`。

## 10. 里程碑（每完成一个给我一个能装的 APK）

```text
M1  数据层 + 计时 + 前台服务 + 常驻通知 + 杀进程恢复 + 权限引导页 + 关于页  → APK-1
M2  时间轴 + 拖拽 + 编辑 + 补记                                          → APK-2
M3  分类嵌套（≤3层）+ 颜色体系 + 颜色选择器                              → APK-3
M4  W1 当前事件 + W3 多事件 + 刷新机制                                  → APK-4
M5  W2 占比环 + NFC 前台 toggle + 绑定页 + AAR 后台拉起                  → APK-5
M6  JSON 导入导出 + 字体导入                                             → APK-6
（P1：悬浮窗滑动提示、主题、标签、通知提醒）
```

> 建议：M3 与 M1/M2 的依赖较紧（`parentId`/`colorSeed` 影响 schema），若想减少 DB 迁移可在 M1 就把表结构做全，M3 只补 UI 与算法。

## 11. 汇报口径（防"假装验收"）

- AI 汇报格式：构建命令、APK 路径、`sha256`、已完成里程碑、已自动验证项、未实机验证项、已知限制。
- AI **不得声称** NFC 碰触、锁屏 5 分钟、小部件 Doze 刷新、HyperOS 后台保活、通知权限拒绝流程「已通过」——这些必须用户实机验收。
- 每交付一个 APK 附上：
  - `apksigner verify --print-certs` 输出。
  - `aapt2 dump permissions app-release.apk` 输出（确认无 `INTERNET`）。
  - 单元测试报告。
  - 已知问题清单。
- 第一条回复必须先回答 §8.2 的自检结果，再开始写代码。
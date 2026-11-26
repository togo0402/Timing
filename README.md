# Timing

基于 **NFC 碰触** 与 **个性化桌面小部件** 的本地时间记录 App（「时间日志」的替代品，全新实现，非二次开发）。

当前版本：**1.3.0**（新增时间记录导入/导出：按时间范围 + 事件范围，JSON 文件/剪贴板；与分类导入导出同放设置「数据」）。

- 包名：`com.togotiming.app`
- 语言：Kotlin + Jetpack Compose（Material 3）
- 存储：Room（SQLite），全部时间以 UTC 毫秒存储，按本地时区展示
- 最低系统：Android 8.0（API 26）；目标 SDK 36（Android 16）
- **不申请 `INTERNET` 权限**，无云同步 / 账号 / 社交 / 任何网络请求

作者：**togo0402@github**、**DeepSeek**

---

## 0. 项目结构

```
Timing/
├─ app/                     Android 应用（Kotlin + Compose）
├─ docs/                    架构、开发、NFC、变更记录、工具总结
├─ design/                  设计稿/预览图（HTML + PNG）
├─ dist/                    当前 release APK + BUILD-INFO
├─ skills/timing-dev/       AI 继续开发用的 SKILL
├─ tools/                   本机构建/工具链脚本
├─ gradle/                  Gradle wrapper
├─ keystore/                本地签名（gitignore，不提交）
├─ build.gradle.kts
├─ settings.gradle.kts
└─ README.md
```

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [开发与构建](docs/DEVELOPMENT.md)
- [NFC 机制](docs/NFC.md)
- [变更记录](docs/CHANGELOG.md)
- [工具总结](docs/TOOLS.md)
- [GitHub 发布说明](docs/GITHUB.md)
- [AI 开发 Skill](skills/timing-dev/SKILL.md)

---

## 1. 功能

| 模块 | 说明 |
| --- | --- |
| 计时 | 顶部开始/停止，秒级跳动；结束/切换自动落库；杀进程后可恢复进行中的计时；通知栏计时条支持暂停/继续 |
| 时间轴 | 横向 Gantt（0–24h 一屏），双指缩放 30min–24h、单指横拖、纵向滚动；点击色条编辑，点击空白补记；顶部日期胶囊 + 玻璃月历 |
| 分类 | 最多 3 层嵌套；12 色一级调色板；子色按 HSL 派生；目标时长；批量删除、批量长按拖动移动 |
| 统计 | 今日 / 本周 / 本月，可切换其他日/周/月；根节点聚合、下钻、环形占比、未统计灰色 |
| NFC | 可写 NTAG 绑定后静默拉起；无 NDEF 卡走 TECH_DISCOVERED（系统确认）；同 UID 5 秒防抖 |
| 通知计时条 | `IMPORTANCE_HIGH` 常驻通知，系统 Chronometer 每秒自走；点击通知暂停/继续，附结束/打开按钮 |
| 小部件 | W1 计时进度环 + Chronometer；W2 今日 24h 分布环（前进端圆头）；W3 一级节点彩色磁贴 |
| 桌面快捷方式 | 长按图标 → 计时器 / 今日占比 / 今日汇总；HyperOS 需「桌面快捷方式」权限 |
| 深色模式 | 设置页「跟随系统 / 浅色 / 深色」，即时生效 |
| 导入导出 | 分类树 JSON；记录 JSON（按时间/事件范围）；SAF 文件或粘贴；事务化 |
| 字体 | 导入 `.ttf/.otf`（≤5MB）用于预览 |
| 权限 | 一次性权限引导页（通知 / 精确闹钟 / 悬浮窗 / 电池优化 / NFC / HyperOS 保活） |

### v1.3.0 记录导入 / 导出

- 设置 → 数据 → 「记录导入 / 导出」。
- 导出：选择开始/结束日期（默认最近 7 天）、事件范围（分类树多选，勾父级含子级，不选=全部）；导出已结束记录，按区间裁剪；写 JSON 文件或复制剪贴板。
- 导入：文件或粘贴 JSON；自动按 path 创建缺失分类；同 typeId+startAt+endAt 去重；报告导入/跳过/创建/非法条数。
- JSON 含 version/kind/exportedAt/from/to/types/records。

### v1.2.1 统计日期 / 回到今天 / 分类拖动

- **统计**：顶部左右切换 + 日期选择（日/周/月锚点），玻璃月历；「回到今天」；聚合/未统计按选定区间重算。
- **计时页**：非今天时日期胶囊旁出现「今天」小胶囊，一键回到今天。
- **分类**：批量选择后长按已选行拖动；拖到分类行=成为其子级，拖到空白=变为一级；子孙保持原关系整体移动；禁止拖到自身/子孙。
- 暂缓：拖动边缘自动滚动（目标不在视口需先滚动）。

### v1.2.0 Gantt + 月历 + 批量删除

- 计时页改为**横向 Gantt**：默认 0–24h 一屏看全天；每条记录一行，短事件最小 6dp 色条 + 右侧标签；当前时间红线。
- **双指缩放** 30min–24h、**单指横拖**平移、纵向滚动；点击色条编辑，点击空白补记。
- 顶部日期胶囊 + 日历按钮，点开 **6×7 月历**（周一起始、上下月、回到今天），选日期自动收起。
- NFC 标签：单个删除先确认；新增「批量管理」多选删除。
- 分类：新增「批量」多选，删除时可选择「提升子分类」或「删除整棵子树」。
- 暂缓：Gantt 色条左右拖拽改时间（本轮用点击编辑代替）。

### v1.1.4 NFC TECH_DISCOVERED

- 新增 `TECH_DISCOVERED` + `res/xml/nfc_tech_filter.xml`：身份证、手环、门禁卡等**没有 NDEF** 的卡，系统也能把 Timing 拉起（可能弹一次“发现 NFC 标签，是否打开应用处理标签”，点“打开”即可）。
- NDEF/AAR 仍是静默拉起的唯一方式；只读卡无法写 AAR 时，用 TECH 通道兜底。
- `Ndef.get` 失败/只读时绑定对话框显示诊断。

### v1.1.3 风格统一

- 统计页：GlassSegmented 周期切换、玻璃总览/节点/未统计行、玻璃明细弹窗。
- 分类页：玻璃分类行、渐变「新建一级分类」、玻璃操作圆钮。
- NFC 页：玻璃状态卡/标签行/提示卡，绑定对话框显示标签诊断（可写 / 只读 / 无 NDEF）。
- 导入导出、权限页：玻璃标题、卡片、pill 与按钮。
- 注意：`身份证 / 门禁卡 / 多数游泳卡` 属于只读、无 NDEF 的卡，只能前台读 UID；后台拉起必须用可写 NTAG213/215/216 标签。

### v1.1.2 修正

- **NFC**：写标签失败时区分「只读 / 不支持 NDEF / 写入失败」，提示换可写标签；支持 NDEF Formatable。
- **小部件添加**：HyperOS 静默失败时弹出「还差一个桌面权限」对话框，可跳 MIUI 权限编辑页开启「桌面快捷方式」；仍失败则给出手动添加路径。
- **侧滑返回**：关闭 predictive back（`enableOnBackInvokedCallback=false`），修复返回时上一页元素叠在当前页上的问题。
- **1.2 设计参考**：`design/preview-gantt.html` / `design/gantt-preview.png`（横向 Gantt + 双指缩放 + 可折叠日历），未实装。

### v1.1.1 修正（用户反馈）

- **统计**：周期内未记录时间以灰色「未统计」显示（含灰环与列表行，百分比口径改为占周期总时长）。
- **NFC**：绑定标签时自动把 `timing://nfc/toggle?uid=...` + AAR 写入标签，后台/冷启动可由系统拉起；删除系统限制说明文案。
- **导航**：底部导航仅显示当前项文字；页面切换去掉淡入淡出（即时）；修复「设置图标进入设置后点计时无反应」，以及二级页面点「设置」回到设置一级页。
- **导入**：粘贴 JSON 文本框限高 220dp，内部滚动，不再撑高页面。
- **小部件 W2**：去掉明显的圆点标记；只有进行中事件的**前进端**是圆头，起始端平头。
- **目标时长**：分类可配置每日默认目标；开始计时时可改本次目标；计时中可点进度环修改；W1 进度环按目标计算并封顶 100%。
- **时间轴**：短事件（<44dp）改为「甘特条 + 右侧浮层胶囊」，避免文字互相覆盖/溢出，保留拖拽与点击。
- **分类**：叶子节点不再显示「叶子」二字；行内可直接编辑目标。
- **小部件添加**：补充 MIUI 桌面 INSTALL_SHORTCUT / INSTALL_WIDGET 权限，快捷方式请求添加更可靠。

### v1.1 重点变化

- **通知计时条**：系统高优先级通知 + Chronometer，点击暂停/继续；HyperOS 上会作为常驻通知优先展示（第三方无法申请小米「焦点通知」，见已知限制）。
- **小部件重做**：W1 秒表环、W2 24h 分布环（当前时间圆点）、W3 彩色磁贴；全部使用原生 Chronometer 走秒，刷新不依赖 `updatePeriodMillis`。
- **体积**：release 开启 `isMinifyEnabled + isShrinkResources`，仅保留 arm64-v8a，APK 从 12 MB 降到 **约 1.75 MB**。
- **视觉**：Fluid Glass 设计系统（浅色/深色）、时间轴卡片化、设置页五组分层、新图标（计时环）。

---

## 2. 构建环境

本项目在 Windows + 以下工具链上构建通过：

| 组件 | 版本 |
| --- | --- |
| JDK | Temurin 17.0.21.1 |
| Android SDK Platform | android-36 |
| Build-Tools | 36.0.0 |
| Platform-Tools | 37.0.3 |
| Gradle | 8.13（Wrapper 已内置） |
| AGP | 8.11.1 |
| Kotlin | 2.1.21 |
| Compose BOM | 2025.06.01 |
| Room | 2.7.2 |

### 2.1 一键安装工具链（国内镜像）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup-toolchain.ps1
```

脚本会从清华 / 腾讯 / 华为镜像安装 JDK 17、cmdline-tools、platform-36、build-tools 36.0.0、platform-tools、Gradle 8.13 到 `D:\AndroidDev`。

### 2.2 指向 SDK

在项目根目录创建 `local.properties`：

```properties
sdk.dir=D:/AndroidDev/sdk
```

### 2.3 编译

```powershell
$env:JAVA_HOME='D:\AndroidDev\jdk17'
.\gradlew.bat :app:assembleDebug      # 调试包
.\gradlew.bat :app:assembleRelease    # 正式包（自动签名）
```

> 依赖仓库优先走阿里云 / 华为 / 腾讯镜像（见 `settings.gradle.kts`），`dl.google.com` 不可达时也能构建。
> Gradle Wrapper 的 `distributionUrl` 指向腾讯镜像。

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`（已用下面的 keystore 签名）

### 2.4 安装

```powershell
D:\AndroidDev\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\release\app-release.apk
```

---

## 3. 签名

keystore 已随仓库提供（自签名，仅用于个人安装）：

| 项 | 值 |
| --- | --- |
| 文件 | `keystore/timing-release.jks` |
| alias | `timing` |
| storePassword | `timing2026` |
| keyPassword | `timing2026` |
| 算法 | RSA 2048 / SHA256withRSA / 有效期 10000 天 |

口令通过 `keystore.properties` 读入（不打进源码）：

```properties
storeFile=keystore/timing-release.jks
storePassword=timing2026
keyAlias=timing
keyPassword=timing2026
```

### 3.1 重新生成 keystore

```powershell
D:\AndroidDev\jdk17\bin\keytool.exe -genkeypair -v ^
  -keystore keystore\timing-release.jks -alias timing ^
  -keyalg RSA -keysize 2048 -validity 10000 ^
  -storepass timing2026 -keypass timing2026 ^
  -dname "CN=Timing, OU=togo0402, O=togo0402@github, L=Shanghai, ST=Shanghai, C=CN"
```

### 3.2 手动重签（v2 + v3）

AGP 生成的 `app-release.apk` 已签名；若要手动签未签名包：

```powershell
$bt = 'D:\AndroidDev\sdk\build-tools\36.0.0'
& "$bt\zipalign.exe" -p -f 4 app-release-unsigned.apk app-aligned.apk
& "$bt\apksigner.bat" sign `
  --ks keystore\timing-release.jks --ks-pass pass:timing2026 --key-pass pass:timing2026 `
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true `
  --out app-release-signed.apk app-aligned.apk
& "$bt\apksigner.bat" verify --print-certs app-release-signed.apk
```

### 3.3 校验

```powershell
& "$bt\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
& "$bt\aapt2.exe" dump permissions app\build\outputs\apk\release\app-release.apk   # 确认无 INTERNET
```

---

## 4. NFC 标签写入（后台也能拉起）

### 4.1 先绑定

App → 底部「NFC」→「绑定新标签」→ 把标签贴到设备背面 → 记下完整 UID（大写十六进制，不截断）→ 选择叶子活动 → 绑定。

### 4.2 用 NFC Tools 写入「可被系统派发」的内容

后台 / 已杀进程时，普通 App 收不到 NFC 事件。需要标签里带 **NDEF URI + AAR**：

1. 打开 NFC Tools → 写 → 添加记录 → **URI**
   内容：
   ```
   timing://nfc/toggle?uid=<你的UID>
   ```
2. 再添加一条记录 → **Android Application Record (AAR)**
   包名：
   ```
   com.togotiming.app
   ```
3. 写入标签。

之后即使 App 没运行，碰标签也会由系统亮屏并拉起 Timing，再按绑定执行 toggle。

> `uid` 参数只是兜底；App 优先使用系统派发的 `Tag.getId()`，两者都取不到时会提示失败，不会静默。
> 注意：**debug 包不能加 `applicationIdSuffix`**，否则 AAR 包名对不上（本项目已固定 `applicationId = com.togotiming.app`）。

### 4.3 行为

- 同一标签：正在计时 → 结束；否则先结束当前计时（若有）再开始。
- 不同标签：当前有别的活动在计时 → 弹「是否切换到 X？」。
- 同一 UID 5 秒内重复上报忽略。
- 后台触发必定亮屏并跳进 App（系统限制，无法静默）。

---

## 5. 桌面小部件（v1.1）

长按桌面空白 → 小部件 → Timing，或长按 App 图标使用快捷方式：

| 小部件 | 尺寸 | 内容 | 点击 |
| --- | --- | --- | --- |
| **W1 计时器** | 2×2 | Canvas 圆角渐变卡片 + 秒表进度环（1 小时满环、圆头）；环中央显示叶子事件名 + 原生 Chronometer 走秒 +「点击停止」；空闲显示「未在计时 / 点击开始」 | 计时中 → 停止；空闲 → 打开 App |
| **W2 今日占比** | 2×2 | 24 小时分布环：未记录时间用 `#E8EDF5` 底轨，已发生事件按颜色画弧，**当前时间头为圆形圆点**；无文字 | 打开统计页 |
| **W3 今日汇总** | 4×2 / 4×4 | 只展示一级节点（子节点时长累加），2 列彩色磁贴：一级名称 + 今日累计 + 百分比环；空状态「今天还没有记录」 | 叶子 → 开始/切换；非叶子 → 打开 App |

刷新机制：

- 计时期间由前台服务 **每分钟** `updateAppWidget`，Chronometer 由系统每秒自走；
- 前台服务被停 / 精确闹钟不可用时，降级为 `AlarmManager.setAndAllowWhileIdle` 约 **15 分钟**一次；
- 时长永远按 `now - startAt` 计算，不累加，刷新延迟不会丢时间；
- 空闲（未计时）时没有分钟刷新，W2 的当前时间头 / W3 的排序只在 App 打开、小部件重加或尺寸变化时更新。

> 小部件选择器里的预览图目前显示为 App 图标（未设置 `previewLayout`），属已知外观问题，不影响添加和使用。

---

## 6. 权限与 HyperOS 保活

首次启动进入「设置 → 权限引导」逐项开启：

| 权限 | 用途 | 拒绝影响 |
| --- | --- | --- |
| 通知 | 计时常驻通知 | 通知不显示，计时仍以数据库为准 |
| 精确闹钟 | 小部件降级刷新 | 降级为约 15 分钟近似刷新 |
| 悬浮窗 | 声明备用（P1 滑动提示） | 不影响 P0 |
| 电池优化白名单 | 后台计时服务不被杀 | 后台可能被系统回收，时长仍准确（按 DB 恢复） |
| NFC | 碰触计时 | 无法使用 NFC |

HyperOS / MIUI 额外设置（文字引导 + 跳设置，不保证自动成功）：

1. 设置 → 应用设置 → 应用管理 → Timing → **自启动** 允许；
2. 省电策略 → **无限制**；
3. 其他权限 → **后台弹出界面** 允许（后台 NFC 拉起时需要）。

---

## 7. 已知限制

1. **NFC 后台**：系统不允许后台常驻读卡；App 在绑定时会把 NDEF URI + AAR 写入标签，后台/冷启动由系统拉起。若标签是「时间日志」等 App 写过的**只读标签**，AAR 指向原 App（会跳应用商店），只能换一张可写标签（NTAG213/215）重新绑定。
2. **暂停状态**：暂停时数据库记录先结束，App 首页暂时显示「未在计时」；继续请点通知。
3. **小部件添加**：HyperOS 桌面需要 MIUI 的「桌面快捷方式」AppOp；未开启时系统会静默失败，App 会弹「还差一个桌面权限」引导去开启，或长按桌面空白 → 小部件手动添加。
4. **小部件预览**：选择器里仍显示 App 图标（未设置 `previewLayout`）。
5. **W2 环**：短事件弧段用平头，极短时长下可能看起来像小方块；进行中事件的前进端为圆头。
6. **时间轴密集短事件**：胶囊会自动纵向错开，极密集时可能离所属条形较远；长按拖拽请点在条形本体上。
7. **UI 一致性**：首页、时间轴、设置、关于、统计、分类、NFC 已 Glass 化；导入导出/权限页同步。
8. **Doze / 锁屏**：小部件刷新可能延迟，重新点亮后显示正确时长。
9. **字体**：仅在小部件 Bitmap / 设置页预览使用；W1 事件名是真实 TextView，字体设置暂不作用于它。
10. **导入导出**只含活动类型树，不含时间记录。
11. **HyperOS 保活**只做文字引导，不承诺自动成功；后台被清后时长仍以数据库为准。
12. **NFC 无 NDEF 卡**：非空白卡（手环/身份证/门禁）每次碰卡系统都会询问，Android 没有“始终”默认；时间日志官方文档也承认这一系统限制。静默需可写 NTAG 标签（绑定一次即写入 URI+AAR）。
13. **Gantt 拖拽**：本轮只支持点击编辑/空白补记，色条左右边缘拖拽改时间尚未实装。

---

## 8. 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest          # 纯逻辑单元测试（JVM，可离线跑）
.\gradlew.bat :app:connectedDebugAndroidTest  # DAO / 事务测试（需连接设备）
```

单元测试覆盖：颜色公式 / HEX 校验、时间切日与跨天裁剪、统计聚合（父含子孙）、NFC 决策与 5s 防抖、JSON 导入校验与导出回环。

当前自动化结果：**36 个 JVM 单元测试全部通过**（0 failed / 0 error），覆盖：

| 测试类 | 数量 | 覆盖点 |
| --- | --- | --- |
| `ColorPaletteTest` | 7 | 12 色循环、HEX 解析/输出、子色 HSL 递进与 clamp |
| `TimeMathTest` | 8 | 本地日界、周一起始、月初、跨天裁剪、进行中计到 now、拆日、5 分钟吸附 |
| `NfcToggleTest` | 7 | 开始 / 结束 / 询问切换 / 忽略、UID 规范化、5s 防抖 |
| `StatsAggregatorTest` | 6 | 根聚合含子孙、区间裁剪、进行中、下钻、占比 |
| `ImportExportTest` | 8 | 3 层解析、4 层拒绝、非法颜色/缺字段/重复 path/版本/坏 JSON、导出回环 |

`RepositoryDaoTest`（androidTest）覆盖进行中唯一、tagUid 唯一、path 唯一、绑定只允许叶子、导入回滚、3 层上限，需连接设备运行。

---

## 9. 交付物与验收状态

### 9.1 产物

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `dist/Timing-1.3.0-release.apk` | 2,033,977 B | **当前版本，可直接安装**，v2+v3 已签名 |
| `dist/Timing-1.2.1-release.apk` | 1,814,021 B | 上一版，留作回滚 |

- SHA-256（1.3.0 release）：`02642F7AF2F8FAA06C34DB05CF596983C6E94EB4A573B240EFAA6C5CDE9511DE`
- 包名：`com.togotiming.app`　versionCode 9 / versionName 1.3.0
- minSdk 26 / targetSdk 36 / compileSdk 36
- ABI：arm64-v8a（仅保留目标机 ABI）
- release 开启 `isMinifyEnabled + isShrinkResources`（R8）
- 签名：`CN=Timing, OU=togo0402, O=togo0402@github, L=Shanghai, ST=Shanghai, C=CN`
- 证书 SHA-256：`b028563361fec42acb185ec7d85c492f51cafd6cb2dd1f68baeb809e27d5e661`

### 9.2 自动验证（已通过）

- `assembleDebug` / `assembleRelease`：BUILD SUCCESSFUL
- `testDebugUnitTest`：36/36 通过（v1.1 逻辑未改动这些纯逻辑模块）
- `apksigner verify --verbose`：v2 = true、v3 = true
- `aapt2 dump permissions`：无 `INTERNET`
- 小米 11 Pro（HyperOS 2.0.1 / Android 14）实机：1.1.0 安装启动、新建分类、计时、通知 Chronometer 走秒、点击通知暂停/继续、深色模式切换、W1 小部件 Chronometer 走秒均已验证

### 9.3 仍需你实机验收 / 尚未验证

1. 锁屏 5 分钟后解锁，时长正确。
2. 时间轴块位置、长按拖上缘、滚动不冲突。
3. NFC 标签绑定与碰触 toggle（没有实体标签，未测试）。
4. W2 今日占比环、W3 今日汇总磁贴的实际外观与点击。
5. 小部件在 Doze / 锁屏下的刷新表现。
6. 杀进程重进恢复计时。
7. HyperOS 权限引导页各跳转（精确闹钟 / 电池优化 / 通知）。
8. 统计 / 分类 / NFC 页面仍是 v1.0 风格，不在本次重点。 

---

## 10. 里程碑与实现范围

行动指南的 M1–M6 功能在本次交付中**一次性实现并合并构建**（未分 6 个 APK 交付，避免多次签名/安装）：

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M1 | 数据层、计时、前台服务、常驻通知、杀进程恢复、权限引导、关于页 | ✅ |
| M2 | 时间轴、拖拽、编辑、补记 | ✅ |
| M3 | 三级分类、颜色体系、三联动颜色选择器 | ✅ |
| M4 | W1 当前事件、W3 多事件、刷新机制 | ✅ |
| M5 | W2 占比环、NFC 前台 toggle、绑定页、AAR 后台拉起 | ✅ |
| M6 | JSON 导入导出、字体导入 | ✅ |
| P1 | 悬浮窗滑动提示、小部件深度主题、标签、通知提醒 | ⬜ 未做 |

## 11. 目录结构

```text
app/src/main/java/com/togotiming/app/
  data/        Room 实体、DAO、仓库、设置
  domain/      颜色体系、时间计算、统计聚合、NFC 决策、JSON 导入导出
  timer/       前台服务、常驻通知、精确闹钟降级
  widget/      W1/W2/W3 Provider、RemoteViewsService、Bitmap 渲染
  nfc/         ReaderMode + NDEF 拉起
  ui/          Compose 导航 / 首页时间轴 / 统计 / 分类 / NFC / 设置
```

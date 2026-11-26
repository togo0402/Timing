# Timing v1.1 · UI/UX 设计稿（待确认）

> 预览图：`design/app-preview.png`、`design/widgets-preview.png`
> HTML 源：`design/preview-app.html`、`design/preview-widgets.html`

## 1. 设计方向

**Fluid Glass × Material 3 Expressive**

- 品牌蓝沿用图标 `#3D8EFF`，深蓝 `#2470D8`，渐变 `#4E9BFF → #2E7DEE`
- 浅色：冷白底 + 三处极淡蓝紫光斑；深色：`#0B111C` 底 + 蓝紫辉光
- 玻璃卡片：半透明白 + `blur(28~30)` + 1px 高光描边 + 大圆角（24~32dp）+ 柔和彩色阴影
- 字体：系统 MiSans / 苹方；时间数字使用 tabular-nums，大字号、紧字距
- 圆角体系：卡片 24~32，行 16~20，胶囊 999

## 2. Design Tokens

### 浅色
| Token | 值 |
| --- | --- |
| background | `#F7F9FE → #EEF3FC` 渐变 |
| surface / glass | `rgba(255,255,255,.62)` + blur 28 |
| primary | `#3D8EFF` |
| primaryDeep | `#2470D8` |
| text | `#101828` |
| textSecondary | `#667085` |
| divider | `rgba(16,24,40,.07)` |
| shadow | `0 26px 50px -28px rgba(31,72,150,.42)` |

### 深色
| Token | 值 |
| --- | --- |
| background | `#0B111C → #0A0F18` |
| glass | `rgba(255,255,255,.06)` + blur 28 |
| primary | `#6FB1FF` |
| text | `#EAF1FF` |
| textSecondary | `#8FA3C4` |
| divider | `rgba(255,255,255,.08)` |

### 间距 / 圆角
- 页面水平 padding 18dp，卡片内 18~20dp，卡片间距 12dp
- 主要触控高度 44~48dp；底部导航 66dp 浮动胶囊

## 3. 页面重构

### 首页
- 顶部：日期 + 「今天」大标题 + 补记 / 设置圆形玻璃按钮
- Hero 计时卡：事件名 + 层级路径 + 进行中胶囊；`00:20:35` 大号等宽数字；右侧 1 小时进度环（34%）；暂停圆钮 + 结束渐变主按钮 + 补记胶囊
- 快捷 chip 行：叶子事件，当前事件高亮
- 时间轴：**左侧 4dp 细色条 + 浅色圆角卡片**，块内「名称 / 起止 / 时长」，进行中淡蓝渐变 + LIVE；左侧 48dp 时间刻度
- 底部：五栏浮动玻璃导航

### 统计
- 胶囊分段：今日 / 本周 / 本月
- 总时长大字 + 178dp 环形占比图（根节点颜色，>8 段灰「其它」）
- 根节点列表：色点、名称、进度条、时长、百分比；点击展开子节点
- 深色适配

### 分类
- 顶部「新建一级分类」虚线胶囊
- 树形行：细色条 + 名称 + 子分类/叶子 + 时长 + chevron
- 长按/更多操作：改名、改色、删除（三选项）

### 设置
- 分组：外观（主题模式 / 字体）、NFC 与计时、数据、权限与保活、关于
- 主题：跟随系统 / 浅色 / 深色 胶囊分段（DataStore 持久化）
- 关于页放「无 INTERNET、数据仅本机」声明

## 4. 通知（桌面顶部计时条）

- `IMPORTANCE_HIGH` 常驻通知，`RemoteViews` 内含原生 `Chronometer`
- 标题：`【考研】学习 / 英语`；副标题：`已用 20 分 35 秒 · 点击暂停`
- 右侧 `20:35` 由 Chronometer 自动走秒，Service 不轮询
- 按钮：暂停 / 结束 / 补记
- 不使用 `SYSTEM_ALERT_WINDOW`；HyperOS 焦点通知/实时活动作为增强适配（可用则用，不可用降级普通通知）

## 5. 小部件

- **W1 2×2**：Canvas 圆角渐变卡片 + 1h 进度环 + 事件名 + 原生 Chronometer（`20:35`），点击暂停/继续
- **W2 2×2**：24h 占比环，仅颜色无文字，点击进统计
- **W3 4×2 / 4×4**：只展示一级节点（子节点累加到父），行 = 色条 + 名称 + 累计时长 + 进度条，点击开始/切换
- 快捷添加：`ShortcutManager` 长按图标 → 计时器 / 今日占比 / 今日汇总（中文 label）

## 6. 图标

采用你提供的 SVG：
- 主图标 512（蓝底圆角 + 圆环 + 顶部按钮）
- 自适应前景 108 / 背景 108
- 单色 24（通知栏、主题图标）
- 需要生成 mipmap 各密度 PNG + `ic_stat_timer` 单色矢量

## 7. 实现映射（Compose）

| 设计元素 | 实现 |
| --- | --- |
| 玻璃卡片 | `Surface` + `graphicsLayer` 半透明 + 渐变背景；Android 12+ 可选 `Modifier.blur`/`RenderEffect`，低版本用半透明 + 阴影近似 |
| 大数字 | `fontFeatureSettings = "tnum"`，`FontWeight.Bold`，`letterSpacing = (-2).sp` |
| 进度环 | Canvas `drawArc`，`Stroke(cap = Round)` |
| 时间轴卡片 | `Row` + 左侧 4dp `Box` + `Surface` 卡片；拖动逻辑复用 v1.0 |
| 通知 Chronometer | `RemoteViews` 布局 + `setChronometer` |
| 小部件 W1/W2 | 继续 Bitmap（Canvas）渲染 + ImageView；Chronometer 需 RemoteViews 真实控件，故 W1 采用「Bitmap 背景 + 上层 Chronometer」组合 |
| 深色模式 | DataStore `themeMode` + `TimingTheme(darkTheme)` |

> 注意：W1 若用 Bitmap 画整张卡片，Chronometer 必须是真实 RemoteViews 控件叠在 Bitmap 上（`FrameLayout` + `ImageView` + `Chronometer`），否则无法原生走秒。

## 8. 待确认

1. 是否采用这套 Fluid Glass 视觉（浅色 + 深色）？
2. 玻璃强度：当前偏「磨砂白」，可调更通透（更像 iOS 液态玻璃）或更扁平。
3. 时间轴卡片是否保留「块内显示时长」，还是只显示名称 + 起止？
4. 底部导航 5 栏是否保留（计时/统计/分类/NFC/设置）。

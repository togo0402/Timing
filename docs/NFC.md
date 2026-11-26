# NFC 机制与现状

## 结论先行

| 卡类型 | 前台（Timing 打开） | 后台/桌面 |
| --- | --- | --- |
| NDEF 可写卡（NTAG213/215/216） | 直接读 UID | 绑定/写入后，系统按 **NDEF URI + AAR** 静默拉起 |
| 只读但带他人 NDEF/AAR（如时间日志写过的卡） | 直接读 UID | 系统按 **原 AAR** 拉起原 App（未装则跳应用商店）；Timing 无法改写 |
| 无 NDEF 卡（身份证 / 手环 / 门禁 / 多数饭卡） | ReaderMode 可读 UID | 只能 **TECH_DISCOVERED**，系统弹出「发现 NFC 标签，是否打开应用处理标签」，需手动确认 |

## 为什么非空白卡每次都要选 App

Android 的 NFC 分发顺序：

1. `NDEF_DISCOVERED`：看 NDEF 里的 URI/AAR；AAR 指定的包优先，直接拉起。
2. `TECH_DISCOVERED`：卡没有 NDEF 时，按 `tech-list` 找 App；因为可能命中钱包/商城/支付宝/微信/TagViewer 等多个 App，系统必须让用户选。
3. `TAG_DISCOVERED`：兜底。

`TECH_DISCOVERED` 没有“始终用这个 App”的持久默认，这是系统安全设计，App 无法绕过。

时间日志官方文档也写明：

- 空白卡写入 = 把时间日志的 NDEF + AAR 写进卡，之后卡只认时间日志，不弹框；
- 非空白卡（门禁卡、身份证、饭卡等）每次识别可能弹框选 App；
- OPPO/一加即使写空白卡也仍弹，系统层面暂时无解；
- 息屏时 Android 不监听 NFC，无法息屏刷。

## Timing 的实现

- 前台：`enableReaderMode`（NFC_A/B/F/V + `FLAG_READER_SKIP_NDEF_CHECK`），5 秒同 UID 防抖。
- 后台：
  - `AndroidManifest` 注册 `NDEF_DISCOVERED`（scheme `timing`，host `nfc`）；
  - 注册 `TECH_DISCOVERED` + `res/xml/nfc_tech_filter.xml`（NfcA/B/F/V、IsoDep、MifareClassic、MifareUltralight、Ndef、NdefFormatable）；
  - `NfcController.handleIntent` 同时处理三种 action，从 `EXTRA_TAG` 取 UID。
- 绑定写卡：
  - `Ndef.get(tag)` 可写 → 整条覆盖为 `timing://nfc/toggle?uid=<HEX>` + Timing AAR；
  - 不可写 → 诊断「只读标签」；
  - 无 Ndef 但 `NdefFormatable` → format 后写入；
  - 无 NDEF → 诊断「无 NDEF 的卡（身份证/门禁卡等）」。

## 推荐硬件

- **NTAG213 / NTAG215** 白卡或贴纸（0.5–2 元），贴书桌/床头/卫生间。
- 绑定一次写入后，桌面碰卡即可静默开始/结束。
- 不建议用身份证/手环/门禁做后台测试：只读、无 NDEF，只能前台或手动确认。

## 调试

1. 确认手机 NFC 已开启。
2. Timing → NFC → 「绑定新标签」→ 贴卡；看诊断：
   - 「可写标签：已写入 NDEF + AAR」→ 后台应静默；
   - 「只读标签」/「无 NDEF」→ 后台只能 TECH 弹窗。
3. 桌面碰卡：静默拉起说明成功；弹选择说明走的是 TECH。
4. 检查标签是否被写坏：用 NFC Tools 读 NDEF，应看到 `timing://nfc/toggle?uid=...` 与 AAR `com.togotiming.app`。

## 已知限制

- 息屏/锁屏不监听 NFC（Android）。
- 非 NDEF 卡无法静默。
- 只读外来 AAR 卡无法被 Timing 覆盖。
- 小米手环的卡模拟能否被手机读取取决于手环型号与卡片类型；能被读时走 TECH。

# 开发与构建指南

## 1. 工具链

| 工具 | 路径 / 版本 |
| --- | --- |
| JDK | `D:\AndroidDev\jdk17`（17.0.21.1） |
| Android SDK | `D:\AndroidDev\sdk`（platform-36、build-tools 36.0.0） |
| Gradle | 项目自带 wrapper 8.13 |
| adb | `D:\AndroidDev\sdk\platform-tools\adb.exe` |
| Kotlin | 2.1.21；Compose BOM 2025.06.01；Material3 1.3.2 |
| Room | 2.7.2（KSP） |
| 签名 | `keystore/timing-release.jks` + `keystore.properties`（**不要提交**） |

工具还包括：`apksigner`、`aapt2`、`dexdump`、Edge headless（渲染设计稿）、PowerShell 7、DSH + MCP（`uiux` / `wash`，可选）。

## 2. 构建

```powershell
$env:JAVA_HOME='D:\AndroidDev\jdk17'
Set-Location 'D:\ai own\Timing'

# 编译检查
.\gradlew.bat :app:compileDebugKotlin --console=plain

# 单元测试
.\gradlew.bat :app:testDebugUnitTest --console=plain

# Release（R8 + 签名）
.\gradlew.bat :app:assembleRelease --console=plain

# 产物
app\build\outputs\apk\release\app-release.apk
```

发布时复制到 `dist/Timing-<versionName>-release.apk`，更新 `dist/BUILD-INFO.txt` 与 README 的 SHA-256。

## 3. 安装 / 调试

```powershell
$adb='D:\AndroidDev\sdk\platform-tools\adb.exe'
& $adb install -r 'D:\ai own\Timing\dist\Timing-1.3.0-release.apk'
& $adb shell am start -n com.togotiming.app/.MainActivity

# 截图（先唤醒）
& $adb shell input keyevent KEYCODE_WAKEUP
& $adb shell wm dismiss-keyguard
& $adb shell screencap -p /sdcard/x.png
& $adb pull /sdcard/x.png 'D:\ai own\Timing\dist\x.png'
& $adb shell rm /sdcard/x.png
```

设备：Xiaomi 11 Pro（M2102K1AC），HyperOS 2.0.1 / Android 14，1440×3200。

## 4. 版本与签名

- `app/build.gradle.kts`：`versionCode` 单调递增，`versionName` 语义化。
- 签名配置读取 `keystore.properties`；该文件与 `keystore/` 必须 gitignore。
- Release 开启 `isMinifyEnabled=true` + `shrinkResources=true`；新增反射/序列化类注意 keep。
- 包名固定 `com.togotiming.app`，debug 不加 `applicationIdSuffix`（否则 NFC AAR 包名不匹配）。

## 5. 数据库迁移

- 修改实体字段 → 升 `AppDatabase.version`，写 `Migration`，并 `addMigrations`。
- 禁止 `fallbackToDestructiveMigration`（会清用户数据）。
- 例：v1→v2 增加 `targetMinutes`：
  ```sql
  ALTER TABLE activity_types ADD COLUMN targetMinutes INTEGER NOT NULL DEFAULT 60;
  ALTER TABLE time_records ADD COLUMN targetMinutes INTEGER;
  ```

## 6. 常见坑

1. **Steam++ / hosts**：本机 `github.com` 被 Steam++ 映射到 127.0.0.1；`gh` 联网命令会卡死，`curl` 可能 `CRYPT_E_NO_REVOCATION_CHECK`。GitHub 操作优先用 `git` + 浏览器建仓库，见根目录 `GitHub 提交范本.md`。
2. **Gradle 文件锁**：删除 `app/build` 前先 `.\gradlew.bat --stop`。
3. **Release 覆盖 debug**：签名不同，先卸载旧 debug。
4. **ROM 安装限制**：HyperOS 可能 `INSTALL_FAILED_USER_RESTRICTED`，用文件管理器点 APK 手动允许安装。
5. **通知权限**：重装后 `POST_NOTIFICATIONS` 需重新授予；App 启动会请求。
6. **NFC 写卡**：只读卡（身份证/手环/门禁）无法写入 AAR；后台仅能 TECH，系统会弹确认。可写 NTAG213/215 才能静默。
7. **小部件添加**：HyperOS 需要「桌面快捷方式」AppOp；否则 `requestPinAppWidget` 静默失败，App 会弹出权限引导。
8. **Compose 转场**：NavHost 全 `None`，否则侧滑返回会叠影；`enableOnBackInvokedCallback=false`。
9. **截图黑屏**：设备息屏时 `screencap` 全黑，先 `WAKEUP` + `dismiss-keyguard`。
10. **坐标**：adb `input tap` 用原始分辨率（1440×3200），不是预览图缩放后的坐标。

## 7. 设计稿渲染

设计预览用 HTML + Edge headless：

```powershell
& 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' `
  --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 `
  --window-size=2240,1420 --screenshot="D:\ai own\Timing\design\gantt-preview.png" `
  "file:///D:/ai%20own/Timing/design/preview-gantt.html"
```

## 8. 发布检查清单

- [ ] `versionCode` / `versionName` 更新
- [ ] Room 迁移（如有字段变更）
- [ ] `:app:testDebugUnitTest` 通过
- [ ] `:app:assembleRelease` 通过
- [ ] 真机安装、启动、深色/浅色
- [ ] 计时开始/暂停/继续/结束 + 通知
- [ ] 三个小部件
- [ ] NFC 绑定（可写 NTAG）
- [ ] 分类/记录导入导出
- [ ] `dist/BUILD-INFO.txt` 与 README 更新

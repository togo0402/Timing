# 工具与工作流总结

## 开发环境

| 工具 | 用途 | 位置 |
| --- | --- | --- |
| DSH（DeepSeek Harness） | AI Agent 会话、子代理、MCP、任务 | 当前会话 |
| JDK 17 | Gradle / kotlinc | `D:\AndroidDev\jdk17` |
| Android SDK | platform-36、build-tools 36.0.0 | `D:\AndroidDev\sdk` |
| Gradle wrapper | 构建 | `gradlew.bat` |
| adb | 安装、截图、模拟输入 | `D:\AndroidDev\sdk\platform-tools\adb.exe` |
| apksigner / aapt2 / dexdump | 验签、资源、APK 分析 | build-tools 36.0.0 |
| Edge headless | HTML 设计稿渲染成 PNG | `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe` |
| PowerShell 7 | 脚本、文件整理、图片拼接 | 系统 |
| GitHub 提交范本 | 本机 GitHub 推送流程 | `D:\ai own\GitHub 提交范本.md` |

## MCP / 设计辅助

- `uiux`（ui-ux-pro-mcp）：设计系统、配色、排版、组件建议。
- `wash`（wash-compose-mcp）：Compose 组件文档/示例。
- 仅作参考；最终以项目 `Glass.kt` / `TimingColors` 为准。

## AI 子代理分工（本项目实际使用）

- `stats-ui`：统计页 + 未统计 + 日期导航。
- `types-ui`：分类树、目标、批量删除/拖动。
- `settings-ui`：设置、导入导出、权限。
- `widgets-v11`：W1/W2/W3。
- `home-v11`：首页、时间轴、Gantt、日历。
- `nfc-glass`：NFC 页与批量删除。
- `records-io`：记录导入导出。

## 工作流

1. 读文件（不要凭记忆）→ 改数据层 → 编译。
2. 大改分给子代理，每个子代理限定文件范围，避免写冲突。
3. 子代理自己离线编译校验；Lead 统一跑 Gradle。
4. 真机安装 + 截图确认；adb 坐标用 1440×3200。
5. 版本号 + `dist/BUILD-INFO.txt` + README SHA 更新。

## 设计稿

- HTML/CSS 手机 mockup → Edge headless 截图 → `design/*.png`。
- 实机截图可用 PowerShell `System.Drawing` 拼成对比图。

## 已知本机网络限制

Steam++ 改写 hosts，`gh` 联网命令卡死，`curl api.github.com` 可能证书错误；GitHub 只用 `git` + 浏览器建空仓库（详见根目录范本）。

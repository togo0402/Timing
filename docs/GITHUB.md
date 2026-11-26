# GitHub 发布说明

本机网络受 **Steam++ / hosts** 影响：`github.com` 被映射到 `127.0.0.1`，只有 Steam++ 加速开启时才能连上。当前 `127.0.0.1:443` 未监听，`git ls-remote` 报 `Could not connect to server`，因此**推送需要在 Steam++ 加速开启后执行**。

仓库已就绪：

- 本地 git 仓库已初始化，提交：`feat: Timing 1.3.0 源码归档...`
- 远端已配置：`https://github.com/togo0402/Timing.git`
- 源码包：`dist/Timing-1.3.0-source.zip`（16.6 MB，仅 git 跟踪文件，不含 keystore / local.properties / _local / build）

## 首次推送（推荐流程，见根目录 `GitHub 提交范本.md`）

1. **先在浏览器创建完全空的仓库**（不要勾 README / .gitignore / license）：
   `https://github.com/new` → 名称 `Timing` → Create。
2. 开启 Steam++ 加速（确保 `127.0.0.1:443` 有监听）。
3. 执行：

```powershell
Set-Location 'D:\ai own\Timing'
git push -u origin main
```

首次会弹 Git Credential Manager → 选 **Sign in with a code**，浏览器授权即可。

4. 验证：

```powershell
git ls-remote origin
git status --short --branch
```

## 如果仓库名不是 Timing

```powershell
git remote set-url origin https://github.com/togo0402/<仓库名>.git
git push -u origin main
```

## 附件

- APK：`dist/Timing-1.3.0-release.apk`（已包含在源码 zip；也可在 GitHub Releases 单独上传）
- 文档：`docs/`
- 设计稿：`design/`
- AI skill：`skills/timing-dev/SKILL.md`（已安装到 `~/.dsh/skills/timing-dev/`）

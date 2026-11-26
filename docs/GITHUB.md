# GitHub 发布说明

已发布：<https://github.com/togo0402/Timing>（Public，默认分支 `main`）

Release v1.3.0：<https://github.com/togo0402/Timing/releases/tag/v1.3.0>
（Assets：`Timing-1.3.0-release.apk`、`Timing-1.3.0-source.zip`）

本地 git 仓库、remote、tag 均已同步；`git status` 应为 `## main...origin/main`。

本机网络受 **Steam++ / hosts** 影响：`github.com` 被映射到 `127.0.0.1`，只有 Steam++ 加速开启时才能连上；`gh` 已用设备码登录并 `gh auth setup-git`。

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

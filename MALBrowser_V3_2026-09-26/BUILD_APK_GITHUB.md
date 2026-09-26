# MAL 浏览器正式构建与云端更新

本版已经从临时 Debug 构建方案切换为“固定正式签名 + GitHub Releases + App 内更新”。

## 重要

不要再使用旧的 API 37 / `./gradlew assembleDebug` 工作流。

仓库当前实际结构是：

```text
hotship/MAL-Browser
└─ MALBrowser_V3_2026-09-26
```

因此 GitHub Actions 文件必须放在仓库最外层：

```text
.github/workflows/android.yml
```

本项目目录中提供：

```text
GITHUB_android-release.yml
```

请把它的内容复制到仓库最外层的 `.github/workflows/android.yml`。

它使用：

- Java 17
- Android API 36
- 官方 Gradle 9.6.1 直接下载执行
- GitHub Secrets 恢复正式 keystore
- `assembleRelease`
- `apksigner verify`
- 自动生成 `update.json`
- 自动创建 GitHub Release
- 自动上传正式 APK、SHA256、update.json

详细操作见：`云端更新_你需要在GitHub完成的步骤.txt`。

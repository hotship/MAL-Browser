# MAL 浏览器正式构建与自动更新

本版使用“固定正式签名 + GitHub Releases + App 内自动检测更新”。

## 1. GitHub Actions

项目中提供 `GITHUB_android-release.yml`。把它复制到仓库最外层：

```text
.github/workflows/android.yml
```

新版工作流会自动查找仓库里的 `settings.gradle.kts`，不再写死旧的 `MALBrowser_V3_2026-09-26` 目录名，因此以后项目文件夹改名也不需要同步修改 `working-directory`。

工作流使用：

- Java 17
- Android API 36
- Gradle 9.6.1
- 固定正式 keystore
- `assembleRelease`
- `apksigner verify`
- 自动生成 APK SHA-256
- 自动生成 `update.json`
- 自动创建/更新 GitHub Release

## 2. 更新内容怎么填写

每次发布前只需要编辑项目根目录的：

```text
UPDATE_NOTES.txt
```

把本次更新内容写进去即可。工作流会自动把这段文字同时写入：

- GitHub Release 的更新说明
- Release 附件 `update.json` 的 `changelog`

MAL 2.6 会优先读取 `update.json.changelog`，检测到更高 `versionCode` 后自动弹出更新窗口。因此正常用户无需再进入设置页手动检查。

如果你把原始更新内容发给 ChatGPT，可以先整理润色后再替换 `UPDATE_NOTES.txt`。

## 3. 每次发布必须升级版本号

修改：

```text
app/build.gradle.kts
```

至少把 `versionCode` 增加 1；`versionName` 也建议同步升级。App 判断是否有新版本的关键是 `versionCode`。

## 4. 正式签名不能换

GitHub Secrets 继续使用：

- `MAL_KEYSTORE_BASE64`
- `MAL_KEYSTORE_PASSWORD`
- `MAL_KEY_ALIAS`
- `MAL_KEY_PASSWORD`

只要 `applicationId = com.shixu.minibrowser` 和正式签名保持不变，用户可以直接覆盖升级，Android 会保留应用私有数据库与 WebView 数据。换签名后不能直接覆盖安装旧版本。

## 5. App 自动更新逻辑

- 打开、重新回到 MAL，或从桌面网页快捷方式进入时，都会自动判断是否到检查时间。
- 正常最多约 1 小时检查一次 GitHub 最新 Release。
- 网络检查失败后约 10 分钟即可再次尝试。
- 发现新版本立即弹窗显示更新内容。
- 用户点“稍后”后，同一版本约 6 小时后才会再次提醒，避免频繁打扰。
- 下载 APK 后校验包名和 `versionCode`，再打开 Android 系统安装界面。

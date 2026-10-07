# MAL 浏览器 2.6

MAL 浏览器是一个 Android 原生 WebView 浏览器容器，重点不是搜索，而是把指定网页长期放在一个独立、可管理、可分类的浏览器里，并继续支持固定到手机主屏幕。


## 2.6 重点更新

- 自动更新检测：打开/回到 MAL 或通过桌面网页快捷方式进入时都会按节流自动检查 GitHub Release；发现新版本直接弹窗，不依赖手动检查。更新文案优先读取 `update.json` 的 `changelog`，GitHub Actions 会把 `UPDATE_NOTES.txt` 自动写进去。
- 安卓最近任务稳定性：主浏览器和桌面网页会话都显式保留最近任务，并移除快捷方式启动时过于激进的 `CLEAR_TASK`。
- 桌面快捷方式恢复：重启/覆盖更新后会重新发布系统仍保留的固定快捷方式定义；快捷方式本身仍由系统桌面管理。
- GitHub 备份链路加强：上传后回读并做 SHA-256 校验；恢复支持 GitHub raw / Contents / Git Blob 多级读取，解决大于 1MB 时 `content` 为空的问题。
- 备份内容补齐：网页、分类、图标、桌面快捷方式映射、基础设置、最后网址都进入 MAL 备份。
- 备份频率可选：GitHub 与手机本地备份可分别设置每 6 小时、12 小时、每天、每 3 天、每 7 天。
- 手机备份目录可选：通过系统文件夹选择器指定保存位置，并保留持久读写权限；未选择时仍使用 `Download/MAL/Backups`。
- 新增手机 JSON 恢复入口；自动备份使用后台定时 + 打开 App 时补做的双保险。
- 继续保留 2.5 的分类、幽灵快捷方式墓碑、四列主页、OAuth/网页登录持久化等修复。

## 这版新增 / 重做

### 1. MAL 主界面

- App 名称改为 **MAL浏览器**。
- 全新黑白灰自适应应用图标。
- 启动默认先进入 MAL 主页，不再直接把用户丢回上一次网页。
- 主页可输入网址直接打开。
- 浏览网页时顶部与底部都有“主页”入口；网页没有返回历史时，Android 返回键也会先回 MAL 主页，而不是直接把用户困在网站里。
- 桌面网站 Shortcut 仍保留独立全屏模式，不显示 MAL 浏览器栏。

### 2. 网页库与分类

- 新增网页库 `saved_website`，与原来的桌面快捷方式记录分开保存。
- 新增分类数据库，默认包含“未分类”。
- MAL 首页可以按分类筛选网页。
- 可以新建分类；长按分类可以删除，分类中的网页会迁回“未分类”。
- 长按网页卡片可以：打开、移动分类、添加到桌面、删除 MAL 网页记录。
- 删除网页记录不会清除该网站的 Cookie / LocalStorage / IndexedDB。

### 3. 网页测试

主页点击“网页测试”：

- 输入 URL 后真实加载网页。
- 自动获取最终 URL、网页标题和 favicon。
- 提供网页实时预览。
- 测试完成后可以确认名称并选择分类保存。
- 浏览器网页菜单也可以把当前页直接送到网页测试。

### 4. 浏览器设置

新增独立设置页：

- 启动时进入 MAL 主页 / 恢复上次网页。
- 桌面版网页 User-Agent。
- 网页消息转 Android 系统通知。
- 自动保活。
- 系统通知权限入口。
- 电池优化设置入口。
- 下载能力说明。

### 5. JSON / ZIP / Blob 下载

原版只处理普通 `http/https` 下载，因此网页前端动态生成的 `blob:` JSON、ZIP 等文件无法导出。

MAL 2.0 新增原生下载桥：

- 普通 `http/https`：继续交给 Android DownloadManager。
- `blob:` / `data:`：网页端读取二进制后分块传给 Android 原生层。
- 原生层通过 MediaStore 写入系统公共目录：`Download/MAL`。
- 适用于常见的前端导出 JSON、ZIP、TXT、图片等 Blob 文件。
- 使用分块传输，避免一次把整个大文件 Base64 塞进单次 JS 调用。

### 6. 网页通知与弹窗

MAL 给 WebView 注入 `Notification` 原生桥：

- 网页调用 `new Notification(title, options)` 时可转成 Android 系统通知。
- 对 `ServiceWorkerRegistration.showNotification(...)` 做兼容桥接。
- Android 13+ 会使用系统 `POST_NOTIFICATIONS` 权限。
- 通知通道为高优先级，可显示横幅/弹窗（具体展示仍由用户系统通知设置决定）。

为了尽可能让网页在进入后台后仍能保持连接，MAL 提供用户主动开启的“自动保活”：

- Android 前台服务。
- 独立后台 WebView 复用同一套 Cookie / WebView 数据。
- App 在前台时暂停后台 WebView；App 退到后台时恢复最后一个网页，减少前后台双连接。
- `START_STICKY` 尝试在进程被回收后恢复。
- Partial WakeLock 用于加强后台网络/JS 保活。
- 手机重启或 App 覆盖升级后，如果用户之前开启过保活，会尝试恢复服务。

> Android / 厂商省电策略仍然可能限制或杀死后台进程。设置页提供“电池优化设置”入口。MAL 无法绕过系统强制停止、清除应用数据、卸载 App 等行为。

## 数据保留

继续沿用原 applicationId：

`com.shixu.minibrowser`

这样用同签名 APK 覆盖升级时，原来的 WebView 数据和数据库可以继续保留。

数据库持续采用增量迁移；本版数据库版本为 v3：

- 原 `website_shortcut` 不删除。
- 新增 `website_category`。
- 新增 `saved_website`。
- v3 新增 `deleted_shortcut` 墓碑表，用于阻止已删除快捷方式被旧桌面图标重新写回。

不要卸载旧 App 再安装，否则 Android 会删除旧 App 私有数据。

## 自动构建、正式签名与自动更新

当前正式工作流位于：

`.github/workflows/android.yml`

它会自动寻找 Android 工程、使用 Java 17 / Android API 36 构建正式 Release APK，并使用仓库 Actions Secrets 中保存的固定 keystore 进行签名。构建完成后会验证签名，生成 APK SHA-256、`update.json`，并创建对应 GitHub Release。

以后发新版时：

1. `app/build.gradle.kts` 的 `versionCode` 必须增加。
2. `versionName` 改成要展示给用户的版本号。
3. 把本次更新内容写入 `UPDATE_NOTES.txt`。
4. 提交到 GitHub 后，工作流会把 `UPDATE_NOTES.txt` 同步到 GitHub Release 和 `update.json.changelog`。
5. 用户打开 MAL、重新回到 MAL，或从桌面网页快捷方式进入时，App 会按节流自动检查正式 Release；发现更高 `versionCode` 后直接显示更新弹窗。

正式签名和 `applicationId = com.shixu.minibrowser` 必须长期保持不变，否则 Android 无法覆盖安装旧版本。签名文件、密码和 GitHub Token 都不要提交到公开仓库。

详细步骤见：`BUILD_APK_GITHUB.md` 与 `云端更新_你需要在GitHub完成的步骤.txt`。

package com.shixu.minibrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shixu.minibrowser.databinding.ActivityInfoBinding

class InfoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureInsets()
        binding.backButton.setOnClickListener { finish() }

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_CHANGELOG -> {
                binding.titleText.text = "更新日志"
                binding.contentText.text = CHANGELOG
            }
            else -> {
                binding.titleText.text = "功能介绍"
                binding.contentText.text = FEATURE_GUIDE
            }
        }
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_FEATURES = "features"
        const val MODE_CHANGELOG = "changelog"

        private val FEATURE_GUIDE = """
MAL 浏览器是什么

MAL 是一个偏“网页容器 / 网页 App 管理器”的 Android 浏览器。它不把搜索、资讯和复杂浏览器功能堆在首页，而是把你常用的网页整理成一组像手机 App 一样的入口，并尽量长期保留登录状态、网页数据和桌面快捷方式。

一、首页与网页库

• 首页可以直接输入网址打开，也可以进入“添加网页”流程，把网站保存到 MAL 首页。
• 保存后的网页会自动读取名称和图标；对普通站点、PWA、Vite/React、Netlify、Vercel、Pages 等托管站点都做了额外识别。
• 首页网页现在采用“圆角方形图标 + 下方名称”的启动器样式，不再给每个网页套一整张卡片。
• 可在设置里选择每排显示 3、4、5 或 6 个网页；图标与文字会按列数自动缩放。
• 长按网页图标可以拖动排序，排序结果会写入本地数据库。
• 长按网页名称可打开管理菜单，用于打开、移动到分类、添加到桌面或删除网页记录；长按图标区域则用于拖动排序。
• 删除 MAL 首页里的网页，不会顺便清空该网站的 Cookie、LocalStorage 或 IndexedDB。

二、网页分类

• 默认有“未分类”，也可以自行新建分类。
• 首页可快速切换“全部”和各个分类。
• 分类可以管理、改动或删除；删除分类时，对应网页会回到未分类，避免网页记录直接丢失。
• 分类和网页顺序会保存在本地数据库中，覆盖更新 App 时只要 applicationId 和签名不变，数据可继续保留。

三、网页识别与图标识别

• 名称会综合读取 document.title、application-name、Apple Web App 名称、Open Graph、Twitter 标题、H1 与 PWA manifest 名称。
• 会过滤“Vite + React”“React App”“Netlify App”等常见默认模板标题，减少把技术模板名当成真实站名。
• SPA 页面会在首轮加载后再次识别，兼容 React/Vite 等页面在 hydration 后才写入标题或图标的情况。
• 对 Netlify、Vercel、GitHub Pages 等站点，读取不到正式名称时会优先使用项目子域名，而不是直接显示托管平台名称。
• 图标会尝试 favicon、apple-touch-icon、manifest icons 等多个来源，并优先选择尺寸更合适的图标。
• 支持常见 SVG favicon 转换为可保存的 PNG，减少网页明明有图标但 MAL 首页显示空白的情况。
• 如果网站最终仍没有可用图标，会生成本地兜底图标，保证首页不会出现完全空的入口。

四、网页显示与兼容性

• MAL 使用 Android WebView，并开启 JavaScript、DOM Storage、数据库、Cookie 等常用网页能力。
• 对首屏缩放、宽视口、文字缩放、布局算法和混合内容进行了兼容处理，减少同一网页在 Chrome/Edge 正常、在 MAL 中却比例异常或首屏空白的问题。
• User-Agent 做了兼容优化，尽量避免站点把 MAL 误判为能力受限的 WebView 后返回简化页面。
• 设置中仍可切换“桌面版网页模式”，用于某些只给桌面 UA 提供完整功能的网站。
• 支持网页打开新窗口时回到当前主 WebView，减少登录页、授权页、第三方跳转被卡在空白窗口里的情况。
• 支持常见外部协议跳转；无法在 WebView 中直接打开的链接会尝试交给系统可处理的 App。

五、桌面快捷方式 / PWA 式入口

• 保存到 MAL 的网页可以继续添加到 Android 桌面，形成独立图标入口。
• 桌面快捷方式使用独立任务会话，避免普通浏览器历史和桌面网页会话互相串台。
• 快捷方式会记录网页 URL、名称、图标与本地映射，覆盖更新后会尽量重新同步系统仍保留的固定快捷方式。
• 删除 MAL 网页时会处理本地快捷方式映射，并使用墓碑记录避免旧桌面图标把已经删除的网页重新写回来。
• 桌面网页默认仍可使用“完美全屏”：隐藏系统状态栏与导航栏，只显示网页。
• 新增“网页快捷方式显示顶部状态栏”开关。开启后可看到时间、网络、电量等顶部状态信息，同时继续隐藏底部导航栏；关闭后恢复原来的完美全屏。

六、登录状态与网页数据

• Cookie、LocalStorage、IndexedDB 等 WebView 数据由 Android 应用数据目录持久保存，适合需要长期登录的网页。
• GitHub、Discord 等网页登录流程只要网站自身允许在 Android WebView 中运行，登录状态通常可继续保留。
• 覆盖安装同签名新版 APK 时，数据会继续沿用；卸载 App、清除应用数据或更换 applicationId 会导致 Android 删除对应本地数据。

七、文件上传、相机与网页权限

• 网页的文件选择器可调用 Android 系统文件选择。
• 页面请求拍照上传时，可申请相机权限并返回拍摄结果。
• 网站请求摄像头或麦克风权限时，MAL 会转成 Android 权限请求，再把用户允许的资源授权给网页。
• 权限是否最终可用仍取决于 Android 系统设置、网站自身实现和设备 WebView 版本。

八、下载与导出

• 普通 http/https 文件下载交给 Android DownloadManager。
• 对网页前端动态生成的 blob: / data: 下载，MAL 提供原生下载桥，不再只依赖浏览器默认下载能力。
• JSON、ZIP、TXT、图片等常见 Blob 文件可通过分块方式传给 Android，避免把超大 Base64 一次性塞进单个 JavaScript 调用。
• 默认会把这类原生导出内容保存到系统 Download/MAL 相关目录。

九、网页通知

• 网页调用 Notification 时，MAL 可将其桥接成 Android 系统通知。
• 对部分 ServiceWorkerRegistration.showNotification 场景也做了兼容桥接。
• Android 13 及以上需要用户授予系统通知权限。
• 是否弹横幅、是否显示在锁屏等最终由手机系统的通知设置决定。

十、后台保活

• 设置里可以主动开启“自动保活”。
• MAL 会使用前台服务和后台 WebView 尝试维持选定网页的连接与运行状态。
• App 回到前台时会尽量暂停后台网页，避免前台和后台同时维持两份连接。
• 系统省电策略、厂商后台限制、强制停止、清理后台、极端内存压力都可能影响保活效果；MAL 不能绕过 Android 的系统限制。
• 设置里提供“电池优化设置”入口，方便用户自行调整系统策略。

十一、GitHub 云备份与手机本地备份

• MAL 提供 GitHub 云端备份链路，可保存网页、分类、图标、快捷方式映射、基础设置和最后网址等数据。
• GitHub 备份上传后会进行回读与 SHA-256 校验，尽量避免“看似上传成功但文件实际异常”。
• GitHub 恢复兼容 raw、Contents 与 Git Blob 多级读取，以处理较大的备份文件。
• GitHub 自动备份与手机本地自动备份可以分别设置频率。
• 可单独选择哪些网页进入 GitHub 备份、哪些网页进入手机本地备份。
• 手机本地备份目录可以通过 Android 系统文件夹选择器指定；未指定时使用默认备份位置。
• 支持手机 JSON 恢复，并通过后台定时 + 打开 App 时补做的方式提高自动备份触发率。

十二、应用更新

• MAL 会读取公开 GitHub Releases / update.json 检查新版本。
• 打开 App、回到 App 或从桌面网页快捷方式进入时会按节流策略自动检查。
• 检测到更高 versionCode 后会弹出更新提示；设置页也保留“立即重新检查”。
• 正式覆盖更新时必须保持 applicationId 和正式签名一致，否则 Android 会把它视为另一个 App，无法直接继承旧数据。

十三、这一版的首页与显示设置

• 首页顶部已明显压缩：标题区、快速添加区、分类区都减少了垂直占用，首屏能更早看到“我的网页”。
• 网页入口改为更像手机桌面的圆角图标样式。
• 首页每排数量由用户自己选择，不再固定四列。
• 桌面网页的顶部状态栏是否显示由用户自己决定。
• 设置页新增详细功能介绍和内置更新日志，后续查看功能不必再翻群公告。

使用提醒

MAL 的目标是“让大量常用网页更像 App 一样被长期管理”，不是完整替代 Chrome、Edge 等大型浏览器。遇到依赖 DRM、强安全校验、特定浏览器扩展、系统级 Passkey 流程或网站主动屏蔽 WebView 的页面，仍可能需要外部浏览器完成。
        """.trimIndent()

        private val CHANGELOG = """
MAL 浏览器 2.9.0 · 2026.10.07

【首页样式】
• 网页入口从“整张卡片包住图标和名字”改为手机桌面式布局：圆角方形图标 + 下方名称。
• 保留网页管理与拖动排序：长按名称进入管理，长按图标区域拖动；同时去掉每个网页外层的大卡片边框。
• 首页顶部重新压缩：标题区减高，移除重复设置入口与多余说明；快速打开/添加区、分类区缩小间距和控件高度。
• 底部导航高度同步收紧，首页首屏可容纳更多网页内容。

【首页自定义】
• 新增“首页每排网页”设置。
• 支持每排 3、4、5、6 个网页。
• 不同列数会自动调整网页图标、文字与间距，避免五列、六列时挤出屏幕。
• 设置修改后返回首页立即生效，无需重启 App。

【状态栏】
• 新增“网页快捷方式显示顶部状态栏”开关。
• 开启：桌面快捷方式网页保留 Android 顶部状态栏，可直接看到时间、网络、电量等信息，同时保持底部导航栏隐藏。
• 关闭：继续使用原来的完美全屏，系统状态栏与导航栏都隐藏。
• 对刘海 / 挖孔区域保留正确顶部安全距离，减少内容被状态栏或显示挖孔遮挡。

【设置与帮助】
• 设置页新增“功能介绍（详细）”。
• 设置页新增“更新日志”。
• 功能介绍覆盖首页、分类、网页识别、图标识别、WebView 兼容、桌面快捷方式、登录状态、上传权限、下载、通知、保活、GitHub/本地备份、自动更新等主要能力。

【版本】
• versionName：2.9.0
• versionCode：12


MAL 浏览器 2.8.0 · 2026.10.07

【网页兼容】
• 修复部分网页在 MAL 内与 Edge/Chrome 显示不一致、首屏空白或布局比例异常的问题。
• 调整 WebView 首屏缩放、宽视口、文字缩放与布局算法，并关闭会改写网页颜色的算法暗化。
• 优化网页兼容 UA，减少网站把 MAL 误判为受限 Android WebView 后返回兼容性较差页面的情况。

【名称识别】
• 新增 application-name、Apple Web App 名称、Open Graph、Twitter 标题、H1 与 PWA manifest 名称综合判断。
• 过滤 Vite + React、React App、Netlify App 等默认模板标题。
• 增加 SPA 二次识别，兼容页面 hydration 后才更新 title / icon 的情况。
• 加强 Netlify / Vercel / Pages 等托管站点回退命名，优先使用项目子域名。

【图标识别】
• 按尺寸和类型挑选 favicon / apple-touch-icon。
• 读取 PWA manifest 高清 icons。
• 增加常见 favicon 路径兜底。
• 新增 SVG favicon 转 PNG 兼容。
• 修复收到低清或默认 favicon 后过早停止继续识别的问题。


此前核心能力

• 2.6/2.7：自动更新、最近任务稳定性、固定快捷方式恢复、GitHub 备份回读校验、大文件恢复、备份频率选择、手机备份目录选择、手机 JSON 恢复等。
• 2.5 及更早：网页分类、幽灵快捷方式墓碑、主页网页库、网页登录状态持久化、原生 Blob 下载桥、网页通知桥、后台保活等基础能力。
        """.trimIndent()
    }
}

package com.shixu.minibrowser

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shixu.minibrowser.data.BrowserDatabase
import com.shixu.minibrowser.data.SavedWebsite
import com.shixu.minibrowser.data.WebsiteShortcut
import com.shixu.minibrowser.databinding.ActivityMainBinding
import com.shixu.minibrowser.service.KeepAliveService
import com.shixu.minibrowser.shortcut.ShortcutManagerHelper
import com.shixu.minibrowser.update.UpdateManager
import com.shixu.minibrowser.web.NativeWebBridge
import com.shixu.minibrowser.web.SiteIdentityHelper
import com.shixu.minibrowser.web.UrlTools
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

open class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: BrowserDatabase
    private lateinit var nativeBridge: NativeWebBridge

    private var shortcutMode = false
    private var currentShortcutId: String? = null
    private var lastPageTitle: String = ""
    private var lastFavicon: Bitmap? = null
    private var currentCategory = "全部"
    private var browserVisible = false

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        val uris = if (result.resultCode == RESULT_OK) {
            val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            if (!parsed.isNullOrEmpty()) parsed else cameraPhotoUri?.let { arrayOf(it) }
        } else null
        callback.onReceiveValue(uris)
        fileChooserCallback = null
        cameraPhotoUri = null
    }

    private val cameraPermissionForChooser = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingFileChooserParams?.let { params ->
            pendingFileChooserParams = null
            launchFileChooser(params)
        }
    }

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val request = pendingWebPermissionRequest ?: return@registerForActivityResult
        pendingWebPermissionRequest = null
        val grantedResources = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    results[Manifest.permission.CAMERA] == true ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    results[Manifest.permission.RECORD_AUDIO] == true ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                else -> false
            }
        }.toTypedArray()
        if (grantedResources.isNotEmpty()) request.grant(grantedResources) else request.deny()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = BrowserDatabase(this)
        nativeBridge = NativeWebBridge(this, { binding.webView }, { BrowserPrefs.webNotifications(this) })

        configureSystemInsets()
        configureWebView()
        configureBrowserUi()
        configureBackHandling()
        requestNotificationPermissionOnce()

        if (BrowserPrefs.keepAlive(this)) startKeepAliveService()

        val restored = savedInstanceState?.let { binding.webView.restoreState(it) } != null
        if (restored) {
            shortcutMode = intent.action == ShortcutManagerHelper.ACTION_OPEN_SHORTCUT
            val wasBrowserVisible = savedInstanceState?.getBoolean("browser_visible", false) == true
            if (shortcutMode) applyDisplayMode(true) else if (wasBrowserVisible) showBrowserUi() else showHome()
        } else {
            handleLaunchIntent(intent, initialLaunch = true)
        }

        if (intent.action != ShortcutManagerHelper.ACTION_OPEN_SHORTCUT) {
            binding.root.post { checkForMissingPinnedShortcut() }
            binding.root.postDelayed({
                if (UpdateManager.shouldAutoCheck(this)) {
                    UpdateManager.checkAndPrompt(this, manual = false)
                }
            }, 1200L)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent, initialLaunch = false)
    }

    override fun onStart() {
        super.onStart()
        binding.webView.onResume()
        if (BrowserPrefs.keepAlive(this)) {
            runCatching { startService(Intent(this, KeepAliveService::class.java).setAction(KeepAliveService.ACTION_PAUSE_WEB)) }
        }
    }

    override fun onResume() {
        super.onResume()
        applyUserAgentSetting()
        refreshHome()
        UpdateManager.resumePendingWork(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webView.saveState(outState)
        outState.putBoolean("browser_visible", browserVisible)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        binding.webView.onPause()
        CookieManager.getInstance().flush()
        if (BrowserPrefs.keepAlive(this)) {
            val url = binding.webView.url?.takeIf(UrlTools::isHttpOrHttps) ?: database.getLastUrl()
            val intent = Intent(this, KeepAliveService::class.java)
                .setAction(KeepAliveService.ACTION_RESUME_WEB)
                .putExtra(KeepAliveService.EXTRA_URL, url)
            runCatching { ContextCompat.startForegroundService(this, intent) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
        nativeBridge.close()
        binding.webView.apply {
            stopLoading()
            removeJavascriptInterface(NativeWebBridge.INTERFACE_NAME)
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        database.close()
        super.onDestroy()
    }

    private fun configureSystemInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun configureBrowserUi() {
        binding.homeOpenButton.setOnClickListener { openFromHome() }
        binding.homeUrlInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                openFromHome(); true
            } else false
        }
        binding.addWebsiteButton.setOnClickListener {
            val intent = Intent(this, SiteTestActivity::class.java)
            val draft = binding.homeUrlInput.text?.toString().orEmpty().trim()
            if (draft.isNotBlank()) intent.putExtra(SiteTestActivity.EXTRA_URL, draft)
            startActivity(intent)
        }
        binding.homeTabButton.setOnClickListener { showHome() }
        binding.testTabButton.setOnClickListener { startActivity(Intent(this, SiteTestActivity::class.java)) }
        binding.settingsTabButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.openButton.setOnClickListener { openTypedUrl() }
        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                openTypedUrl(); true
            } else false
        }
        binding.backButton.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.addCategoryButton.setOnClickListener { showAddCategoryDialog() }
        binding.browserMenuButton.setOnClickListener { showBrowserMenu() }
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.webView.canGoBack() && browserVisible -> binding.webView.goBack()
                    shortcutMode -> finish()
                    browserVisible -> showHome()
                    else -> finish()
                }
            }
        })
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = buildUserAgent()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }
        binding.webView.addJavascriptInterface(nativeBridge, NativeWebBridge.INTERFACE_NAME)
        NativeWebBridge.installDocumentStartScript(binding.webView)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(NativeWebBridge.injectionScript(), null)
                lastPageTitle = ""
                lastFavicon = null
                binding.loadingProgress.visibility = View.VISIBLE
                binding.pageTitle.text = "正在打开…"
                binding.pageDomain.text = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }.orEmpty()
                super.onPageStarted(view, url, favicon)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (UrlTools.isHttpOrHttps(url)) false else {
                    openExternalScheme(url); true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(NativeWebBridge.injectionScript(), null)
                binding.loadingProgress.visibility = View.GONE
                if (UrlTools.isHttpOrHttps(url)) {
                    database.saveLastUrl(url)
                    database.markWebsiteOpened(url)
                    if (!shortcutMode) binding.urlInput.setText(url)
                    binding.pageDomain.text = Uri.parse(url).host.orEmpty()
                    if (lastPageTitle.isBlank()) binding.pageTitle.text = binding.pageDomain.text.ifBlank { "网页" }
                    resolveVisiblePageIdentity(view, url)
                    notifyKeepAliveUrl(url)
                }
                updateNavButtons()
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                lastPageTitle = title.orEmpty()
                binding.pageTitle.text = lastPageTitle.ifBlank { "网页" }
                super.onReceivedTitle(view, title)
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                lastFavicon = icon
                super.onReceivedIcon(view, icon)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.loadingProgress.progress = newProgress
                binding.loadingProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                super.onProgressChanged(view, newProgress)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val wantsCameraCapture = fileChooserParams.isCaptureEnabled && acceptsImages(fileChooserParams)
                if (wantsCameraCapture &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingFileChooserParams = fileChooserParams
                    cameraPermissionForChooser.launch(Manifest.permission.CAMERA)
                } else launchFileChooser(fileChooserParams)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { promptWebPermissions(request) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermissionRequest == request) pendingWebPermissionRequest = null
                super.onPermissionRequestCanceled(request)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val tempWebView = WebView(this@MainActivity)
                tempWebView.settings.javaScriptEnabled = true
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(temp: WebView, url: String?, favicon: Bitmap?) {
                        if (!url.isNullOrBlank()) {
                            if (UrlTools.isHttpOrHttps(url)) binding.webView.loadUrl(url) else openExternalScheme(url)
                        }
                        temp.stopLoading(); temp.destroy()
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        binding.webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val fileName = URLUtil.guessFileName("https://mal.local/file", contentDisposition, mimeType)
                binding.webView.evaluateJavascript(
                    "window.__MALSaveBlobUrl && window.__MALSaveBlobUrl(${JSONObject.quote(url)}, ${JSONObject.quote(fileName)});",
                    null
                )
            } else enqueueDownload(url, userAgent, contentDisposition, mimeType)
        })
    }

    private fun handleLaunchIntent(intent: Intent, initialLaunch: Boolean) {
        val shortcutId = if (intent.action == ShortcutManagerHelper.ACTION_OPEN_SHORTCUT) {
            intent.getStringExtra(ShortcutManagerHelper.EXTRA_SHORTCUT_ID)
        } else null
        if (!shortcutId.isNullOrBlank()) {
            val shortcut = database.getShortcut(shortcutId)
            if (shortcut != null) {
                currentShortcutId = shortcutId
                applyDisplayMode(shortcut.displayMode == DISPLAY_FULLSCREEN)
                binding.webView.loadUrl(shortcut.url)
                return
            }
            val fallbackUrl = intent.getStringExtra(ShortcutManagerHelper.EXTRA_SHORTCUT_URL)?.takeIf(UrlTools::isHttpOrHttps)
            if (!fallbackUrl.isNullOrBlank()) {
                val title = intent.getStringExtra(ShortcutManagerHelper.EXTRA_SHORTCUT_TITLE).orEmpty().ifBlank { "网页" }
                val iconPath = intent.getStringExtra(ShortcutManagerHelper.EXTRA_SHORTCUT_ICON_PATH)
                database.upsertShortcut(
                    WebsiteShortcut(
                        id = 0,
                        shortcutId = shortcutId,
                        title = title,
                        url = fallbackUrl,
                        iconPath = iconPath,
                        displayMode = DISPLAY_FULLSCREEN,
                        createdAt = System.currentTimeMillis(),
                        pinConfirmed = true
                    )
                )
                currentShortcutId = shortcutId
                applyDisplayMode(true)
                binding.webView.loadUrl(fallbackUrl)
                Toast.makeText(this, "已自动修复这个快捷方式的本地记录。", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "这个桌面快捷方式缺少本地记录，而且没带上网页地址，所以暂时无法打开。", Toast.LENGTH_LONG).show()
        }

        currentShortcutId = null
        shortcutMode = false
        if (BrowserPrefs.startOnHome(this)) {
            showHome()
        } else if (initialLaunch || binding.webView.url.isNullOrBlank()) {
            val lastUrl = database.getLastUrl()
            if (!lastUrl.isNullOrBlank()) openUrl(lastUrl) else showHome()
        } else showBrowserUi()
    }

    private fun applyDisplayMode(shortcutMode: Boolean) {
        this.shortcutMode = shortcutMode
        browserVisible = true
        binding.homeScreen.visibility = View.GONE
        binding.browserScreen.visibility = View.VISIBLE
        val ui = if (shortcutMode) View.GONE else View.VISIBLE
        binding.browserHeader.visibility = ui
    }

    private fun showBrowserUi() {
        shortcutMode = false
        browserVisible = true
        binding.homeScreen.visibility = View.GONE
        binding.browserScreen.visibility = View.VISIBLE
        binding.browserHeader.visibility = View.VISIBLE
    }

    private fun showHome() {
        if (shortcutMode) {
            finish()
            return
        }
        browserVisible = false
        binding.browserScreen.visibility = View.GONE
        binding.homeScreen.visibility = View.VISIBLE
        refreshHome()
    }

    private fun openFromHome() {
        val normalized = UrlTools.normalize(binding.homeUrlInput.text?.toString().orEmpty())
        if (normalized == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show(); return
        }
        openUrl(normalized)
    }

    private fun openTypedUrl() {
        val normalized = UrlTools.normalize(binding.urlInput.text?.toString().orEmpty())
        if (normalized == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show(); return
        }
        openUrl(normalized)
    }

    private fun openUrl(url: String) {
        showBrowserUi()
        binding.urlInput.setText(url)
        binding.webView.loadUrl(url)
    }

    private fun refreshHome() {
        if (!::binding.isInitialized || !::database.isInitialized) return
        val categories = listOf("全部") + database.getCategories()
        if (currentCategory !in categories) currentCategory = "全部"
        binding.categoryContainer.removeAllViews()
        categories.distinct().forEach { name ->
            val button = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 12f
                setTextColor(if (name == currentCategory) Color.WHITE else ContextCompat.getColor(this@MainActivity, R.color.ink))
                setBackgroundResource(if (name == currentCategory) R.drawable.bg_chip_selected else R.drawable.bg_chip)
                stateListAnimator = null
                minimumHeight = 0
                minHeight = 0
                minimumWidth = 0
                minWidth = 0
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) }
                setOnClickListener { currentCategory = name; refreshHome() }
                if (name != "全部" && name != "未分类") {
                    setOnLongClickListener { showDeleteCategoryDialog(name); true }
                }
            }
            binding.categoryContainer.addView(button)
        }

        val websites = database.getWebsites(currentCategory)
        binding.siteCountText.text = "${websites.size} 个网页"
        binding.emptyState.visibility = if (websites.isEmpty()) View.VISIBLE else View.GONE
        binding.websiteList.removeAllViews()
        binding.websiteList.columnCount = 4
        websites.forEach { binding.websiteList.addView(createWebsiteCard(it)) }
    }

    private fun createWebsiteCard(site: SavedWebsite): View {
        val totalHorizontal = dp(18 * 2)
        val totalGaps = dp(8 * 4)
        val width = ((resources.displayMetrics.widthPixels - totalHorizontal - totalGaps) / 4f).toInt().coerceAtLeast(dp(68))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundResource(R.drawable.bg_card)
            elevation = dp(1).toFloat()
            layoutParams = GridLayout.LayoutParams().apply {
                this.width = width
                this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(0, 0, dp(8), dp(10))
            }
            minimumHeight = dp(104)
            setOnClickListener { openUrl(site.url) }
            setOnLongClickListener { showWebsiteMenu(site); true }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setBackgroundResource(R.drawable.bg_icon_tile)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageBitmap(loadStoredIcon(site.iconPath) ?: SiteIdentityHelper.createFallbackIcon(this@MainActivity, site.title, site.url))
        }
        card.addView(icon)
        card.addView(TextView(this).apply {
            text = site.title
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        })
        return card
    }

    private fun showWebsiteMenu(site: SavedWebsite) {
        val items = arrayOf("打开", "移动到分类", "添加到桌面", "删除网页")
        AlertDialog.Builder(this)
            .setTitle(site.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openUrl(site.url)
                    1 -> chooseCategory(site)
                    2 -> createAndPinShortcut(site.title, site.url, site.iconPath)
                    3 -> AlertDialog.Builder(this)
                        .setTitle("删除网页？")
                        .setMessage("只删除 MAL 主页里的网页记录，不会清除网站 Cookie 或登录数据。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ -> database.deleteWebsite(site.id); refreshHome() }
                        .show()
                }
            }.show()
    }

    private fun chooseCategory(site: SavedWebsite) {
        val categories = database.getCategories().toTypedArray()
        val checked = categories.indexOf(site.category).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("移动到分类")
            .setSingleChoiceItems(categories, checked) { dialog, which ->
                database.updateWebsiteCategory(site.id, categories[which])
                dialog.dismiss(); refreshHome()
            }.show()
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this).apply {
            hint = "例如：小手机 / 工作 / 娱乐"
            setPadding(dp(20), dp(14), dp(20), dp(14))
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle("新建网页分类")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (database.addCategory(name)) {
                    currentCategory = name; refreshHome()
                } else Toast.makeText(this, "分类为空或已经存在。", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showDeleteCategoryDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("删除分类“$name”？")
            .setMessage("分类里的网页会自动移到“未分类”，不会删除网页。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> database.deleteCategory(name); currentCategory = "全部"; refreshHome() }
            .show()
    }

    private fun showBrowserMenu() {
        val items = arrayOf("主页", "前进", "刷新", "添加到桌面", "保存到首页", "添加网页", "新建分类", "浏览器设置")
        AlertDialog.Builder(this)
            .setTitle("网页菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showHome()
                    1 -> if (binding.webView.canGoForward()) binding.webView.goForward()
                         else Toast.makeText(this, "没有可前进的页面。", Toast.LENGTH_SHORT).show()
                    2 -> binding.webView.reload()
                    3 -> showAddShortcutDialog()
                    4 -> saveCurrentPageToLibrary()
                    5 -> startActivity(Intent(this, SiteTestActivity::class.java).putExtra(SiteTestActivity.EXTRA_URL, binding.webView.url))
                    6 -> showAddCategoryDialog()
                    7 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }.show()
    }

    private fun saveCurrentPageToLibrary() {
        val url = binding.webView.url?.takeIf(UrlTools::isHttpOrHttps) ?: run {
            Toast.makeText(this, "当前页面不能保存。", Toast.LENGTH_SHORT).show(); return
        }
        val categories = database.getCategories().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("保存到哪个分类？")
            .setSingleChoiceItems(categories, 0) { dialog, which ->
                val title = lastPageTitle.ifBlank { Uri.parse(url).host.orEmpty().ifBlank { "网页" } }
                val iconPath = saveWebsiteIcon("site_${System.currentTimeMillis()}", lastFavicon ?: SiteIdentityHelper.createFallbackIcon(this, title, url))
                database.upsertWebsite(
                    SavedWebsite(
                        title = title,
                        url = url,
                        iconPath = iconPath,
                        category = categories[which],
                        createdAt = System.currentTimeMillis(),
                        lastOpenedAt = System.currentTimeMillis()
                    )
                )
                dialog.dismiss()
                Toast.makeText(this, "已保存到 ${categories[which]}", Toast.LENGTH_SHORT).show()
                refreshHome()
            }.show()
    }

    private fun showAddShortcutDialog() {
        val url = binding.webView.url?.takeIf(UrlTools::isHttpOrHttps)
        if (url == null) {
            Toast.makeText(this, "当前页面不能添加到桌面。", Toast.LENGTH_SHORT).show(); return
        }
        if (!ShortcutManagerHelper.isPinSupported(this)) {
            Toast.makeText(this, "当前桌面启动器不支持应用内固定快捷方式。", Toast.LENGTH_LONG).show(); return
        }
        val defaultTitle = lastPageTitle.ifBlank { Uri.parse(url).host.orEmpty().ifBlank { "网页" } }
        val input = EditText(this).apply {
            setText(defaultTitle); selectAll(); maxLines = 1; setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("添加到主屏幕")
            .setMessage("桌面入口仍保持无浏览器工具栏的独立网页模式。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加") { _, _ ->
                createAndPinShortcut(input.text?.toString()?.trim().orEmpty().ifBlank { defaultTitle }, url, null)
            }.show()
    }

    private fun createAndPinShortcut(title: String, url: String, storedIconPath: String?) {
        if (!ShortcutManagerHelper.isPinSupported(this)) {
            Toast.makeText(this, "当前桌面启动器不支持固定快捷方式。", Toast.LENGTH_LONG).show(); return
        }
        val shortcutId = "site_${UUID.randomUUID()}"
        val iconPath = storedIconPath
            ?: database.getWebsiteByUrl(url)?.iconPath
            ?: saveShortcutIcon(shortcutId, lastFavicon ?: SiteIdentityHelper.createFallbackIcon(this, title, url))
        val shortcut = WebsiteShortcut(
            id = 0,
            shortcutId = shortcutId,
            title = title,
            url = url,
            iconPath = iconPath,
            displayMode = DISPLAY_FULLSCREEN,
            createdAt = System.currentTimeMillis(),
            pinConfirmed = false
        )
        database.upsertShortcut(shortcut)
        if (ShortcutManagerHelper.requestPin(this, shortcut)) {
            Toast.makeText(this, "请在系统弹窗中确认添加到主屏幕。", Toast.LENGTH_LONG).show()
        } else Toast.makeText(this, "无法请求添加桌面快捷方式。", Toast.LENGTH_LONG).show()
    }

    private fun resolveVisiblePageIdentity(view: WebView, url: String) {
        SiteIdentityHelper.requestPageMeta(view, url) { meta ->
            val resolvedTitle = meta.bestTitle.ifBlank { SiteIdentityHelper.cleanDisplayName(lastPageTitle, url) }
            if (resolvedTitle.isNotBlank()) {
                lastPageTitle = resolvedTitle
                binding.pageTitle.text = resolvedTitle
            }
            if (lastFavicon == null) {
                meta.iconUrl?.let { iconUrl ->
                    SiteIdentityHelper.fetchBitmapAsync(iconUrl) { bitmap ->
                        if (bitmap != null) lastFavicon = bitmap
                    }
                }
            }
        }
    }

    private fun saveShortcutIcon(shortcutId: String, bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return saveBitmapToPrivateFolder("shortcut_icons", "$shortcutId.png", bitmap)
    }

    private fun saveWebsiteIcon(key: String, bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return saveBitmapToPrivateFolder("website_icons", "$key.png", bitmap)
    }

    private fun saveBitmapToPrivateFolder(folder: String, fileName: String, bitmap: Bitmap): String? = runCatching {
        val dir = File(filesDir, folder).apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.relativeTo(filesDir).path
    }.getOrNull()

    private fun loadStoredIcon(iconPath: String?): Bitmap? {
        if (iconPath.isNullOrBlank()) return null
        val file = File(iconPath).let { if (it.isAbsolute) it else File(filesDir, iconPath) }
        return file.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    private fun checkForMissingPinnedShortcut() {
        if (!ShortcutManagerHelper.isPinSupported(this)) return
        val pinnedIds = ShortcutManagerHelper.pinnedIds(this)
        database.getAllShortcuts().filter { !it.pinConfirmed && it.shortcutId in pinnedIds }
            .forEach { database.markPinConfirmed(it.shortcutId) }
        val missing = database.getConfirmedShortcuts().firstOrNull { it.shortcutId !in pinnedIds } ?: return
        AlertDialog.Builder(this)
            .setTitle("检测到桌面快捷方式已失效")
            .setMessage("“${missing.title}”不在系统当前的固定快捷方式列表中，是否重新添加？")
            .setNegativeButton("暂不", null)
            .setPositiveButton("重新添加") { _, _ ->
                if (!ShortcutManagerHelper.requestPin(this, missing.copy(pinConfirmed = false))) {
                    Toast.makeText(this, "无法请求重新添加。", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams) {
        val baseIntent = runCatching { params.createIntent() }.getOrElse {
            fileChooserCallback?.onReceiveValue(null); fileChooserCallback = null
            Toast.makeText(this, "无法打开系统文件选择器。", Toast.LENGTH_SHORT).show(); return
        }
        val extraIntents = mutableListOf<Intent>()
        if (acceptsImages(params) && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            createCameraIntent()?.let(extraIntents::add)
        }
        val chooser = Intent.createChooser(baseIntent, "选择文件").apply {
            if (extraIntents.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
        }
        try { fileChooserLauncher.launch(chooser) }
        catch (_: ActivityNotFoundException) {
            fileChooserCallback?.onReceiveValue(null); fileChooserCallback = null
            Toast.makeText(this, "系统没有可用的文件选择器。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(cameraDir, "IMG_$stamp.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", photoFile)
        cameraPhotoUri = uri
        return intent.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun acceptsImages(params: WebChromeClient.FileChooserParams): Boolean {
        val types = params.acceptTypes.filter { it.isNotBlank() }
        return types.isEmpty() || types.any { it == "*/*" || it.startsWith("image/") }
    }

    private fun promptWebPermissions(request: PermissionRequest) {
        val requestedLabels = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) add("相机")
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) add("麦克风")
        }
        if (requestedLabels.isEmpty()) { request.deny(); return }
        pendingWebPermissionRequest?.takeIf { it != request }?.deny()
        val host = request.origin?.host ?: request.origin?.toString().orEmpty().ifBlank { "当前网页" }
        AlertDialog.Builder(this)
            .setTitle("网页权限请求")
            .setMessage("$host 请求使用${requestedLabels.joinToString("、")}。是否允许？")
            .setNegativeButton("拒绝") { _, _ -> request.deny() }
            .setPositiveButton("允许") { _, _ -> grantWebPermissionsAfterAndroidPermission(request) }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun grantWebPermissionsAfterAndroidPermission(request: PermissionRequest) {
        val androidPermissions = mutableSetOf<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPermissions += Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPermissions += Manifest.permission.RECORD_AUDIO
            }
        }
        val missing = androidPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            request.grant(request.resources.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
            }.toTypedArray())
        } else {
            pendingWebPermissionRequest?.takeIf { it != request }?.deny()
            pendingWebPermissionRequest = request
            webPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!UrlTools.isHttpOrHttps(url)) {
            Toast.makeText(this, "该下载链接无法直接处理。", Toast.LENGTH_LONG).show(); return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription(Uri.parse(url).host ?: "网页下载")
            mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
            .onSuccess { Toast.makeText(this, "已开始下载：$fileName", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "下载启动失败。", Toast.LENGTH_LONG).show() }
    }

    private fun openExternalScheme(url: String) {
        val intent = if (url.startsWith("intent://")) runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent ?: return
        try { if (intent.resolveActivity(packageManager) != null) startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "无法打开这个链接。", Toast.LENGTH_SHORT).show() }
    }

    private fun updateNavButtons() {
        binding.backButton.isEnabled = binding.webView.canGoBack()
        binding.backButton.alpha = if (binding.backButton.isEnabled) 1f else 0.35f
    }

    private fun applyUserAgentSetting() {
        binding.webView.settings.userAgentString = buildUserAgent()
    }

    private fun buildUserAgent(): String {
        val base = WebSettings.getDefaultUserAgent(this)
        return if (BrowserPrefs.desktopMode(this)) base.replace("Mobile", "").replace("; wv", "") else base
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            BrowserPrefs.webNotifications(this) &&
            !BrowserPrefs.notificationPermissionAsked(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            BrowserPrefs.setNotificationPermissionAsked(this, true)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startKeepAliveService() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java)) }
    }

    private fun notifyKeepAliveUrl(url: String) {
        if (!BrowserPrefs.keepAlive(this)) return
        runCatching {
            startService(Intent(this, KeepAliveService::class.java)
                .setAction(KeepAliveService.ACTION_UPDATE_URL)
                .putExtra(KeepAliveService.EXTRA_URL, url))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DISPLAY_FULLSCREEN = "fullscreen"
    }
}

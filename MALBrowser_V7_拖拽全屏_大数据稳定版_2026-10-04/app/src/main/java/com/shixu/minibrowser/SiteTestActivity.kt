package com.shixu.minibrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shixu.minibrowser.data.BrowserDatabase
import com.shixu.minibrowser.data.SavedWebsite
import com.shixu.minibrowser.databinding.ActivitySiteTestBinding
import com.shixu.minibrowser.web.NativeWebBridge
import com.shixu.minibrowser.web.SiteIdentityHelper
import com.shixu.minibrowser.web.UrlTools
import java.io.File
import java.io.FileOutputStream

class SiteTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySiteTestBinding
    private lateinit var database: BrowserDatabase
    private lateinit var bridge: NativeWebBridge
    private var lastTitle = ""
    private var lastIcon: Bitmap? = null
    private var customIcon: Bitmap? = null
    private var testedUrl: String? = null
    private var selectedCategory = "未分类"
    private var rendererGone = false

    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.let { bitmap ->
            customIcon = bitmap
            binding.editIconPreview.setImageBitmap(bitmap)
            binding.faviconView.setImageBitmap(bitmap)
        } ?: Toast.makeText(this, "图标读取失败。", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySiteTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = BrowserDatabase(this)
        bridge = NativeWebBridge(this, { binding.webView }, { BrowserPrefs.webNotifications(this) })
        configureInsets()
        configureWebView()
        bindActions()
        if (database.getCategories().isEmpty()) database.addCategory("未分类")
        selectedCategory = database.getCategories().firstOrNull() ?: "未分类"
        binding.categoryButton.text = selectedCategory

        intent.getStringExtra(EXTRA_URL)?.let { incoming ->
            binding.urlInput.setText(incoming)
            UrlTools.normalize(incoming)?.let { testUrl(it) }
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

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = true
            val base = WebSettings.getDefaultUserAgent(this@SiteTestActivity)
            userAgentString = if (BrowserPrefs.desktopMode(this@SiteTestActivity)) base.replace("Mobile", "").replace("; wv", "") else base
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }
        binding.webView.addJavascriptInterface(bridge, NativeWebBridge.INTERFACE_NAME)
        NativeWebBridge.installDocumentStartScript(binding.webView)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(NativeWebBridge.injectionScript(), null)
                super.onPageStarted(view, url, favicon)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !UrlTools.isHttpOrHttps(request.url.toString())
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(NativeWebBridge.injectionScript(), null)
                testedUrl = url.takeIf(UrlTools::isHttpOrHttps)
                val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
                if (lastTitle.isBlank()) lastTitle = host.ifBlank { "网页" }
                if (binding.siteNameInput.text.isNullOrBlank()) binding.siteNameInput.setText(lastTitle)
                binding.siteTitle.text = lastTitle
                binding.statusText.text = "已识别网页 · $host · 正在校正名称和图标"
                resolveTestedSiteIdentity(view, url)
                binding.saveButton.isEnabled = testedUrl != null
                binding.saveButton.alpha = if (binding.saveButton.isEnabled) 1f else 0.45f
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val retryUrl = testedUrl ?: runCatching { view.url }.getOrNull()
                rendererGone = true
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
                retryUrl?.takeIf(UrlTools::isHttpOrHttps)?.let { intent.putExtra(EXTRA_URL, it) }
                Toast.makeText(this@SiteTestActivity, "网页内核已自动恢复，请重新识别。", Toast.LENGTH_SHORT).show()
                binding.root.post { if (!isFinishing && !isDestroyed) recreate() }
                return true
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                lastTitle = title?.trim().orEmpty()
                if (lastTitle.isNotBlank()) {
                    binding.siteTitle.text = lastTitle
                    if (binding.siteNameInput.text.isNullOrBlank()) binding.siteNameInput.setText(lastTitle)
                }
                super.onReceivedTitle(view, title)
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                lastIcon = icon
                if (icon != null && customIcon == null) {
                    binding.faviconView.setImageBitmap(icon)
                    binding.editIconPreview.setImageBitmap(icon)
                }
                super.onReceivedIcon(view, icon)
            }
        }
    }

    private fun bindActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.testButton.setOnClickListener { normalizeAndTest() }
        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                normalizeAndTest(); true
            } else false
        }
        binding.categoryButton.setOnClickListener { chooseCategory() }
        binding.iconPickerButton.setOnClickListener { iconPicker.launch("image/*") }
        binding.saveButton.setOnClickListener { saveTestedSite() }
    }

    private fun chooseCategory() {
        if (database.getCategories().isEmpty()) database.addCategory("未分类")
        val categories = database.getCategories().toTypedArray()
        val checked = categories.indexOf(selectedCategory).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择分类")
            .setSingleChoiceItems(categories, checked) { dialog, which ->
                selectedCategory = categories[which]
                binding.categoryButton.text = selectedCategory
                dialog.dismiss()
            }
            .show()
    }

    private fun normalizeAndTest() {
        val url = UrlTools.normalize(binding.urlInput.text?.toString().orEmpty())
        if (url == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show(); return
        }
        testUrl(url)
    }

    private fun testUrl(url: String) {
        lastTitle = ""
        lastIcon = null
        customIcon = null
        testedUrl = null
        binding.siteTitle.text = "正在识别网页…"
        binding.statusText.text = "正在识别网页名称和图标"
        binding.siteNameInput.setText("")
        binding.faviconView.setImageResource(R.mipmap.ic_launcher)
        binding.editIconPreview.setImageResource(R.mipmap.ic_launcher)
        binding.saveButton.isEnabled = false
        binding.saveButton.alpha = 0.45f
        binding.webView.loadUrl(url)
    }

    private fun saveTestedSite() {
        val url = testedUrl ?: return
        val finalTitle = binding.siteNameInput.text?.toString()?.trim().orEmpty()
            .ifBlank { lastTitle.ifBlank { Uri.parse(url).host.orEmpty().ifBlank { "网页" } } }
        val iconPath = saveIcon(customIcon ?: lastIcon ?: SiteIdentityHelper.createFallbackIcon(this, finalTitle, url))
        database.upsertWebsite(
            SavedWebsite(
                title = finalTitle,
                url = url,
                iconPath = iconPath,
                category = selectedCategory,
                createdAt = System.currentTimeMillis(),
                lastOpenedAt = 0
            )
        )
        Toast.makeText(this, "已保存到首页 · $selectedCategory", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resolveTestedSiteIdentity(view: WebView, url: String) {
        SiteIdentityHelper.requestPageMeta(view, url) { meta ->
            val betterTitle = meta.bestTitle.ifBlank { SiteIdentityHelper.cleanDisplayName(lastTitle, url) }
            if (betterTitle.isNotBlank()) {
                lastTitle = betterTitle
                binding.siteTitle.text = betterTitle
                val currentInput = binding.siteNameInput.text?.toString().orEmpty().trim()
                if (currentInput.isBlank() || currentInput == testedUrl || currentInput == runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")) {
                    binding.siteNameInput.setText(betterTitle)
                }
            }
            if (customIcon == null && lastIcon == null) {
                meta.iconUrl?.let { iconUrl ->
                    SiteIdentityHelper.fetchBitmapAsync(iconUrl) { bitmap ->
                        if (bitmap != null && customIcon == null) {
                            lastIcon = bitmap
                            binding.faviconView.setImageBitmap(bitmap)
                            binding.editIconPreview.setImageBitmap(bitmap)
                            binding.statusText.text = "已识别完成 · 已校正网页名称和图标"
                        } else {
                            binding.statusText.text = "已识别完成 · 已校正网页名称"
                        }
                    }
                } ?: run {
                    binding.statusText.text = "已识别完成 · 已校正网页名称"
                }
            } else {
                binding.statusText.text = "已识别完成 · 已校正网页名称和图标"
            }
        }
    }

    private fun saveIcon(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return runCatching {
            val dir = File(filesDir, "website_icons").apply { mkdirs() }
            val file = File(dir, "tested_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.relativeTo(filesDir).path
        }.getOrNull()
    }

    override fun onDestroy() {
        bridge.close()
        if (!rendererGone) {
            runCatching {
                binding.webView.apply {
                    stopLoading()
                    removeJavascriptInterface(NativeWebBridge.INTERFACE_NAME)
                    destroy()
                }
            }
        }
        database.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}

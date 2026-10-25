package com.shixu.minibrowser.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import com.shixu.minibrowser.BrowserPrefs
import com.shixu.minibrowser.MainActivity
import com.shixu.minibrowser.R
import com.shixu.minibrowser.data.BrowserDatabase
import com.shixu.minibrowser.web.NativeWebBridge
import com.shixu.minibrowser.web.UrlTools
import com.shixu.minibrowser.web.WebViewCompatibility

class KeepAliveService : Service() {
    private var webView: WebView? = null
    private var bridge: NativeWebBridge? = null
    private var database: BrowserDatabase? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var backgroundActive = true

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundSafely()
        database = BrowserDatabase(this)
        createBackgroundWebView()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BrowserPrefs.keepAlive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafely()
        when (intent?.action) {
            ACTION_PAUSE_WEB -> pauseBackgroundWebView()
            ACTION_RESUME_WEB -> {
                val url = intent.getStringExtra(EXTRA_URL)
                resumeBackgroundWebView(url)
            }
            ACTION_UPDATE_URL -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (backgroundActive && !url.isNullOrBlank()) loadIfNeeded(url)
            }
            else -> resumeBackgroundWebView(database?.getLastUrl())
        }
        return START_STICKY
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createBackgroundWebView() {
        if (webView != null) return
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = WebViewCompatibility.buildBrowserLikeUserAgent(this@KeepAliveService)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript(NativeWebBridge.injectionScript(), null)
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (UrlTools.isHttpOrHttps(url)) database?.saveLastUrl(url)
                    view.evaluateJavascript(NativeWebBridge.injectionScript(), null)
                    super.onPageFinished(view, url)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val resumeUrl = runCatching { view.url }.getOrNull()?.takeIf(UrlTools::isHttpOrHttps)
                        ?: database?.getLastUrl()?.takeIf(UrlTools::isHttpOrHttps)
                    runCatching { view.destroy() }
                    if (webView === view) {
                        webView = null
                        bridge?.close()
                        bridge = null
                        Handler(Looper.getMainLooper()).post {
                            if (!BrowserPrefs.keepAlive(this@KeepAliveService)) return@post
                            createBackgroundWebView()
                            if (backgroundActive && !resumeUrl.isNullOrBlank()) loadIfNeeded(resumeUrl)
                        }
                    }
                    return true
                }
            }
        }
        bridge = NativeWebBridge(this, { webView }, { BrowserPrefs.webNotifications(this) })
        webView?.addJavascriptInterface(bridge!!, NativeWebBridge.INTERFACE_NAME)
        webView?.let { NativeWebBridge.installDocumentStartScript(it) }
    }

    private fun pauseBackgroundWebView() {
        backgroundActive = false
        webView?.onPause()
    }

    private fun resumeBackgroundWebView(url: String?) {
        backgroundActive = true
        webView?.onResume()
        if (!url.isNullOrBlank()) loadIfNeeded(url)
    }

    private fun loadIfNeeded(url: String) {
        if (!UrlTools.isHttpOrHttps(url)) return
        if (webView?.url != url) webView?.loadUrl(url)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAL:WebKeepAlive").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundSafely() {
        val openIntent = PendingIntent.getActivity(
            this,
            7001,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_notification_mal)
            .setContentTitle("MAL 浏览器正在保活")
            .setContentText("网页连接与原生消息桥保持运行")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_KEEP_ALIVE, "网页保活", NotificationManager.IMPORTANCE_LOW).apply {
                description = "MAL 浏览器用户主动开启的后台网页保活"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        bridge?.close()
        bridge = null
        webView?.apply {
            stopLoading()
            removeJavascriptInterface(NativeWebBridge.INTERFACE_NAME)
            destroy()
        }
        webView = null
        database?.close()
        database = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PAUSE_WEB = "com.shixu.minibrowser.KEEPALIVE_PAUSE"
        const val ACTION_RESUME_WEB = "com.shixu.minibrowser.KEEPALIVE_RESUME"
        const val ACTION_UPDATE_URL = "com.shixu.minibrowser.KEEPALIVE_UPDATE_URL"
        const val EXTRA_URL = "url"
        private const val CHANNEL_KEEP_ALIVE = "mal_keep_alive"
        private const val NOTIFICATION_ID = 7101
    }
}

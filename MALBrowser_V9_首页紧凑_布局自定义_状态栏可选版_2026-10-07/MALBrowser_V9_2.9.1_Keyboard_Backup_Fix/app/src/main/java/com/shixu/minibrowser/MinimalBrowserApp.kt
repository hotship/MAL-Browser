package com.shixu.minibrowser

import android.app.Application
import android.webkit.CookieManager

class MinimalBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Never clear cookies or WebView storage here. The point of this app is persistence.
        CookieManager.getInstance().setAcceptCookie(true)
    }
}

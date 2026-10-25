package com.shixu.minibrowser.web

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.shixu.minibrowser.BrowserPrefs

/**
 * Central WebView compatibility profile.
 *
 * The goal is to stay close to a normal mobile Chromium browser instead of the
 * raw Android WebView defaults. Some modern sites (especially small PWA/Netlify
 * sites) behave differently when they detect the `wv` UA token or when the
 * layout viewport is not fitted on first load.
 */
object WebViewCompatibility {

    @Suppress("SetJavaScriptEnabled")
    fun applyVisibleProfile(
        webView: WebView,
        context: Context,
        supportMultipleWindows: Boolean = true,
        mediaPlaybackRequiresGesture: Boolean = true
    ) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT

            loadsImagesAutomatically = true
            blockNetworkImage = false
            blockNetworkLoads = false
            allowFileAccess = false
            allowContentAccess = true

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(supportMultipleWindows)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = mediaPlaybackRequiresGesture

            // Keep CSS/layout behavior close to Chrome/Edge on Android.
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            defaultTextEncodingName = "UTF-8"

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            userAgentString = buildBrowserLikeUserAgent(context)
        }

        // Do not let app/theme darkening rewrite page colors. It can break
        // carefully positioned/light visual pages and differs from standalone browsers.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
            }
        }

        // 0 asks WebView to calculate the initial scale from viewport metadata.
        webView.setInitialScale(0)
    }

    fun buildBrowserLikeUserAgent(context: Context): String {
        var ua = WebSettings.getDefaultUserAgent(context)

        // Android System WebView advertises "; wv" and "Version/4.0". A number
        // of sites serve a reduced/incompatible embedded view for that UA even
        // though the underlying Chromium engine can render the normal mobile page.
        ua = ua.replace(Regex(";\\s*wv(?=\\))", RegexOption.IGNORE_CASE), "")
        ua = ua.replace(Regex("\\bVersion/4\\.0\\s+", RegexOption.IGNORE_CASE), "")

        if (BrowserPrefs.desktopMode(context)) {
            ua = ua.replace(Regex("\\sMobile(?=\\s|$)", RegexOption.IGNORE_CASE), "")
        }

        return ua.replace(Regex("\\s{2,}"), " ").trim()
    }
}

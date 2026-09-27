package com.shixu.minibrowser.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

object SiteIdentityHelper {

    data class PageMeta(
        val bestTitle: String,
        val rawTitle: String,
        val iconUrl: String?
    )

    fun requestPageMeta(webView: WebView, pageUrl: String, onResult: (PageMeta) -> Unit) {
        val script = """
            (function(){
              function pick(sel, attr){
                var node = document.querySelector(sel);
                if (!node) return '';
                if (!attr) return (node.textContent || '').trim();
                return (node.getAttribute(attr) || '').trim();
              }
              function abs(url){
                try { return url ? new URL(url, location.href).href : ''; } catch(e) { return ''; }
              }
              var title = (document.title || '').trim();
              var ogSite = pick('meta[property="og:site_name"]', 'content');
              var appName = pick('meta[name="application-name"]', 'content');
              var shortName = pick('meta[name="apple-mobile-web-app-title"]', 'content');
              var candidate = ogSite || appName || shortName || title || location.hostname || '';
              var iconHref = '';
              var iconSelectors = [
                'link[rel="apple-touch-icon"]',
                'link[rel="apple-touch-icon-precomposed"]',
                'link[rel="icon"]',
                'link[rel="shortcut icon"]',
                'link[rel~="icon"]'
              ];
              for (var i = 0; i < iconSelectors.length; i++) {
                var node = document.querySelector(iconSelectors[i]);
                if (node && node.getAttribute('href')) { iconHref = node.getAttribute('href'); break; }
              }
              iconHref = abs(iconHref);
              if (!iconHref) {
                try { iconHref = location.origin + '/favicon.ico'; } catch(e) {}
              }
              return [candidate, title, iconHref || ''].join('\u001F');
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeJsResult(raw).orEmpty()
            val parts = decoded.split('')
            val best = cleanDisplayName(parts.getOrNull(0), pageUrl)
            val rawTitle = cleanDisplayName(parts.getOrNull(1), pageUrl)
            val iconUrl = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            onResult(PageMeta(best, rawTitle, iconUrl))
        }
    }

    fun fetchBitmapAsync(url: String, onResult: (Bitmap?) -> Unit) {
        Thread {
            val bitmap = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "MALBrowser/2")
                }
                connection.connect()
                connection.inputStream.use { input ->
                    android.graphics.BitmapFactory.decodeStream(BufferedInputStream(input))
                }
            }.getOrNull()
            Handler(Looper.getMainLooper()).post { onResult(bitmap) }
        }.start()
    }

    fun createFallbackIcon(context: Context, label: String, url: String): Bitmap {
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = paint(Color.parseColor(pickBackground(url)))
        val inset = size * 0.06f
        canvas.drawRoundRect(RectF(inset, inset, size - inset, size - inset), size * 0.26f, size * 0.26f, background)

        val text = firstGlyph(label, url)
        val textPaint = paint(Color.WHITE).apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = size * 0.40f
            isFakeBoldText = true
        }
        val x = size / 2f
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
        return bitmap
    }

    fun cleanDisplayName(raw: String?, url: String): String {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val hostLabel = host.removePrefix("www.").substringBefore('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val candidate = raw.orEmpty().trim()
            .replace(Regex("\\s*[|｜·•-]\\s*[^|｜·•-]{1,20}$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            candidate.isBlank() -> if (hostLabel.isNotBlank()) hostLabel else "网页"
            candidate.length > 30 -> candidate.take(30).trim()
            else -> candidate
        }
    }

    private fun decodeJsResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
    }

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun firstGlyph(label: String, url: String): String {
        val source = label.trim().ifBlank {
            runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        }
        val ch = source.firstOrNull { it.isLetterOrDigit() || it.code > 127 } ?: 'W'
        return ch.uppercase()
    }

    private fun pickBackground(url: String): String {
        val palette = listOf("#7A8AA0", "#8F7CA8", "#8E9E75", "#C08E6D", "#6F97A8", "#B18BA5")
        val index = (url.hashCode().absoluteValue) % palette.size
        return palette[index]
    }

    private val Int.absoluteValue: Int
        get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
}

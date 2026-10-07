package com.shixu.minibrowser.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SiteIdentityHelper {

    data class PageMeta(
        val bestTitle: String,
        val rawTitle: String,
        val iconUrls: List<String>
    ) {
        val iconUrl: String?
            get() = iconUrls.firstOrNull()
    }

    private data class ManifestMeta(
        val titleCandidates: List<String>,
        val iconUrls: List<String>
    )

    /**
     * Read identity data from the loaded DOM, then (when present) enrich it from
     * the PWA manifest. The callback can run twice: once immediately with DOM data
     * and once with higher-quality manifest data.
     */
    fun requestPageMeta(webView: WebView, pageUrl: String, onResult: (PageMeta) -> Unit) {
        val script = """
            (function(){
              function text(sel, attr){
                var node = document.querySelector(sel);
                if (!node) return '';
                if (attr) return String(node.getAttribute(attr) || '').trim();
                return String(node.textContent || '').trim();
              }
              function abs(url){
                try { return url ? new URL(url, location.href).href : ''; } catch(e) { return ''; }
              }
              function maxSize(value){
                if (!value || value === 'any') return 0;
                var best = 0;
                String(value).split(/\\s+/).forEach(function(part){
                  var m = part.match(/^(\\d+)x(\\d+)$/i);
                  if (m) best = Math.max(best, parseInt(m[1] || '0',10), parseInt(m[2] || '0',10));
                });
                return best;
              }

              var title = String(document.title || '').trim();
              var appName = text('meta[name="application-name"]', 'content');
              var appleTitle = text('meta[name="apple-mobile-web-app-title"]', 'content');
              var ogSite = text('meta[property="og:site_name"]', 'content');
              var ogTitle = text('meta[property="og:title"]', 'content');
              var twitterTitle = text('meta[name="twitter:title"]', 'content');
              var h1 = text('h1', null);
              var manifestUrl = abs(text('link[rel~="manifest"]', 'href'));

              var icons = [];
              Array.prototype.slice.call(document.querySelectorAll('link[rel]')).forEach(function(node){
                var rel = String(node.getAttribute('rel') || '').toLowerCase();
                if (rel.indexOf('icon') < 0) return;
                var href = abs(node.getAttribute('href'));
                if (!href) return;
                var score = maxSize(node.getAttribute('sizes'));
                if (rel.indexOf('apple-touch-icon') >= 0) score += 10000;
                else if (rel.indexOf('shortcut') >= 0) score += 2000;
                else score += 5000;
                icons.push({u:href,s:score});
              });
              icons.sort(function(a,b){ return b.s-a.s; });

              var iconUrls = [];
              icons.forEach(function(item){ if (iconUrls.indexOf(item.u) < 0) iconUrls.push(item.u); });

              var origin = '';
              try { origin = location.origin; } catch(e) {}
              if (origin) {
                [
                  '/apple-touch-icon.png',
                  '/favicon-192.png',
                  '/favicon.png',
                  '/favicon.ico'
                ].forEach(function(path){
                  var u = origin + path;
                  if (iconUrls.indexOf(u) < 0) iconUrls.push(u);
                });
              }

              var payload = {
                title: title,
                candidates: [appName, appleTitle, ogSite, title, ogTitle, twitterTitle, h1],
                iconUrls: iconUrls,
                manifestUrl: manifestUrl
              };
              return JSON.stringify(payload);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            val decoded = decodeJsResult(raw).orEmpty()
            val json = runCatching { JSONObject(decoded) }.getOrNull()
            if (json == null) {
                onResult(
                    PageMeta(
                        cleanDisplayName(null, pageUrl),
                        "",
                        defaultIconCandidates(pageUrl)
                    )
                )
                return@evaluateJavascript
            }

            val rawTitle = json.optString("title").trim()
            val domCandidates = json.optJSONArray("candidates").toStringList()
            val domIcons = (json.optJSONArray("iconUrls").toStringList() + defaultIconCandidates(pageUrl)).distinct()
            val domMeta = PageMeta(
                bestTitle = chooseBestTitle(domCandidates, pageUrl),
                rawTitle = cleanDisplayName(rawTitle, pageUrl),
                iconUrls = domIcons
            )
            onResult(domMeta)

            val manifestUrl = json.optString("manifestUrl").trim().takeIf { it.isNotBlank() } ?: return@evaluateJavascript
            val userAgent = runCatching { webView.settings.userAgentString }.getOrNull().orEmpty()
            val cookie = runCatching { CookieManager.getInstance().getCookie(manifestUrl) }.getOrNull()

            Thread {
                val manifest = fetchManifest(manifestUrl, userAgent, cookie, pageUrl) ?: return@Thread
                val enrichedTitle = chooseBestTitle(manifest.titleCandidates + domCandidates, pageUrl)
                val enrichedIcons = (manifest.iconUrls + domIcons).distinct()
                val enriched = PageMeta(enrichedTitle, domMeta.rawTitle, enrichedIcons)
                if (enriched != domMeta) {
                    Handler(Looper.getMainLooper()).post { onResult(enriched) }
                }
            }.start()
        }
    }

    fun fetchBitmapAsync(url: String, onResult: (Bitmap?) -> Unit) {
        fetchBestBitmapAsync(listOf(url), null, null, onResult)
    }

    /**
     * Resolve a page icon using both native HTTP decoding and the live WebView.
     * The WebView path is important for SVG favicons (very common on Vite/Netlify),
     * which Android BitmapFactory cannot decode directly.
     */
    fun fetchBestBitmapForPage(
        webView: WebView,
        urls: List<String>,
        referer: String? = null,
        onResult: (Bitmap?) -> Unit
    ) {
        val candidates = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12)
        val svgCandidates = candidates.filter(::isSvgCandidate).take(4)
        val remaining = AtomicInteger(if (svgCandidates.isNotEmpty()) 2 else 1)
        val delivered = AtomicBoolean(false)

        fun finish(bitmap: Bitmap?) {
            if (bitmap != null && delivered.compareAndSet(false, true)) {
                onResult(bitmap)
                return
            }
            if (remaining.decrementAndGet() == 0 && delivered.compareAndSet(false, true)) {
                onResult(null)
            }
        }

        fetchBestBitmapAsync(
            urls = candidates,
            userAgent = runCatching { webView.settings.userAgentString }.getOrNull(),
            referer = referer
        ) { finish(it) }

        if (svgCandidates.isNotEmpty()) {
            rasterizeSvgCandidates(webView, svgCandidates) { finish(it) }
        }
    }

    /** Try multiple icon URLs in order and return the first decodable bitmap. */
    fun fetchBestBitmapAsync(
        urls: List<String>,
        userAgent: String? = null,
        referer: String? = null,
        onResult: (Bitmap?) -> Unit
    ) {
        val candidates = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12)
        Thread {
            var result: Bitmap? = null
            for (url in candidates) {
                result = runCatching { fetchBitmap(url, userAgent, referer) }.getOrNull()
                if (result != null && result.width > 0 && result.height > 0) break
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun rasterizeSvgCandidates(webView: WebView, urls: List<String>, onResult: (Bitmap?) -> Unit) {
        if (urls.isEmpty()) {
            onResult(null)
            return
        }
        val bridgeName = "MALIdentity_${System.nanoTime().toString().replace('-', '0')}"
        val completed = AtomicBoolean(false)
        val bridge = IconRasterBridge { dataUrl ->
            if (completed.compareAndSet(false, true)) {
                Handler(Looper.getMainLooper()).post {
                    runCatching { webView.removeJavascriptInterface(bridgeName) }
                    onResult(dataUrl?.let(::decodeDataUrl))
                }
            }
        }
        val bridgeAdded = runCatching { webView.addJavascriptInterface(bridge, bridgeName) }.isSuccess
        if (!bridgeAdded) {
            onResult(null)
            return
        }

        val array = JSONArray().apply { urls.forEach { put(it) } }.toString()
        val quotedBridge = JSONObject.quote(bridgeName)
        val script = """
            (function(){
              var urls = $array;
              var bridge = window[$quotedBridge];
              if (!bridge || !bridge.complete) return;
              var finished = false;
              function done(value){ if (finished) return; finished = true; try { bridge.complete(value || ''); } catch(e) {} }
              function tryAt(index){
                if (index >= urls.length) { done(''); return; }
                var img = new Image();
                var timer = setTimeout(function(){ cleanup(); tryAt(index + 1); }, 1800);
                function cleanup(){ clearTimeout(timer); img.onload = null; img.onerror = null; }
                img.onload = function(){
                  try {
                    cleanup();
                    var w = img.naturalWidth || img.width || 192;
                    var h = img.naturalHeight || img.height || 192;
                    var size = 192;
                    var canvas = document.createElement('canvas');
                    canvas.width = size; canvas.height = size;
                    var ctx = canvas.getContext('2d');
                    ctx.clearRect(0,0,size,size);
                    var scale = Math.min(size / Math.max(1,w), size / Math.max(1,h));
                    var dw = Math.max(1, Math.round(w * scale));
                    var dh = Math.max(1, Math.round(h * scale));
                    ctx.drawImage(img, Math.round((size-dw)/2), Math.round((size-dh)/2), dw, dh);
                    done(canvas.toDataURL('image/png'));
                  } catch(e) { tryAt(index + 1); }
                };
                img.onerror = function(){ cleanup(); tryAt(index + 1); };
                try { img.crossOrigin = 'anonymous'; } catch(e) {}
                img.src = urls[index];
              }
              tryAt(0);
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure {
                if (completed.compareAndSet(false, true)) {
                    runCatching { webView.removeJavascriptInterface(bridgeName) }
                    onResult(null)
                }
            }
    }

    class IconRasterBridge(private val callback: (String?) -> Unit) {
        @JavascriptInterface
        fun complete(dataUrl: String?) {
            callback(dataUrl)
        }
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
        val fallback = hostDisplayName(url)
        val candidate = raw.orEmpty().trim()
            .replace(Regex("\\s*[|｜·•]\\s*[^|｜·•]{1,30}$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            candidate.isBlank() || isGenericTitle(candidate, url) -> fallback
            candidate.length > 40 -> candidate.take(40).trim()
            else -> candidate
        }
    }

    private fun chooseBestTitle(candidates: List<String>, url: String): String {
        val fallback = hostDisplayName(url)
        for (raw in candidates) {
            val value = raw.trim().replace(Regex("\\s+"), " ")
            if (value.isBlank() || isGenericTitle(value, url)) continue
            val cleaned = cleanDisplayName(value, url)
            if (cleaned.isNotBlank() && !isGenericTitle(cleaned, url)) return cleaned
        }
        return fallback
    }

    private fun hostDisplayName(url: String): String {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
            .removePrefix("www.")
        if (host.isBlank()) return "网页"

        // For hosted sites such as xxx.netlify.app, use the project subdomain
        // rather than the hosting provider name.
        val label = when {
            host.endsWith(".netlify.app", ignoreCase = true) -> host.dropLast(".netlify.app".length).substringBefore('.')
            host.endsWith(".vercel.app", ignoreCase = true) -> host.dropLast(".vercel.app".length).substringBefore('.')
            host.endsWith(".pages.dev", ignoreCase = true) -> host.dropLast(".pages.dev".length).substringBefore('.')
            host.endsWith(".github.io", ignoreCase = true) -> host.dropLast(".github.io".length).substringBefore('.')
            else -> host.substringBefore('.')
        }
        return humanize(label).ifBlank { "网页" }
    }

    private fun humanize(raw: String): String {
        val normalized = raw.replace(Regex("[-_]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return ""
        return normalized.split(' ').joinToString(" ") { part ->
            if (part.any { it.isUpperCase() } || part.any { it.isDigit() }) part
            else part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun isGenericTitle(value: String, url: String): Boolean {
        val normalized = value.trim().lowercase().replace(Regex("\\s+"), " ")
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").lowercase()
        val generic = setOf(
            "home", "homepage", "index", "untitled", "document", "website", "web app",
            "react app", "vite + react", "vite react", "next.js", "nextjs", "nuxt app",
            "vue app", "svelte app", "netlify app", "new tab", "网页"
        )
        return normalized in generic || normalized == host || normalized == "www.$host"
    }

    private fun fetchManifest(manifestUrl: String, userAgent: String?, cookie: String?, referer: String?): ManifestMeta? {
        val text = httpGetText(manifestUrl, userAgent, cookie, referer) ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val titles = listOf(json.optString("short_name"), json.optString("name")).filter { it.isNotBlank() }

        val icons = mutableListOf<Pair<Int, String>>()
        val array = json.optJSONArray("icons")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val src = item.optString("src").trim()
                if (src.isBlank()) continue
                val resolved = resolveUrl(manifestUrl, src) ?: continue
                val score = iconSizeScore(item.optString("sizes")) +
                    if (item.optString("purpose").contains("maskable", ignoreCase = true)) 500 else 0
                icons += score to resolved
            }
        }
        return ManifestMeta(
            titleCandidates = titles,
            iconUrls = icons.sortedByDescending { it.first }.map { it.second }.distinct()
        )
    }

    private fun iconSizeScore(sizes: String): Int {
        var best = 0
        Regex("(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).findAll(sizes).forEach { match ->
            best = maxOf(best, match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2].toIntOrNull() ?: 0)
        }
        return best
    }

    private fun defaultIconCandidates(pageUrl: String): List<String> {
        val uri = runCatching { Uri.parse(pageUrl) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme ?: return emptyList()
        val authority = uri.encodedAuthority ?: return emptyList()
        val origin = "$scheme://$authority"
        return listOf(
            "$origin/apple-touch-icon.png",
            "$origin/favicon-192.png",
            "$origin/favicon.svg",
            "$origin/favicon.png",
            "$origin/favicon.ico"
        )
    }


    private fun isSvgCandidate(url: String): Boolean {
        if (url.startsWith("data:image/svg", ignoreCase = true)) return true
        return Regex("\\.svg(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    private fun fetchBitmap(url: String, userAgent: String?, referer: String?): Bitmap? {
        if (url.startsWith("data:", ignoreCase = true)) return decodeDataUrl(url)
        if (isSvgCandidate(url)) return null

        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 2500
            readTimeout = 3500
            setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return null
        }
        val contentType = connection.contentType.orEmpty().lowercase()
        if (contentType.contains("svg")) {
            connection.disconnect()
            return null
        }
        return connection.inputStream.use { input ->
            BitmapFactory.decodeStream(BufferedInputStream(input))
        }.also { connection.disconnect() }
    }

    private fun decodeDataUrl(url: String): Bitmap? {
        if (url.length > 5_000_000) return null
        val comma = url.indexOf(',')
        if (comma <= 0) return null
        val header = url.substring(0, comma).lowercase()
        if (header.contains("image/svg")) return null
        val body = url.substring(comma + 1)
        val bytes = if (header.contains(";base64")) {
            runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull()
        } else {
            runCatching { java.net.URLDecoder.decode(body, "UTF-8").toByteArray(Charsets.ISO_8859_1) }.getOrNull()
        } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun httpGetText(url: String, userAgent: String?, cookie: String?, referer: String?): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 2500
            readTimeout = 3500
            setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: "Mozilla/5.0")
            setRequestProperty("Accept", "application/manifest+json,application/json,text/plain,*/*;q=0.5")
            if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return null
        }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { connection.disconnect() }
    }

    private fun resolveUrl(base: String, relative: String): String? = runCatching { URL(URL(base), relative).toString() }.getOrNull()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val output = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotBlank()) output += value
        }
        return output
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

package com.shixu.minibrowser.web

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.shixu.minibrowser.MainActivity
import com.shixu.minibrowser.R
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class NativeWebBridge(
    context: Context,
    private val webViewProvider: () -> WebView?,
    private val notificationToggle: () -> Boolean
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloads = ConcurrentHashMap<String, DownloadSession>()

    init {
        ensureNotificationChannel(appContext)
    }

    @JavascriptInterface
    fun notificationsEnabled(): Boolean = canNotify()

    @JavascriptInterface
    fun showNotification(title: String?, body: String?, tag: String?) {
        if (!canNotify()) return

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            appContext,
            (tag ?: title ?: body ?: "mal").hashCode() and 0x7fffffff,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cleanTitle = title?.trim().takeUnless { it.isNullOrBlank() } ?: "网页消息"
        val cleanBody = body?.trim().orEmpty()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_WEB_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_mal)
            .setContentTitle(cleanTitle.take(80))
            .setContentText(cleanBody.take(220))
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanBody.take(1500)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(appContext).notify((tag ?: "$cleanTitle|$cleanBody").hashCode() and 0x7fffffff, notification)
    }

    @JavascriptInterface
    fun beginDownload(id: String, fileName: String?, mimeType: String?): Boolean {
        if (id.isBlank()) return false
        return runCatching {
            downloads.remove(id)?.closeQuietly(appContext, delete = true)
            val safeName = sanitizeFileName(fileName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MAL")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val output = appContext.contentResolver.openOutputStream(uri, "w")
                ?: run {
                    appContext.contentResolver.delete(uri, null, null)
                    return false
                }
            downloads[id] = DownloadSession(uri, output, safeName)
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun appendDownloadChunk(id: String, base64Chunk: String): Boolean {
        val session = downloads[id] ?: return false
        return runCatching {
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            session.output.write(bytes)
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun finishDownload(id: String): Boolean {
        val session = downloads.remove(id) ?: return false
        return runCatching {
            session.output.flush()
            session.output.close()
            appContext.contentResolver.update(
                session.uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            mainHandler.post {
                Toast.makeText(appContext, "已保存到 下载/MAL：${session.fileName}", Toast.LENGTH_LONG).show()
            }
            true
        }.getOrElse {
            session.closeQuietly(appContext, delete = true)
            false
        }
    }

    @JavascriptInterface
    fun cancelDownload(id: String) {
        downloads.remove(id)?.closeQuietly(appContext, delete = true)
    }

    @JavascriptInterface
    fun currentUrl(): String = webViewProvider()?.url.orEmpty()

    fun close() {
        downloads.values.forEach { it.closeQuietly(appContext, delete = true) }
        downloads.clear()
    }

    private fun canNotify(): Boolean {
        if (!notificationToggle()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }


    private fun sanitizeFileName(raw: String?): String {
        val base = raw?.trim().orEmpty().ifBlank { "MAL_${System.currentTimeMillis()}.bin" }
        return base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120)
    }

    private data class DownloadSession(val uri: Uri, val output: OutputStream, val fileName: String) {
        fun closeQuietly(context: Context, delete: Boolean) {
            runCatching { output.close() }
            if (delete) runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    companion object {
        const val INTERFACE_NAME = "MALNative"
        const val CHANNEL_WEB_MESSAGES = "mal_web_messages"

        fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WEB_MESSAGES,
                    "网页消息",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "MAL 浏览器把网页 Notification 转成系统通知"
                    enableVibration(true)
                }
            )
        }


        fun installDocumentStartScript(webView: WebView) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                runCatching { WebViewCompat.addDocumentStartJavaScript(webView, injectionScript(), setOf("*")) }
            }
        }

        fun injectionScript(): String = """
            (function(){
              if (window.__MAL_BRIDGE_INSTALLED__) return;
              window.__MAL_BRIDGE_INSTALLED__ = true;

              function nativeNotify(title, options){
                try {
                  var body = options && options.body ? String(options.body) : '';
                  var tag = options && options.tag ? String(options.tag) : '';
                  window.MALNative.showNotification(String(title || '网页消息'), body, tag);
                } catch(e) {}
                return { close:function(){}, onclick:null, onshow:null, onerror:null, onclose:null };
              }

              try {
                var MALNotification = function(title, options){ return nativeNotify(title, options || {}); };
                Object.defineProperty(MALNotification, 'permission', { get:function(){ try { return window.MALNative.notificationsEnabled() ? 'granted' : 'denied'; } catch(e) { return 'denied'; } } });
                MALNotification.requestPermission = function(cb){
                  var state = 'denied'; try { state = window.MALNative.notificationsEnabled() ? 'granted' : 'denied'; } catch(e) {}
                  var p = Promise.resolve(state);
                  if (typeof cb === 'function') p.then(cb);
                  return p;
                };
                MALNotification.maxActions = 2;
                window.Notification = MALNotification;
              } catch(e) {}

              try {
                if (window.ServiceWorkerRegistration && ServiceWorkerRegistration.prototype) {
                  ServiceWorkerRegistration.prototype.showNotification = function(title, options){
                    nativeNotify(title, options || {});
                    return Promise.resolve();
                  };
                }
              } catch(e) {}

              function b64(bytes){
                var binary = '';
                var step = 0x8000;
                for (var i=0;i<bytes.length;i+=step) {
                  var sub = bytes.subarray(i, Math.min(i+step, bytes.length));
                  binary += String.fromCharCode.apply(null, sub);
                }
                return btoa(binary);
              }

              window.__MALSaveBlobUrl = async function(url, suggestedName){
                var id = 'mal_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                try {
                  var blob = await fetch(url).then(function(r){ return r.blob(); });
                  var name = suggestedName || ('MAL_' + Date.now());
                  if (!window.MALNative.beginDownload(id, name, blob.type || 'application/octet-stream')) throw new Error('begin failed');
                  var data = new Uint8Array(await blob.arrayBuffer());
                  var chunk = 192 * 1024;
                  for (var i=0;i<data.length;i+=chunk) {
                    var ok = window.MALNative.appendDownloadChunk(id, b64(data.subarray(i, Math.min(i+chunk, data.length))));
                    if (!ok) throw new Error('chunk failed');
                  }
                  window.MALNative.finishDownload(id);
                } catch(err) {
                  try { window.MALNative.cancelDownload(id); } catch(_) {}
                  try { alert('MAL 下载失败：' + (err && err.message ? err.message : err)); } catch(_) {}
                }
              };

              function malForceSameWindow(root){
                try {
                  (root || document).querySelectorAll('a[target="_blank"],form[target="_blank"]').forEach(function(el){
                    el.setAttribute('target','_self');
                  });
                } catch(_) {}
              }
              try {
                malForceSameWindow(document);
                document.addEventListener('DOMContentLoaded', function(){ malForceSameWindow(document); }, true);
                new MutationObserver(function(mutations){
                  mutations.forEach(function(m){
                    if (!m.addedNodes) return;
                    m.addedNodes.forEach(function(n){ if (n && n.querySelectorAll) malForceSameWindow(n); });
                  });
                }).observe(document.documentElement || document, {childList:true, subtree:true});
                document.addEventListener('submit', function(ev){
                  var form = ev.target;
                  if (form && String(form.target || '').toLowerCase() === '_blank') form.target = '_self';
                }, true);
              } catch(_) {}

              document.addEventListener('click', function(ev){
                var node = ev.target;
                while(node && node.tagName !== 'A') node = node.parentElement;
                if (!node || !node.href) return;
                if (String(node.target || '').toLowerCase() === '_blank') node.target = '_self';
                var href = String(node.href);
                if (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) {
                  ev.preventDefault();
                  ev.stopPropagation();
                  window.__MALSaveBlobUrl(href, node.getAttribute('download') || ('MAL_' + Date.now()));
                }
              }, true);
            })();
        """.trimIndent()
    }
}

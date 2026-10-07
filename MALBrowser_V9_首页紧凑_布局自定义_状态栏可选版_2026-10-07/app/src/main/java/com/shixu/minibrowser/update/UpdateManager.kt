package com.shixu.minibrowser.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.shixu.minibrowser.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * GitHub Releases based updater for MAL Browser.
 *
 * Release contract:
 * - Latest GitHub Release must contain an APK asset.
 * - Latest GitHub Release must contain update.json with at least:
 *   {"versionCode":11,"versionName":"2.8.0","apkAsset":"MALBrowser-2.8.0.apk"}
 * - update.json may include "changelog"; Release body is the fallback changelog.
 */
object UpdateManager {
    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkAsset: String,
        val apkUrl: String,
        val changelog: String
    )

    sealed class CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    private const val PREFS = "mal_update_state"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check"
    private const val KEY_LAST_AUTO_ATTEMPT = "last_auto_attempt"
    private const val KEY_LAST_PROMPT_VERSION = "last_prompt_version"
    private const val KEY_LAST_PROMPT_AT = "last_prompt_at"
    private const val KEY_PENDING_UPDATE = "pending_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_PATH = "download_path"
    private const val KEY_DOWNLOAD_INFO = "download_info"
    private const val AUTO_CHECK_INTERVAL_MS = 60L * 60L * 1000L
    private const val AUTO_RETRY_AFTER_ERROR_MS = 10L * 60L * 1000L
    private const val PROMPT_REPEAT_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    fun shouldAutoCheck(context: Context): Boolean {
        val lastAttempt = prefs(context).getLong(KEY_LAST_AUTO_ATTEMPT, 0L)
        return System.currentTimeMillis() - lastAttempt >= AUTO_CHECK_INTERVAL_MS
    }

    fun checkForUpdates(context: Context, manual: Boolean, callback: (CheckResult) -> Unit) {
        val now = System.currentTimeMillis()
        if (!manual) prefs(context).edit().putLong(KEY_LAST_AUTO_ATTEMPT, now).apply()
        io.execute {
            val result = runCatching { fetchLatestRelease(context) }
                .getOrElse { CheckResult.Error(readableError(it)) }
            if (!manual) {
                val state = prefs(context)
                when (result) {
                    is CheckResult.Error -> state.edit()
                        .putLong(KEY_LAST_AUTO_ATTEMPT, System.currentTimeMillis() - AUTO_CHECK_INTERVAL_MS + AUTO_RETRY_AFTER_ERROR_MS)
                        .apply()
                    else -> state.edit().putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis()).apply()
                }
            }
            main.post { callback(result) }
        }
    }

    fun checkAndPrompt(
        activity: AppCompatActivity,
        manual: Boolean,
        status: ((String) -> Unit)? = null
    ) {
        status?.invoke("正在检查 GitHub 最新版本…")
        checkForUpdates(activity, manual) { result ->
            when (result) {
                is CheckResult.Available -> {
                    status?.invoke("发现新版本 ${result.info.versionName}")
                    if (manual || shouldPromptAgain(activity, result.info.versionCode)) {
                        showUpdateDialog(activity, result.info)
                    }
                }
                CheckResult.UpToDate -> {
                    status?.invoke("已是最新版本 · ${BuildConfig.VERSION_NAME}")
                    if (manual) Toast.makeText(activity, "当前已经是最新版本。", Toast.LENGTH_SHORT).show()
                }
                is CheckResult.Error -> {
                    status?.invoke("检查失败：${result.message}")
                    if (manual) Toast.makeText(activity, "检查更新失败：${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun resumePendingWork(activity: AppCompatActivity) {
        val state = prefs(activity)

        val downloadId = state.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId > 0) {
            inspectDownload(activity, downloadId, showRunningToast = false)
            return
        }

        val pendingJson = state.getString(KEY_PENDING_UPDATE, null)
        if (!pendingJson.isNullOrBlank() && canInstallPackages(activity)) {
            val info = runCatching { updateInfoFromJson(JSONObject(pendingJson)) }.getOrNull()
            state.edit().remove(KEY_PENDING_UPDATE).apply()
            if (info != null) enqueueDownload(activity, info)
        }
    }

    private fun fetchLatestRelease(context: Context): CheckResult {
        val repo = BuildConfig.UPDATE_REPOSITORY
        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        val release = JSONObject(httpGet(apiUrl, "application/vnd.github+json"))
        val assets = release.getJSONArray("assets")

        var metadataUrl: String? = null
        var firstApkUrl: String? = null
        var firstApkName: String? = null
        val apkByName = linkedMapOf<String, String>()

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.equals("update.json", ignoreCase = true)) metadataUrl = url
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkByName[name] = url
                if (firstApkUrl == null) {
                    firstApkUrl = url
                    firstApkName = name
                }
            }
        }

        if (metadataUrl.isNullOrBlank()) {
            return CheckResult.Error("最新 Release 缺少 update.json")
        }
        if (firstApkUrl.isNullOrBlank()) {
            return CheckResult.Error("最新 Release 没有 APK 文件")
        }

        val metadata = JSONObject(httpGet(metadataUrl, "application/json"))
        val versionCode = metadata.optLong("versionCode", -1L)
        val versionName = metadata.optString("versionName").ifBlank { release.optString("tag_name").removePrefix("v") }
        val requestedAsset = metadata.optString("apkAsset")
        val apkName = requestedAsset.takeIf { it.isNotBlank() } ?: firstApkName.orEmpty()
        val apkUrl = apkByName[apkName] ?: firstApkUrl

        if (versionCode <= 0L || versionName.isBlank() || apkUrl.isNullOrBlank()) {
            return CheckResult.Error("update.json 内容不完整")
        }

        val localCode = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }

        if (versionCode <= localCode) return CheckResult.UpToDate

        val changelog = readChangelog(metadata, release)
        return CheckResult.Available(
            UpdateInfo(versionCode, versionName, apkName, apkUrl, changelog)
        )
    }

    private fun showUpdateDialog(activity: AppCompatActivity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        prefs(activity).edit()
            .putLong(KEY_LAST_PROMPT_VERSION, info.versionCode)
            .putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis())
            .apply()
        val body = buildString {
            append("当前版本：${BuildConfig.VERSION_NAME}\n")
            append("最新版本：${info.versionName}\n\n")
            append(info.changelog.take(1600))
        }
        AlertDialog.Builder(activity)
            .setTitle("发现 MAL 新版本")
            .setMessage(body)
            .setNegativeButton("稍后") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("立即更新") { _, _ -> prepareDownload(activity, info) }
            .show()
    }


    private fun shouldPromptAgain(context: Context, versionCode: Long): Boolean {
        val state = prefs(context)
        val lastVersion = state.getLong(KEY_LAST_PROMPT_VERSION, -1L)
        val lastAt = state.getLong(KEY_LAST_PROMPT_AT, 0L)
        return lastVersion != versionCode || System.currentTimeMillis() - lastAt >= PROMPT_REPEAT_INTERVAL_MS
    }

    private fun readChangelog(metadata: JSONObject, release: JSONObject): String {
        val value = metadata.opt("changelog")
        val fromMetadata = when (value) {
            is org.json.JSONArray -> buildString {
                for (i in 0 until value.length()) {
                    val item = value.optString(i).trim()
                    if (item.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append("• ").append(item)
                    }
                }
            }
            is String -> value.trim()
            else -> ""
        }
        return fromMetadata.ifBlank {
            release.optString("body").trim().ifBlank { "本次版本包含稳定性与体验更新。" }
        }
    }

    private fun prepareDownload(activity: AppCompatActivity, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            prefs(activity).edit().putString(KEY_PENDING_UPDATE, updateInfoToJson(info).toString()).apply()
            AlertDialog.Builder(activity)
                .setTitle("允许安装 MAL 更新")
                .setMessage("Android 需要先允许“MAL 浏览器”安装来自此来源的应用。开启后返回 MAL，更新会自动继续。")
                .setNegativeButton("取消") { _, _ ->
                    prefs(activity).edit().remove(KEY_PENDING_UPDATE).apply()
                }
                .setPositiveButton("去开启") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    runCatching { activity.startActivity(intent) }
                        .onFailure {
                            activity.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
                            )
                        }
                }
                .show()
            return
        }
        enqueueDownload(activity, info)
    }

    private fun enqueueDownload(activity: AppCompatActivity, info: UpdateInfo) {
        val downloads = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloads == null) {
            Toast.makeText(activity, "无法访问更新下载目录。", Toast.LENGTH_LONG).show()
            return
        }
        downloads.mkdirs()
        val safeName = "MALBrowser-update-v${info.versionCode}-${info.versionName}.apk"
        val target = File(downloads, safeName)
        runCatching { if (target.exists()) target.delete() }

        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("MAL 浏览器 ${info.versionName}")
            .setDescription("正在下载应用更新")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, safeName)

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = runCatching { manager.enqueue(request) }.getOrElse {
            Toast.makeText(activity, "更新下载启动失败：${it.message}", Toast.LENGTH_LONG).show()
            return
        }

        prefs(activity).edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_DOWNLOAD_PATH, target.absolutePath)
            .putString(KEY_DOWNLOAD_INFO, updateInfoToJson(info).toString())
            .apply()

        Toast.makeText(activity, "已开始下载 ${info.versionName}，完成后会打开系统安装界面。", Toast.LENGTH_LONG).show()
        pollDownload(activity, id)
    }

    private fun pollDownload(activity: AppCompatActivity, id: Long) {
        io.execute {
            repeat(3600) {
                Thread.sleep(1000L)
                val status = queryDownloadStatus(activity, id)
                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    main.post { inspectDownload(activity, id, showRunningToast = false) }
                    return@execute
                }
            }
        }
    }

    private fun inspectDownload(activity: AppCompatActivity, id: Long, showRunningToast: Boolean) {
        when (queryDownloadStatus(activity, id)) {
            DownloadManager.STATUS_SUCCESSFUL -> installDownloadedApk(activity)
            DownloadManager.STATUS_FAILED -> {
                clearDownloadState(activity)
                Toast.makeText(activity, "更新 APK 下载失败，请重新检查更新。", Toast.LENGTH_LONG).show()
            }
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_RUNNING -> if (showRunningToast) {
                Toast.makeText(activity, "更新仍在下载中。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryDownloadStatus(context: Context, id: Long): Int {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
            ?: return DownloadManager.STATUS_FAILED
        cursor.use {
            if (!it.moveToFirst()) return DownloadManager.STATUS_FAILED
            val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (index >= 0) it.getInt(index) else DownloadManager.STATUS_FAILED
        }
    }

    private fun installDownloadedApk(activity: AppCompatActivity) {
        if (!canInstallPackages(activity)) {
            val infoJson = prefs(activity).getString(KEY_DOWNLOAD_INFO, null)
            if (!infoJson.isNullOrBlank()) prefs(activity).edit().putString(KEY_PENDING_UPDATE, infoJson).apply()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            runCatching { activity.startActivity(intent) }
            return
        }

        val path = prefs(activity).getString(KEY_DOWNLOAD_PATH, null)
        val infoJson = prefs(activity).getString(KEY_DOWNLOAD_INFO, null)
        val info = infoJson?.let { runCatching { updateInfoFromJson(JSONObject(it)) }.getOrNull() }
        val file = path?.let(::File)
        if (file == null || !file.exists() || file.length() <= 0L) {
            clearDownloadState(activity)
            Toast.makeText(activity, "更新文件不存在，请重新下载。", Toast.LENGTH_LONG).show()
            return
        }

        val archive = runCatching { activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull()
        if (archive == null || archive.packageName != activity.packageName) {
            clearDownloadState(activity)
            file.delete()
            Toast.makeText(activity, "更新包校验失败：包名不匹配。", Toast.LENGTH_LONG).show()
            return
        }
        if (info != null) {
            val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else {
                @Suppress("DEPRECATION") archive.versionCode.toLong()
            }
            if (archiveCode != info.versionCode) {
                clearDownloadState(activity)
                file.delete()
                Toast.makeText(activity, "更新包校验失败：版本号不匹配。", Toast.LENGTH_LONG).show()
                return
            }
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        clearDownloadState(activity)
        runCatching { activity.startActivity(intent) }
            .onFailure {
                Toast.makeText(activity, "无法打开 Android 安装器：${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun clearDownloadState(context: Context) {
        prefs(context).edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_PATH)
            .remove(KEY_DOWNLOAD_INFO)
            .apply()
    }

    private fun httpGet(url: String, accept: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "MALBrowser/${BuildConfig.VERSION_NAME}")
        connection.instanceFollowRedirects = true
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                if (code == 404) throw IllegalStateException("GitHub 还没有可用的正式 Release")
                if (code == 403) throw IllegalStateException("GitHub 请求受限，请稍后再试")
                throw IllegalStateException("GitHub HTTP $code")
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun readableError(error: Throwable): String {
        val raw = error.message.orEmpty().trim()
        return when {
            raw.isNotBlank() -> raw.take(180)
            else -> "网络连接失败"
        }
    }

    private fun updateInfoToJson(info: UpdateInfo) = JSONObject().apply {
        put("versionCode", info.versionCode)
        put("versionName", info.versionName)
        put("apkAsset", info.apkAsset)
        put("apkUrl", info.apkUrl)
        put("changelog", info.changelog)
    }

    private fun updateInfoFromJson(json: JSONObject) = UpdateInfo(
        versionCode = json.getLong("versionCode"),
        versionName = json.getString("versionName"),
        apkAsset = json.getString("apkAsset"),
        apkUrl = json.getString("apkUrl"),
        changelog = json.optString("changelog")
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

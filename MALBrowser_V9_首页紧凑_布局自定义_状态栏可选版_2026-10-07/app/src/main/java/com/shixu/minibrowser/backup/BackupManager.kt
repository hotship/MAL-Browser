package com.shixu.minibrowser.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import com.shixu.minibrowser.BrowserPrefs
import com.shixu.minibrowser.BuildConfig
import com.shixu.minibrowser.data.BrowserDatabase
import com.shixu.minibrowser.data.SavedWebsite
import com.shixu.minibrowser.data.WebsiteShortcut
import com.shixu.minibrowser.shortcut.ShortcutManagerHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object BackupManager {
    private const val SCHEMA = "mal-browser-backup-v1"
    private const val MAX_GITHUB_BACKUP_BYTES = 95L * 1024L * 1024L
    private const val MAX_RESTORE_BYTES = 100L * 1024L * 1024L
    private val autoBusy = AtomicBoolean(false)

    data class Result(val ok: Boolean, val message: String, val restoredCount: Int = 0)

    fun selectedGithubUrls(context: Context, allSites: List<SavedWebsite>): Set<String> =
        if (BrowserPrefs.githubSelectionInitialized(context)) BrowserPrefs.githubSelectedUrls(context)
        else allSites.mapTo(linkedSetOf()) { it.url }

    fun selectedLocalUrls(context: Context, allSites: List<SavedWebsite>): Set<String> =
        if (BrowserPrefs.localSelectionInitialized(context)) BrowserPrefs.localSelectedUrls(context)
        else allSites.mapTo(linkedSetOf()) { it.url }

    fun maybeRunAutomaticBackups(context: Context) {
        val app = context.applicationContext
        Thread { runDueAutomaticBackupsBlocking(app) }.start()
    }

    /** Called by the periodic receiver and by the foreground fallback. */
    fun runDueAutomaticBackupsBlocking(
        context: Context,
        allowGithub: Boolean = true,
        allowLocal: Boolean = true
    ) {
        val app = context.applicationContext
        if (!autoBusy.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            val ghInterval = BrowserPrefs.githubBackupIntervalHours(app) * 60L * 60L * 1000L
            val localInterval = BrowserPrefs.localBackupIntervalHours(app) * 60L * 60L * 1000L
            val ghDue = allowGithub && BrowserPrefs.githubAutoBackup(app) &&
                now - BrowserPrefs.lastGithubBackupAt(app) >= ghInterval && githubConfigComplete(app)
            val localDue = allowLocal && BrowserPrefs.localAutoBackup(app) &&
                now - BrowserPrefs.lastLocalBackupAt(app) >= localInterval
            if (!ghDue && !localDue) return

            val db = BrowserDatabase(app)
            try {
                val all = db.getWebsites()
                if (localDue) {
                    val json = buildSnapshot(app, db, selectedLocalUrls(app, all), "local")
                    if (saveToLocalDestination(app, json).ok) {
                        BrowserPrefs.setLastLocalBackupAt(app, System.currentTimeMillis())
                    }
                }
                if (ghDue) {
                    val json = buildSnapshot(app, db, selectedGithubUrls(app, all), "github")
                    val result = uploadGithub(app, json)
                    if (result.ok) BrowserPrefs.setLastGithubBackupAt(app, System.currentTimeMillis())
                }
            } finally {
                db.close()
            }
        } catch (_: Throwable) {
            // Automatic backups never interrupt current browsing. Manual actions surface detailed errors.
        } finally {
            autoBusy.set(false)
        }
    }

    fun backupGithub(context: Context, callback: (Result) -> Unit) {
        runAsync(callback) {
            val db = BrowserDatabase(context)
            try {
                val all = db.getWebsites()
                val json = buildSnapshot(context, db, selectedGithubUrls(context, all), "github")
                val result = uploadGithub(context, json)
                if (result.ok) BrowserPrefs.setLastGithubBackupAt(context, System.currentTimeMillis())
                result
            } finally { db.close() }
        }
    }

    fun backupLocal(context: Context, callback: (Result) -> Unit) {
        runAsync(callback) {
            val db = BrowserDatabase(context)
            try {
                val all = db.getWebsites()
                val json = buildSnapshot(context, db, selectedLocalUrls(context, all), "local")
                val result = saveToLocalDestination(context, json)
                if (result.ok) BrowserPrefs.setLastLocalBackupAt(context, System.currentTimeMillis())
                result
            } finally { db.close() }
        }
    }

    fun restoreLocal(context: Context, uri: Uri, callback: (Result) -> Unit) {
        runAsync(callback) {
            val json = runCatching {
                context.contentResolver.openInputStream(uri)?.use { readTextLimited(it, MAX_RESTORE_BYTES) }
                    ?: error("无法读取这个备份文件")
            }.getOrElse { return@runAsync Result(false, "读取本地备份失败：${it.message ?: it.javaClass.simpleName}") }
            restoreSnapshot(context, json)
        }
    }

    fun testGithub(context: Context, callback: (Result) -> Unit) {
        runAsync(callback) {
            val cfg = githubConfig(context) ?: return@runAsync Result(false, "请先填写 GitHub 用户名、仓库名和 Token。")
            val response = request(
                "https://api.github.com/repos/${encode(cfg.owner)}/${encode(cfg.repo)}",
                "GET",
                cfg.token,
                null
            )
            if (response.code in 200..299) Result(true, "GitHub 连接成功 · ${cfg.owner}/${cfg.repo}")
            else Result(false, githubError(response.code, response.body))
        }
    }

    fun restoreGithub(context: Context, callback: (Result) -> Unit) {
        runAsync(callback) {
            val cfg = githubConfig(context) ?: return@runAsync Result(false, "请先填写 GitHub 用户名、仓库名和 Token。")
            val endpoint = contentUrl(cfg)

            val file = readGithubFile(cfg, endpoint)
            if (file.code !in 200..299) return@runAsync Result(false, githubError(file.code, file.body))
            if (file.body.isBlank()) return@runAsync Result(false, "GitHub 备份文件为空。")
            restoreSnapshot(context, file.body)
        }
    }

    private fun buildSnapshot(context: Context, db: BrowserDatabase, selectedUrls: Set<String>, mode: String): String {
        val sites = db.getWebsites().filter { it.url in selectedUrls }
        val shortcuts = db.getAllShortcuts().filter { it.url in selectedUrls }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("version", 3)
            put("createdAt", System.currentTimeMillis())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("mode", mode)
            put("settings", JSONObject().apply {
                put("startOnHome", BrowserPrefs.startOnHome(context))
                put("desktopMode", BrowserPrefs.desktopMode(context))
                put("webNotifications", BrowserPrefs.webNotifications(context))
                put("keepAlive", BrowserPrefs.keepAlive(context))
                put("showStatusBarInShortcut", BrowserPrefs.showStatusBarInShortcut(context))
                put("homeColumns", BrowserPrefs.homeColumns(context))
            })
            put("lastUrl", db.getLastUrl().orEmpty())
            put("categories", JSONArray().apply { db.getCategories().forEach { put(it) } })
            put("websites", JSONArray().apply {
                sites.forEach { site ->
                    put(JSONObject().apply {
                        put("title", site.title)
                        put("url", site.url)
                        put("category", site.category)
                        put("createdAt", site.createdAt)
                        put("lastOpenedAt", site.lastOpenedAt)
                        put("sortOrder", site.sortOrder)
                        put("iconBase64", readIconBase64(context, site.iconPath))
                    })
                }
            })
            put("shortcuts", JSONArray().apply {
                shortcuts.forEach { shortcut ->
                    put(JSONObject().apply {
                        put("shortcutId", shortcut.shortcutId)
                        put("title", shortcut.title)
                        put("url", shortcut.url)
                        put("displayMode", shortcut.displayMode)
                        put("createdAt", shortcut.createdAt)
                        put("pinConfirmed", shortcut.pinConfirmed)
                        put("iconBase64", readIconBase64(context, shortcut.iconPath))
                    })
                }
            })
            put("note", "This backup stores MAL-managed website records, categories, icons, shortcut mappings and basic settings. It intentionally excludes cookies, passwords, OAuth sessions, WebView local site databases and GitHub token.")
        }.toString(2)
    }

    private fun restoreSnapshot(context: Context, json: String): Result {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return Result(false, "备份 JSON 无法解析。")
        if (root.optString("schema") != SCHEMA) return Result(false, "这不是 MAL 浏览器备份文件。")

        val sites = root.optJSONArray("websites") ?: JSONArray()
        val shortcuts = root.optJSONArray("shortcuts") ?: JSONArray()
        val db = BrowserDatabase(context)
        var restored = 0
        var shortcutRestored = 0
        return try {
            root.optJSONArray("categories")?.let { categories ->
                for (i in 0 until categories.length()) {
                    categories.optString(i).trim().takeIf { it.isNotBlank() }?.let(db::ensureCategory)
                }
            }

            val restoredUrls = linkedSetOf<String>()
            for (i in 0 until sites.length()) {
                val item = sites.optJSONObject(i) ?: continue
                val url = item.optString("url").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                val title = item.optString("title").trim().ifBlank { url }
                val category = item.optString("category").trim().ifBlank { "未分类" }
                db.ensureCategory(category)
                val iconPath = writeIconBase64(context, "site_$url", item.optString("iconBase64"))
                    ?: db.getWebsiteByUrl(url)?.iconPath
                db.upsertWebsite(
                    SavedWebsite(
                        title = title,
                        url = url,
                        iconPath = iconPath,
                        category = category,
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        lastOpenedAt = item.optLong("lastOpenedAt", 0L),
                        sortOrder = item.optLong("sortOrder", 0L)
                    )
                )
                restoredUrls += url
                restored++
            }

            for (i in 0 until shortcuts.length()) {
                val item = shortcuts.optJSONObject(i) ?: continue
                val shortcutId = item.optString("shortcutId").trim()
                val url = item.optString("url").trim()
                if (shortcutId.isBlank() || url !in restoredUrls) continue
                val iconPath = writeIconBase64(context, "shortcut_$shortcutId", item.optString("iconBase64"))
                db.upsertShortcut(
                    WebsiteShortcut(
                        id = 0,
                        shortcutId = shortcutId,
                        title = item.optString("title").ifBlank { db.getWebsiteByUrl(url)?.title ?: "网页" },
                        url = url,
                        iconPath = iconPath ?: db.getWebsiteByUrl(url)?.iconPath,
                        displayMode = item.optString("displayMode").ifBlank { "fullscreen" },
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        pinConfirmed = item.optBoolean("pinConfirmed", false)
                    )
                )
                shortcutRestored++
            }

            root.optJSONObject("settings")?.let { settings ->
                BrowserPrefs.setStartOnHome(context, settings.optBoolean("startOnHome", BrowserPrefs.startOnHome(context)))
                BrowserPrefs.setDesktopMode(context, settings.optBoolean("desktopMode", BrowserPrefs.desktopMode(context)))
                BrowserPrefs.setWebNotifications(context, settings.optBoolean("webNotifications", BrowserPrefs.webNotifications(context)))
                BrowserPrefs.setKeepAlive(context, settings.optBoolean("keepAlive", BrowserPrefs.keepAlive(context)))
                BrowserPrefs.setShowStatusBarInShortcut(context, settings.optBoolean("showStatusBarInShortcut", BrowserPrefs.showStatusBarInShortcut(context)))
                BrowserPrefs.setHomeColumns(context, settings.optInt("homeColumns", BrowserPrefs.homeColumns(context)))
            }
            root.optString("lastUrl").takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let(db::saveLastUrl)

            // If the launcher still owns any pinned shortcuts, refresh them against the restored mappings/icons.
            // Android does not allow silently re-pinning shortcuts that the launcher has already removed.
            runCatching { ShortcutManagerHelper.republishKnownShortcuts(context) }

            Result(
                true,
                "恢复完成：$restored 个网页、$shortcutRestored 条桌面快捷方式映射。已有网页会合并更新，不会清除当前网站登录态。",
                restored
            )
        } catch (e: Throwable) {
            Result(false, "恢复失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            db.close()
        }
    }

    private fun saveToLocalDestination(context: Context, json: String): Result {
        val tree = BrowserPrefs.localBackupTreeUri(context)
        return if (tree.isNotBlank()) saveToChosenFolder(context, Uri.parse(tree), json) else saveToDownloads(context, json)
    }

    private fun saveToChosenFolder(context: Context, treeUri: Uri, json: String): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "MAL_Backup_$stamp.json"
        return runCatching {
            val resolver = context.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
            val fileUri = DocumentsContract.createDocument(resolver, parent, "application/json", name)
                ?: error("系统未允许在所选文件夹创建文件")
            resolver.openOutputStream(fileUri, "w")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                ?: error("无法写入所选文件夹")
            val label = BrowserPrefs.localBackupTreeLabel(context).ifBlank { "自选文件夹" }
            Result(true, "备份已保存到 $label/$name")
        }.getOrElse {
            Result(false, "保存到自选文件夹失败：${it.message ?: it.javaClass.simpleName}。可重新选择备份位置。")
        }
    }

    private fun saveToDownloads(context: Context, json: String): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "MAL_Backup_$stamp.json"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MAL/Backups")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入下载文件")
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null
                )
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
            Result(true, "备份已保存到 下载/MAL/Backups/$name")
        }.getOrElse { Result(false, "本地备份失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private data class GithubConfig(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
        val token: String
    )

    private fun githubConfigComplete(context: Context): Boolean = githubConfig(context) != null

    private fun githubConfig(context: Context): GithubConfig? {
        val owner = BrowserPrefs.githubOwner(context).trim()
        val repo = BrowserPrefs.githubRepo(context).trim()
        val branch = BrowserPrefs.githubBranch(context).trim().ifBlank { "main" }
        val path = BrowserPrefs.githubPath(context).trim().ifBlank { "mal-backup.json" }
        val token = SecretStore.getGithubToken(context).trim()
        if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null
        return GithubConfig(owner, repo, branch, path, token)
    }

    private fun uploadGithub(context: Context, json: String): Result {
        val cfg = githubConfig(context) ?: return Result(false, "GitHub 配置不完整。")
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_GITHUB_BACKUP_BYTES) {
            return Result(false, "备份文件过大（${bytes.size / 1024 / 1024}MB）。GitHub Contents API 单文件建议控制在 95MB 以内。")
        }

        val endpoint = contentUrl(cfg)
        var sha = ""
        val old = request("$endpoint?ref=${encode(cfg.branch)}", "GET", cfg.token, null)
        if (old.code in 200..299) {
            sha = runCatching { JSONObject(old.body).optString("sha") }.getOrDefault("")
        } else if (old.code != 404) {
            return Result(false, githubError(old.code, old.body))
        }

        val payload = JSONObject().apply {
            put("message", "chore: backup MAL browser")
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("branch", cfg.branch)
            if (sha.isNotBlank()) put("sha", sha)
        }
        val put = request(endpoint, "PUT", cfg.token, payload.toString())
        if (put.code !in 200..299) return Result(false, githubError(put.code, put.body))

        // Read the exact file back from GitHub and compare a SHA-256 digest, not only the reported size.
        val verify = readGithubFile(cfg, endpoint)
        if (verify.code !in 200..299) {
            return Result(false, "GitHub 已写入，但回读校验失败：${githubError(verify.code, verify.body)}")
        }
        val localHash = sha256(bytes)
        val remoteHash = sha256(verify.body.toByteArray(Charsets.UTF_8))
        if (localHash != remoteHash) return Result(false, "GitHub 回读校验失败：文件摘要不一致。")
        return Result(true, "GitHub 备份完成，并已回读校验 SHA-256 · ${cfg.path}")
    }

    private fun readGithubFile(cfg: GithubConfig, endpoint: String): HttpResult {
        val refUrl = "$endpoint?ref=${encode(cfg.branch)}"

        val raw = request(refUrl, "GET", cfg.token, null, accept = "application/vnd.github.raw+json")
        if (raw.code !in 200..299) return raw
        val rawLooksLikeBackup = runCatching { JSONObject(raw.body).optString("schema") == SCHEMA }.getOrDefault(false)
        if (rawLooksLikeBackup) return raw

        val metaResponse = request(refUrl, "GET", cfg.token, null)
        if (metaResponse.code !in 200..299) return metaResponse
        val meta = runCatching { JSONObject(metaResponse.body) }.getOrNull()
            ?: return HttpResult(422, "GitHub 返回的文件元数据无法解析")

        decodeGithubBase64(meta.optString("content"))?.let { return HttpResult(200, it) }

        val gitUrl = meta.optString("git_url")
        if (gitUrl.isNotBlank()) {
            val blob = request(gitUrl, "GET", cfg.token, null)
            if (blob.code in 200..299) {
                val blobJson = runCatching { JSONObject(blob.body) }.getOrNull()
                decodeGithubBase64(blobJson?.optString("content").orEmpty())?.let { return HttpResult(200, it) }
            }
        }

        val downloadUrl = meta.optString("download_url")
        if (downloadUrl.isNotBlank()) {
            val download = request(downloadUrl, "GET", cfg.token, null, accept = "application/octet-stream")
            if (download.code in 200..299 && download.body.isNotBlank()) return download
        }

        return HttpResult(422, "GitHub 文件内容为空或超过可恢复大小（上限约 100MB）")
    }

    private fun decodeGithubBase64(encodedValue: String): String? {
        val encoded = encodedValue.replace("\n", "").replace("\r", "").trim()
        if (encoded.isBlank()) return null
        return runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
    }

    private fun contentUrl(cfg: GithubConfig): String {
        val encodedPath = cfg.path.split('/').filter { it.isNotBlank() }.joinToString("/") { encode(it) }
        return "https://api.github.com/repos/${encode(cfg.owner)}/${encode(cfg.repo)}/contents/$encodedPath"
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun request(
        url: String,
        method: String,
        token: String,
        body: String?,
        accept: String = "application/vnd.github+json"
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", accept)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MAL-Browser/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun githubError(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("").ifBlank { "HTTP $code" }
        return when (code) {
            401 -> "GitHub Token 无效或已过期。"
            403 -> "GitHub 拒绝访问：请检查 Token 是否有该仓库 Contents 读写权限。"
            404 -> "找不到仓库/分支/备份文件；若是私有仓库，请检查 Token 权限。"
            409 -> "GitHub 仓库尚未初始化或分支状态冲突。建议先在仓库创建 README。"
            413 -> "GitHub 拒绝上传：备份文件过大。"
            else -> "GitHub $code：$message"
        }
    }

    private fun readIconBase64(context: Context, path: String?): String {
        if (path.isNullOrBlank()) return ""
        val file = File(path).let { if (it.isAbsolute) it else File(context.filesDir, path) }
        if (!file.isFile || file.length() > 2L * 1024L * 1024L) return ""
        return runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrDefault("")
    }

    private fun writeIconBase64(context: Context, key: String, encoded: String): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return@runCatching null
            val dir = File(context.filesDir, "website_icons").apply { mkdirs() }
            val file = File(dir, "restored_${key.hashCode().toUInt()}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { it.write(bytes) }
            file.relativeTo(context.filesDir).path
        }.getOrNull()
    }

    private fun readTextLimited(input: InputStream, maxBytes: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) error("备份文件超过 ${maxBytes / 1024 / 1024}MB，已停止读取")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun encode(value: String): String = Uri.encode(value)

    private fun runAsync(callback: (Result) -> Unit, block: () -> Result) {
        Thread {
            val result = runCatching(block).getOrElse { Result(false, it.message ?: it.javaClass.simpleName) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }.start()
    }
}

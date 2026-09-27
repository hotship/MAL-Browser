package com.shixu.minibrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BrowserDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE browser_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                last_url TEXT
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO browser_state(id, last_url) VALUES(1, NULL)")
        createShortcutTable(db)
        createWebsiteTables(db)
        createShortcutTombstoneTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只做增量迁移，避免升级时破坏 WebView 登录态和已有网页数据。
        if (oldVersion < 2) createWebsiteTables(db)
        if (oldVersion < 3) createShortcutTombstoneTable(db)
    }

    private fun createShortcutTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS website_shortcut (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shortcut_id TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                icon_path TEXT,
                display_mode TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                pin_confirmed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shortcut_id ON website_shortcut(shortcut_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shortcut_url ON website_shortcut(url)")
    }

    private fun createWebsiteTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS website_category (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS saved_website (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                icon_path TEXT,
                category TEXT NOT NULL DEFAULT '未分类',
                created_at INTEGER NOT NULL,
                last_opened_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_saved_website_category ON saved_website(category)")
        db.execSQL(
            "INSERT OR IGNORE INTO website_category(name, created_at) VALUES('未分类', ${System.currentTimeMillis()})"
        )
    }

    private fun createShortcutTombstoneTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS deleted_shortcut (
                shortcut_id TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun saveLastUrl(url: String) {
        val values = ContentValues().apply { put("last_url", url) }
        writableDatabase.update("browser_state", values, "id = 1", null)
    }

    fun getLastUrl(): String? {
        readableDatabase.query("browser_state", arrayOf("last_url"), "id = 1", null, null, null, null).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }

    fun upsertShortcut(shortcut: WebsiteShortcut) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("deleted_shortcut", "shortcut_id = ?", arrayOf(shortcut.shortcutId))
            writableDatabase.insertWithOnConflict("website_shortcut", null, shortcut.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun updateShortcutUrl(shortcutId: String, newUrl: String) {
        writableDatabase.update(
            "website_shortcut",
            ContentValues().apply { put("url", newUrl) },
            "shortcut_id = ?",
            arrayOf(shortcutId)
        )
    }

    fun markPinConfirmed(shortcutId: String) {
        writableDatabase.update(
            "website_shortcut",
            ContentValues().apply { put("pin_confirmed", 1) },
            "shortcut_id = ?",
            arrayOf(shortcutId)
        )
    }

    fun getShortcut(shortcutId: String): WebsiteShortcut? {
        readableDatabase.query("website_shortcut", SHORTCUT_COLUMNS, "shortcut_id = ?", arrayOf(shortcutId), null, null, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toShortcut() else null
        }
    }

    fun isShortcutTombstoned(shortcutId: String): Boolean {
        readableDatabase.query(
            "deleted_shortcut",
            arrayOf("shortcut_id"),
            "shortcut_id = ?",
            arrayOf(shortcutId),
            null,
            null,
            null,
            "1"
        ).use { return it.moveToFirst() }
    }

    fun getAllShortcuts(): List<WebsiteShortcut> = readShortcuts(null)
    fun getConfirmedShortcuts(): List<WebsiteShortcut> = readShortcuts("pin_confirmed = 1")

    private fun readShortcuts(selection: String?): List<WebsiteShortcut> {
        val result = mutableListOf<WebsiteShortcut>()
        readableDatabase.query("website_shortcut", SHORTCUT_COLUMNS, selection, null, null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.toShortcut()
        }
        return result
    }

    fun addCategory(name: String): Boolean {
        val clean = name.trim().take(24)
        if (clean.isBlank() || clean == "全部") return false
        return writableDatabase.insertWithOnConflict(
            "website_category",
            null,
            ContentValues().apply {
                put("name", clean)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun ensureCategory(name: String): String {
        val clean = name.trim().take(24).ifBlank { "未分类" }
        addCategory(clean)
        return clean
    }

    fun getCategories(): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.query("website_category", arrayOf("name"), null, null, null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result.distinct()
    }

    fun renameCategory(oldName: String, newName: String): Boolean {
        val clean = newName.trim().take(24)
        if (oldName.isBlank() || clean.isBlank() || clean == "全部") return false
        if (oldName == clean) return true
        if (getCategories().any { it == clean }) return false
        writableDatabase.beginTransaction()
        return try {
            val changed = writableDatabase.update(
                "website_category",
                ContentValues().apply { put("name", clean) },
                "name = ?",
                arrayOf(oldName)
            ) > 0
            if (changed) {
                writableDatabase.update(
                    "saved_website",
                    ContentValues().apply { put("category", clean) },
                    "category = ?",
                    arrayOf(oldName)
                )
                writableDatabase.setTransactionSuccessful()
            }
            changed
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /**
     * 删除分类但不删除网页。返回网页被移动到的兜底分类；若该分类为空则返回 null。
     */
    fun deleteCategory(name: String): String? {
        val websiteCount = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM saved_website WHERE category = ?",
            arrayOf(name)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val others = getCategories().filter { it != name }
        var fallback: String? = null
        if (websiteCount > 0) {
            fallback = others.firstOrNull()
            if (fallback == null) {
                fallback = if (name == "未分类") "默认分类" else "未分类"
                ensureCategory(fallback)
            }
        }
        writableDatabase.beginTransaction()
        try {
            if (fallback != null) {
                writableDatabase.update(
                    "saved_website",
                    ContentValues().apply { put("category", fallback) },
                    "category = ?",
                    arrayOf(name)
                )
            }
            writableDatabase.delete("website_category", "name = ?", arrayOf(name))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return fallback
    }

    fun upsertWebsite(website: SavedWebsite): Long {
        ensureCategory(website.category)
        val values = ContentValues().apply {
            put("title", website.title)
            put("url", website.url)
            if (website.iconPath == null) putNull("icon_path") else put("icon_path", website.iconPath)
            put("category", website.category)
            put("created_at", website.createdAt)
            put("last_opened_at", website.lastOpenedAt)
        }
        return writableDatabase.insertWithOnConflict("saved_website", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getWebsiteByUrl(url: String): SavedWebsite? {
        readableDatabase.query(
            "saved_website",
            WEBSITE_COLUMNS,
            "url = ?",
            arrayOf(url),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toWebsite() else null
        }
    }

    fun getWebsites(category: String? = null): List<SavedWebsite> {
        val result = mutableListOf<SavedWebsite>()
        val selection = if (category.isNullOrBlank() || category == "全部") null else "category = ?"
        val args = if (selection == null) null else arrayOf(category)
        readableDatabase.query(
            "saved_website",
            WEBSITE_COLUMNS,
            selection,
            args,
            null,
            null,
            "last_opened_at DESC, created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toWebsite()
        }
        return result
    }

    fun updateWebsiteCategory(id: Long, category: String) {
        ensureCategory(category)
        writableDatabase.update(
            "saved_website",
            ContentValues().apply { put("category", category) },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun markWebsiteOpened(url: String) {
        writableDatabase.update(
            "saved_website",
            ContentValues().apply { put("last_opened_at", System.currentTimeMillis()) },
            "url = ?",
            arrayOf(url)
        )
    }

    /**
     * 删除网页时同步清理 MAL 内部快捷方式映射，并写入墓碑。
     * Android 启动器上的固定图标不一定允许应用强制移除，所以调用方还需要 disableShortcuts。
     */
    fun deleteWebsiteAndShortcutMappings(id: Long, url: String): List<String> {
        val shortcutIds = mutableListOf<String>()
        readableDatabase.query(
            "website_shortcut",
            arrayOf("shortcut_id"),
            "url = ?",
            arrayOf(url),
            null,
            null,
            null
        ).use { cursor -> while (cursor.moveToNext()) shortcutIds += cursor.getString(0) }

        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("saved_website", "id = ?", arrayOf(id.toString()))
            shortcutIds.forEach { shortcutId ->
                writableDatabase.insertWithOnConflict(
                    "deleted_shortcut",
                    null,
                    ContentValues().apply {
                        put("shortcut_id", shortcutId)
                        put("deleted_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.delete("website_shortcut", "url = ?", arrayOf(url))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return shortcutIds
    }

    fun deleteWebsite(id: Long) {
        writableDatabase.delete("saved_website", "id = ?", arrayOf(id.toString()))
    }

    private fun WebsiteShortcut.toValues() = ContentValues().apply {
        if (id > 0) put("id", id)
        put("shortcut_id", shortcutId)
        put("title", title)
        put("url", url)
        if (iconPath == null) putNull("icon_path") else put("icon_path", iconPath)
        put("display_mode", displayMode)
        put("created_at", createdAt)
        put("pin_confirmed", if (pinConfirmed) 1 else 0)
    }

    private fun android.database.Cursor.toShortcut(): WebsiteShortcut = WebsiteShortcut(
        id = getLong(getColumnIndexOrThrow("id")),
        shortcutId = getString(getColumnIndexOrThrow("shortcut_id")),
        title = getString(getColumnIndexOrThrow("title")),
        url = getString(getColumnIndexOrThrow("url")),
        iconPath = getColumnIndexOrThrow("icon_path").let { if (isNull(it)) null else getString(it) },
        displayMode = getString(getColumnIndexOrThrow("display_mode")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        pinConfirmed = getInt(getColumnIndexOrThrow("pin_confirmed")) == 1
    )

    private fun android.database.Cursor.toWebsite(): SavedWebsite = SavedWebsite(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        url = getString(getColumnIndexOrThrow("url")),
        iconPath = getColumnIndexOrThrow("icon_path").let { if (isNull(it)) null else getString(it) },
        category = getString(getColumnIndexOrThrow("category")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        lastOpenedAt = getLong(getColumnIndexOrThrow("last_opened_at"))
    )

    companion object {
        private const val DB_NAME = "browser.db"
        private const val DB_VERSION = 3
        private val SHORTCUT_COLUMNS = arrayOf("id", "shortcut_id", "title", "url", "icon_path", "display_mode", "created_at", "pin_confirmed")
        private val WEBSITE_COLUMNS = arrayOf("id", "title", "url", "icon_path", "category", "created_at", "last_opened_at")
    }
}

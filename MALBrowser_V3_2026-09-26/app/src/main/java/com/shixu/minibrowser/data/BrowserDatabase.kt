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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive-only migrations: never destroy WebView/shortcut mappings during upgrades.
        if (oldVersion < 2) createWebsiteTables(db)
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
        writableDatabase.insertWithOnConflict("website_shortcut", null, shortcut.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
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
        if (clean.isBlank()) return false
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

    fun getCategories(): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.query("website_category", arrayOf("name"), null, null, null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        if ("未分类" !in result) result.add(0, "未分类")
        return result.distinct()
    }

    fun deleteCategory(name: String) {
        if (name == "未分类") return
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update(
                "saved_website",
                ContentValues().apply { put("category", "未分类") },
                "category = ?",
                arrayOf(name)
            )
            writableDatabase.delete("website_category", "name = ?", arrayOf(name))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun upsertWebsite(website: SavedWebsite): Long {
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
        private const val DB_VERSION = 2
        private val SHORTCUT_COLUMNS = arrayOf("id", "shortcut_id", "title", "url", "icon_path", "display_mode", "created_at", "pin_confirmed")
        private val WEBSITE_COLUMNS = arrayOf("id", "title", "url", "icon_path", "category", "created_at", "last_opened_at")
    }
}

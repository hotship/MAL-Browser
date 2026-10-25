package com.shixu.minibrowser

import android.content.Context

object BrowserPrefs {
    private const val NAME = "mal_browser_settings"
    private const val KEY_START_HOME = "start_home"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_WEB_NOTIFICATIONS = "web_notifications"
    private const val KEY_DESKTOP_MODE = "desktop_mode"
    private const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    private const val KEY_SHOW_STATUS_BAR_IN_SHORTCUT = "show_status_bar_in_shortcut"
    private const val KEY_HOME_COLUMNS = "home_columns"

    private const val KEY_GH_OWNER = "backup_github_owner"
    private const val KEY_GH_REPO = "backup_github_repo"
    private const val KEY_GH_BRANCH = "backup_github_branch"
    private const val KEY_GH_PATH = "backup_github_path"
    private const val KEY_GH_AUTO = "backup_github_auto"
    private const val KEY_LOCAL_AUTO = "backup_local_auto"
    private const val KEY_GH_INTERVAL_HOURS = "backup_github_interval_hours"
    private const val KEY_LOCAL_INTERVAL_HOURS = "backup_local_interval_hours"
    private const val KEY_LOCAL_TREE_URI = "backup_local_tree_uri"
    private const val KEY_LOCAL_TREE_LABEL = "backup_local_tree_label"
    private const val KEY_GH_SELECTED = "backup_github_selected_urls"
    private const val KEY_LOCAL_SELECTED = "backup_local_selected_urls"
    private const val KEY_GH_SELECTION_INITIALIZED = "backup_github_selection_initialized"
    private const val KEY_LOCAL_SELECTION_INITIALIZED = "backup_local_selection_initialized"
    private const val KEY_LAST_GH_BACKUP = "backup_last_github_at"
    private const val KEY_LAST_LOCAL_BACKUP = "backup_last_local_at"

    const val DEFAULT_BACKUP_INTERVAL_HOURS = 24L

    fun startOnHome(context: Context): Boolean = prefs(context).getBoolean(KEY_START_HOME, true)
    fun setStartOnHome(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_START_HOME, value).apply()

    fun keepAlive(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_ALIVE, false)
    fun setKeepAlive(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, value).apply()

    fun webNotifications(context: Context): Boolean = prefs(context).getBoolean(KEY_WEB_NOTIFICATIONS, true)
    fun setWebNotifications(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_WEB_NOTIFICATIONS, value).apply()

    fun desktopMode(context: Context): Boolean = prefs(context).getBoolean(KEY_DESKTOP_MODE, false)
    fun setDesktopMode(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    fun notificationPermissionAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)
    fun setNotificationPermissionAsked(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, value).apply()

    /** When enabled, pinned web shortcuts keep the Android top status bar visible. */
    fun showStatusBarInShortcut(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_STATUS_BAR_IN_SHORTCUT, false)
    fun setShowStatusBarInShortcut(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SHOW_STATUS_BAR_IN_SHORTCUT, value).apply()

    /** Number of launcher-style website items per row on the MAL home page. */
    fun homeColumns(context: Context): Int = prefs(context).getInt(KEY_HOME_COLUMNS, 4).coerceIn(3, 6)
    fun setHomeColumns(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_HOME_COLUMNS, value.coerceIn(3, 6)).apply()

    fun githubOwner(context: Context): String = prefs(context).getString(KEY_GH_OWNER, "").orEmpty()
    fun setGithubOwner(context: Context, value: String) = prefs(context).edit().putString(KEY_GH_OWNER, value.trim()).apply()

    fun githubRepo(context: Context): String = prefs(context).getString(KEY_GH_REPO, "").orEmpty()
    fun setGithubRepo(context: Context, value: String) = prefs(context).edit().putString(KEY_GH_REPO, value.trim().removeSuffix(".git")).apply()

    fun githubBranch(context: Context): String = prefs(context).getString(KEY_GH_BRANCH, "main").orEmpty().ifBlank { "main" }
    fun setGithubBranch(context: Context, value: String) = prefs(context).edit().putString(KEY_GH_BRANCH, value.trim().ifBlank { "main" }).apply()

    fun githubPath(context: Context): String = prefs(context).getString(KEY_GH_PATH, "mal-backup.json").orEmpty().ifBlank { "mal-backup.json" }
    fun setGithubPath(context: Context, value: String) = prefs(context).edit().putString(KEY_GH_PATH, value.trim().ifBlank { "mal-backup.json" }).apply()

    fun githubAutoBackup(context: Context): Boolean = prefs(context).getBoolean(KEY_GH_AUTO, false)
    fun setGithubAutoBackup(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_GH_AUTO, value).apply()

    fun localAutoBackup(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCAL_AUTO, false)
    fun setLocalAutoBackup(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LOCAL_AUTO, value).apply()

    fun githubBackupIntervalHours(context: Context): Long =
        prefs(context).getLong(KEY_GH_INTERVAL_HOURS, DEFAULT_BACKUP_INTERVAL_HOURS).coerceAtLeast(1L)

    fun setGithubBackupIntervalHours(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_GH_INTERVAL_HOURS, value.coerceAtLeast(1L)).apply()

    fun localBackupIntervalHours(context: Context): Long =
        prefs(context).getLong(KEY_LOCAL_INTERVAL_HOURS, DEFAULT_BACKUP_INTERVAL_HOURS).coerceAtLeast(1L)

    fun setLocalBackupIntervalHours(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_LOCAL_INTERVAL_HOURS, value.coerceAtLeast(1L)).apply()

    fun localBackupTreeUri(context: Context): String = prefs(context).getString(KEY_LOCAL_TREE_URI, "").orEmpty()
    fun localBackupTreeLabel(context: Context): String = prefs(context).getString(KEY_LOCAL_TREE_LABEL, "").orEmpty()
    fun setLocalBackupTree(context: Context, uri: String, label: String) {
        prefs(context).edit()
            .putString(KEY_LOCAL_TREE_URI, uri.trim())
            .putString(KEY_LOCAL_TREE_LABEL, label.trim())
            .apply()
    }

    fun clearLocalBackupTree(context: Context) {
        prefs(context).edit().remove(KEY_LOCAL_TREE_URI).remove(KEY_LOCAL_TREE_LABEL).apply()
    }

    fun githubSelectionInitialized(context: Context): Boolean = prefs(context).getBoolean(KEY_GH_SELECTION_INITIALIZED, false)
    fun githubSelectedUrls(context: Context): Set<String> = prefs(context).getStringSet(KEY_GH_SELECTED, emptySet())?.toSet().orEmpty()
    fun setGithubSelectedUrls(context: Context, urls: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_GH_SELECTED, urls.toSet())
            .putBoolean(KEY_GH_SELECTION_INITIALIZED, true)
            .apply()
    }

    fun localSelectionInitialized(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCAL_SELECTION_INITIALIZED, false)
    fun localSelectedUrls(context: Context): Set<String> = prefs(context).getStringSet(KEY_LOCAL_SELECTED, emptySet())?.toSet().orEmpty()
    fun setLocalSelectedUrls(context: Context, urls: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_LOCAL_SELECTED, urls.toSet())
            .putBoolean(KEY_LOCAL_SELECTION_INITIALIZED, true)
            .apply()
    }

    fun lastGithubBackupAt(context: Context): Long = prefs(context).getLong(KEY_LAST_GH_BACKUP, 0L)
    fun setLastGithubBackupAt(context: Context, value: Long) = prefs(context).edit().putLong(KEY_LAST_GH_BACKUP, value).apply()

    fun lastLocalBackupAt(context: Context): Long = prefs(context).getLong(KEY_LAST_LOCAL_BACKUP, 0L)
    fun setLastLocalBackupAt(context: Context, value: Long) = prefs(context).edit().putLong(KEY_LAST_LOCAL_BACKUP, value).apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

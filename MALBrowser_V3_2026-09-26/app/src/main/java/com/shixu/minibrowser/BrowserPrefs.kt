package com.shixu.minibrowser

import android.content.Context

object BrowserPrefs {
    private const val NAME = "mal_browser_settings"
    private const val KEY_START_HOME = "start_home"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_WEB_NOTIFICATIONS = "web_notifications"
    private const val KEY_DESKTOP_MODE = "desktop_mode"
    private const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"

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

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}

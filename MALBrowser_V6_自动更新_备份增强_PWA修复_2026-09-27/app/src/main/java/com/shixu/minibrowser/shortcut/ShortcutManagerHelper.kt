package com.shixu.minibrowser.shortcut

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import com.shixu.minibrowser.MainActivity
import com.shixu.minibrowser.R
import com.shixu.minibrowser.ShortcutActivity
import com.shixu.minibrowser.data.BrowserDatabase
import com.shixu.minibrowser.data.WebsiteShortcut
import java.io.File

object ShortcutManagerHelper {
    const val ACTION_OPEN_SHORTCUT = "com.shixu.minibrowser.OPEN_SHORTCUT"
    const val EXTRA_SHORTCUT_ID = "shortcut_id"
    const val EXTRA_SHORTCUT_URL = "shortcut_url"
    const val EXTRA_SHORTCUT_TITLE = "shortcut_title"
    const val EXTRA_SHORTCUT_ICON_PATH = "shortcut_icon_path"

    fun isPinSupported(context: Context): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        return manager?.isRequestPinShortcutSupported == true
    }

    fun requestPin(context: Context, shortcut: WebsiteShortcut): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false

        val launchIntent = Intent(context, ShortcutActivity::class.java).apply {
            action = ACTION_OPEN_SHORTCUT
            putExtra(EXTRA_SHORTCUT_ID, shortcut.shortcutId)
            putExtra(EXTRA_SHORTCUT_URL, shortcut.url)
            putExtra(EXTRA_SHORTCUT_TITLE, shortcut.title)
            putExtra(EXTRA_SHORTCUT_ICON_PATH, shortcut.iconPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        val builder = ShortcutInfo.Builder(context, shortcut.shortcutId)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(shortcut.title.take(10).ifBlank { "网页" })
            .setLongLabel(shortcut.title.take(25).ifBlank { "网页" })
            .setIntent(launchIntent)

        val iconFile = resolveIconFile(context, shortcut.iconPath)
        val bitmap = iconFile?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bitmap != null) {
            builder.setIcon(Icon.createWithBitmap(bitmap))
        } else {
            builder.setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
        }

        val info = builder.build()
        val callbackIntent = manager.createShortcutResultIntent(info).apply {
            setClass(context, ShortcutPinnedReceiver::class.java)
            putExtra(EXTRA_SHORTCUT_ID, shortcut.shortcutId)
        }
        val requestCode = shortcut.shortcutId.hashCode() and 0x7fffffff
        val callback = PendingIntent.getBroadcast(
            context,
            requestCode,
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return manager.requestPinShortcut(info, callback.intentSender)
    }

    fun pinnedIds(context: Context): Set<String> {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return emptySet()
        return manager.pinnedShortcuts.mapTo(mutableSetOf()) { it.id }
    }

    fun disableShortcuts(context: Context, shortcutIds: List<String>) {
        if (shortcutIds.isEmpty()) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching { manager.disableShortcuts(shortcutIds, "网页已从 MAL 删除") }
    }

    fun updateShortcut(context: Context, shortcut: WebsiteShortcut) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val launchIntent = Intent(context, ShortcutActivity::class.java).apply {
            action = ACTION_OPEN_SHORTCUT
            putExtra(EXTRA_SHORTCUT_ID, shortcut.shortcutId)
            putExtra(EXTRA_SHORTCUT_URL, shortcut.url)
            putExtra(EXTRA_SHORTCUT_TITLE, shortcut.title)
            putExtra(EXTRA_SHORTCUT_ICON_PATH, shortcut.iconPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val builder = ShortcutInfo.Builder(context, shortcut.shortcutId)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(shortcut.title.take(10).ifBlank { "网页" })
            .setLongLabel(shortcut.title.take(25).ifBlank { "网页" })
            .setIntent(launchIntent)

        resolveIconFile(context, shortcut.iconPath)?.takeIf { it.isFile }?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                builder.setIcon(Icon.createWithBitmap(bitmap))
            }
        }
        manager.updateShortcuts(listOf(builder.build()))
    }

    fun republishKnownShortcuts(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val pinned = runCatching { manager.pinnedShortcuts.mapTo(mutableSetOf<String>()) { it.id } }.getOrElse { mutableSetOf() }
        if (pinned.isEmpty()) return
        BrowserDatabase(context).use { db ->
            db.getAllShortcuts()
                .filter { it.shortcutId in pinned }
                .forEach { shortcut ->
                    runCatching { updateShortcut(context, shortcut) }
                    if (!shortcut.pinConfirmed) db.markPinConfirmed(shortcut.shortcutId)
                }
        }
    }

    private fun resolveIconFile(context: Context, iconPath: String?): File? {
        if (iconPath.isNullOrBlank()) return null
        val stored = File(iconPath)
        return if (stored.isAbsolute) stored else File(context.filesDir, iconPath)
    }

}

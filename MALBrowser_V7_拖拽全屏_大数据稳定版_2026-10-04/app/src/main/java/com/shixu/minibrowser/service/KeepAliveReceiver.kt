package com.shixu.minibrowser.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.shixu.minibrowser.BrowserPrefs
import com.shixu.minibrowser.backup.BackupScheduler
import com.shixu.minibrowser.shortcut.ShortcutManagerHelper

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Re-register best-effort periodic backup alarms after reboot/app update.
        runCatching { BackupScheduler.rescheduleAll(context) }

        // Refresh the launcher-owned pinned shortcut definitions when the system still has them.
        runCatching { ShortcutManagerHelper.republishKnownShortcuts(context) }

        if (BrowserPrefs.keepAlive(context)) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }
    }
}

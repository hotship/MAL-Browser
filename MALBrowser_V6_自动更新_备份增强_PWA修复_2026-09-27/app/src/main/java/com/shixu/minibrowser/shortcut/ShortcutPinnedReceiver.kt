package com.shixu.minibrowser.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shixu.minibrowser.data.BrowserDatabase

class ShortcutPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shortcutId = intent.getStringExtra(ShortcutManagerHelper.EXTRA_SHORTCUT_ID) ?: return
        BrowserDatabase(context).use { db ->
            db.markPinConfirmed(shortcutId)
        }
    }
}

package com.shixu.minibrowser.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BackupScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val requestedGithub = intent?.action == BackupScheduler.ACTION_GITHUB
        val requestedLocal = intent?.action == BackupScheduler.ACTION_LOCAL
        if (!requestedGithub && !requestedLocal) return

        val pending = goAsync()
        Thread {
            try {
                BackupManager.runDueAutomaticBackupsBlocking(
                    context.applicationContext,
                    allowGithub = requestedGithub,
                    allowLocal = requestedLocal
                )
            } finally {
                pending.finish()
            }
        }.start()
    }
}

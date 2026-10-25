package com.shixu.minibrowser.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.shixu.minibrowser.BrowserPrefs

/**
 * Best-effort periodic scheduler for automatic MAL backups.
 * Uses inexact alarms so it does not require exact-alarm permission and remains battery friendly.
 * Android may defer alarms while the device is idle; MainActivity also performs a due check on resume.
 */
object BackupScheduler {
    const val ACTION_GITHUB = "com.shixu.minibrowser.backup.AUTO_GITHUB"
    const val ACTION_LOCAL = "com.shixu.minibrowser.backup.AUTO_LOCAL"

    private const val REQUEST_GITHUB = 4101
    private const val REQUEST_LOCAL = 4102
    private const val MIN_INTERVAL_MS = 60L * 60L * 1000L

    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        schedule(
            app,
            ACTION_GITHUB,
            REQUEST_GITHUB,
            BrowserPrefs.githubAutoBackup(app),
            BrowserPrefs.githubBackupIntervalHours(app)
        )
        schedule(
            app,
            ACTION_LOCAL,
            REQUEST_LOCAL,
            BrowserPrefs.localAutoBackup(app),
            BrowserPrefs.localBackupIntervalHours(app)
        )
    }

    private fun schedule(context: Context, action: String, requestCode: Int, enabled: Boolean, intervalHours: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BackupScheduleReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.cancel(pending)
        if (!enabled) return

        val interval = (intervalHours.coerceAtLeast(1L) * 60L * 60L * 1000L).coerceAtLeast(MIN_INTERVAL_MS)
        val first = SystemClock.elapsedRealtime() + interval
        alarm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, first, interval, pending)
    }
}

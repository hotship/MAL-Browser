package com.shixu.minibrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shixu.minibrowser.databinding.ActivitySettingsBinding
import com.shixu.minibrowser.service.KeepAliveService

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(this, if (granted) "系统通知权限已允许。" else "系统通知权限未允许。", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureInsets()
        bindState()
        bindActions()
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun bindState() {
        binding.startHomeSwitch.isChecked = BrowserPrefs.startOnHome(this)
        binding.desktopModeSwitch.isChecked = BrowserPrefs.desktopMode(this)
        binding.webNotificationsSwitch.isChecked = BrowserPrefs.webNotifications(this)
        binding.keepAliveSwitch.isChecked = BrowserPrefs.keepAlive(this)
    }

    private fun bindActions() {
        binding.backButton.setOnClickListener { finish() }
        binding.startHomeSwitch.setOnCheckedChangeListener { _, checked -> BrowserPrefs.setStartOnHome(this, checked) }
        binding.desktopModeSwitch.setOnCheckedChangeListener { _, checked ->
            BrowserPrefs.setDesktopMode(this, checked)
            Toast.makeText(this, "重新打开网页后生效。", Toast.LENGTH_SHORT).show()
        }
        binding.webNotificationsSwitch.setOnCheckedChangeListener { _, checked ->
            BrowserPrefs.setWebNotifications(this, checked)
            if (checked) requestNotificationPermissionIfNeeded()
        }
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            BrowserPrefs.setKeepAlive(this, checked)
            if (checked) {
                requestNotificationPermissionIfNeeded()
                runCatching { ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java)) }
                    .onFailure { Toast.makeText(this, "保活服务启动失败：${it.message}", Toast.LENGTH_LONG).show() }
            } else {
                stopService(Intent(this, KeepAliveService::class.java))
            }
        }
        binding.notificationPermissionButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                runCatching { startActivity(intent) }
            }
        }
        binding.batterySettingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { startActivity(intent) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

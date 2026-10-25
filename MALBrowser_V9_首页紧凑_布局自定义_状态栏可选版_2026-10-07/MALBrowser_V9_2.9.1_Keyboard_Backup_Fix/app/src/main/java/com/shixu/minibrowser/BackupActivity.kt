package com.shixu.minibrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.shixu.minibrowser.backup.BackupManager
import com.shixu.minibrowser.backup.BackupScheduler
import com.shixu.minibrowser.backup.SecretStore
import com.shixu.minibrowser.data.BrowserDatabase
import com.shixu.minibrowser.databinding.ActivityBackupBinding

class BackupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBackupBinding
    private lateinit var database: BrowserDatabase
    private var bindingState = false

    private val chooseFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        val label = describeTreeUri(uri)
        BrowserPrefs.setLocalBackupTree(this, uri.toString(), label)
        refreshLocalFolderLabel()
        BackupScheduler.rescheduleAll(this)
        Toast.makeText(this, "已选择备份位置：$label", Toast.LENGTH_SHORT).show()
    }

    private val restoreFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        setLocalBusy(true, "正在读取手机备份…")
        BackupManager.restoreLocal(this, uri) { result ->
            setLocalBusy(false, result.message)
            refreshSelectionLabels()
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = BrowserDatabase(this)
        configureInsets()
        configureFrequencySpinners()
        bindState()
        bindActions()
        refreshSelectionLabels()
        refreshLocalFolderLabel()
    }

    private fun configureInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun configureFrequencySpinners() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, FREQUENCY_LABELS).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.githubFrequencySpinner.adapter = adapter
        binding.localFrequencySpinner.adapter = adapter
    }

    private fun bindState() {
        bindingState = true
        binding.githubOwnerInput.setText(BrowserPrefs.githubOwner(this))
        binding.githubRepoInput.setText(BrowserPrefs.githubRepo(this))
        binding.githubBranchInput.setText(BrowserPrefs.githubBranch(this))
        binding.githubPathInput.setText(BrowserPrefs.githubPath(this))
        binding.githubTokenInput.setText(SecretStore.getGithubToken(this))
        binding.githubAutoSwitch.isChecked = BrowserPrefs.githubAutoBackup(this)
        binding.localAutoSwitch.isChecked = BrowserPrefs.localAutoBackup(this)
        binding.githubFrequencySpinner.setSelection(indexForHours(BrowserPrefs.githubBackupIntervalHours(this)))
        binding.localFrequencySpinner.setSelection(indexForHours(BrowserPrefs.localBackupIntervalHours(this)))
        bindingState = false
    }

    private fun bindActions() {
        binding.backButton.setOnClickListener { saveConfig(); finish() }
        binding.githubSelectSitesButton.setOnClickListener { chooseSites(forGithub = true) }
        binding.localSelectSitesButton.setOnClickListener { chooseSites(forGithub = false) }
        binding.localChooseFolderButton.setOnClickListener { chooseFolderLauncher.launch(null) }
        binding.localRestoreButton.setOnClickListener { restoreFileLauncher.launch(arrayOf("*/*")) }

        binding.githubAutoSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            BrowserPrefs.setGithubAutoBackup(this, checked)
            BackupScheduler.rescheduleAll(this)
            if (checked) BackupManager.maybeRunAutomaticBackups(this)
        }
        binding.localAutoSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingState) return@setOnCheckedChangeListener
            BrowserPrefs.setLocalAutoBackup(this, checked)
            BackupScheduler.rescheduleAll(this)
            if (checked) BackupManager.maybeRunAutomaticBackups(this)
        }

        binding.githubFrequencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingState) return
                BrowserPrefs.setGithubBackupIntervalHours(this@BackupActivity, FREQUENCY_HOURS[position])
                BackupScheduler.rescheduleAll(this@BackupActivity)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.localFrequencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (bindingState) return
                BrowserPrefs.setLocalBackupIntervalHours(this@BackupActivity, FREQUENCY_HOURS[position])
                BackupScheduler.rescheduleAll(this@BackupActivity)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.githubTestButton.setOnClickListener {
            saveConfig()
            setGithubBusy(true, "正在测试 GitHub…")
            BackupManager.testGithub(this) { result ->
                setGithubBusy(false, result.message)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
        binding.githubBackupButton.setOnClickListener {
            saveConfig()
            setGithubBusy(true, "正在上传并回读校验 SHA-256…")
            BackupManager.backupGithub(this) { result ->
                setGithubBusy(false, result.message)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
        binding.githubRestoreButton.setOnClickListener {
            saveConfig()
            AlertDialog.Builder(this)
                .setTitle("从 GitHub 恢复？")
                .setMessage("会把备份中的网页、分类、图标、桌面快捷方式映射和基础设置合并回 MAL；不会覆盖或恢复网站 Cookie、密码、OAuth 会话和网页自己的本地数据库。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    setGithubBusy(true, "正在读取并恢复 GitHub 备份…")
                    BackupManager.restoreGithub(this) { result ->
                        setGithubBusy(false, result.message)
                        refreshSelectionLabels()
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }.show()
        }
        binding.localBackupButton.setOnClickListener {
            saveConfig()
            setLocalBusy(true, "正在生成本地备份…")
            BackupManager.backupLocal(this) { result ->
                setLocalBusy(false, result.message)
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun chooseSites(forGithub: Boolean) {
        val sites = database.getWebsites()
        if (sites.isEmpty()) {
            Toast.makeText(this, "首页还没有保存网页。", Toast.LENGTH_SHORT).show()
            return
        }
        val current = if (forGithub) BackupManager.selectedGithubUrls(this, sites) else BackupManager.selectedLocalUrls(this, sites)
        val checked = BooleanArray(sites.size) { sites[it].url in current }
        val labels = sites.map { site ->
            val host = runCatching { Uri.parse(site.url).host.orEmpty() }.getOrDefault("")
            if (host.isBlank() || host == site.title) site.title else "${site.title} · $host"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(if (forGithub) "选择 GitHub 备份网页" else "选择手机下载备份网页")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNeutralButton("全选") { _, _ ->
                val urls = sites.mapTo(linkedSetOf()) { it.url }
                if (forGithub) BrowserPrefs.setGithubSelectedUrls(this, urls) else BrowserPrefs.setLocalSelectedUrls(this, urls)
                refreshSelectionLabels()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val urls = sites.indices.filter { checked[it] }.mapTo(linkedSetOf()) { sites[it].url }
                if (forGithub) BrowserPrefs.setGithubSelectedUrls(this, urls) else BrowserPrefs.setLocalSelectedUrls(this, urls)
                refreshSelectionLabels()
            }.show()
    }

    private fun refreshSelectionLabels() {
        val sites = database.getWebsites()
        val gh = BackupManager.selectedGithubUrls(this, sites).count { url -> sites.any { it.url == url } }
        val local = BackupManager.selectedLocalUrls(this, sites).count { url -> sites.any { it.url == url } }
        binding.githubSelectionText.text = "GitHub 备份网页：$gh / ${sites.size}"
        binding.localSelectionText.text = "手机下载网页：$local / ${sites.size}"
    }

    private fun refreshLocalFolderLabel() {
        val label = BrowserPrefs.localBackupTreeLabel(this)
        binding.localFolderText.text = if (label.isBlank()) {
            "保存位置：下载/MAL/Backups（默认）"
        } else {
            "保存位置：$label"
        }
    }

    private fun saveConfig() {
        BrowserPrefs.setGithubOwner(this, binding.githubOwnerInput.text?.toString().orEmpty())
        BrowserPrefs.setGithubRepo(this, binding.githubRepoInput.text?.toString().orEmpty())
        BrowserPrefs.setGithubBranch(this, binding.githubBranchInput.text?.toString().orEmpty())
        BrowserPrefs.setGithubPath(this, binding.githubPathInput.text?.toString().orEmpty())
        SecretStore.setGithubToken(this, binding.githubTokenInput.text?.toString().orEmpty())
        BrowserPrefs.setGithubAutoBackup(this, binding.githubAutoSwitch.isChecked)
        BrowserPrefs.setLocalAutoBackup(this, binding.localAutoSwitch.isChecked)
        BrowserPrefs.setGithubBackupIntervalHours(this, FREQUENCY_HOURS[binding.githubFrequencySpinner.selectedItemPosition.coerceIn(FREQUENCY_HOURS.indices)])
        BrowserPrefs.setLocalBackupIntervalHours(this, FREQUENCY_HOURS[binding.localFrequencySpinner.selectedItemPosition.coerceIn(FREQUENCY_HOURS.indices)])
        BackupScheduler.rescheduleAll(this)
    }

    private fun setGithubBusy(busy: Boolean, status: String) {
        binding.githubStatusText.text = status
        binding.githubTestButton.isEnabled = !busy
        binding.githubBackupButton.isEnabled = !busy
        binding.githubRestoreButton.isEnabled = !busy
    }

    private fun setLocalBusy(busy: Boolean, status: String) {
        binding.localStatusText.text = status
        binding.localBackupButton.isEnabled = !busy
        binding.localRestoreButton.isEnabled = !busy
        binding.localChooseFolderButton.isEnabled = !busy
    }

    private fun describeTreeUri(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        if (id.isBlank()) return "已选择文件夹"
        val decoded = Uri.decode(id)
        return when {
            decoded.startsWith("primary:") -> "内部存储/" + decoded.removePrefix("primary:").trim('/').ifBlank { "根目录" }
            else -> decoded.replace(':', '/').trim('/').ifBlank { "已选择文件夹" }
        }
    }

    private fun indexForHours(hours: Long): Int {
        val exact = FREQUENCY_HOURS.indexOf(hours)
        if (exact >= 0) return exact
        return FREQUENCY_HOURS.indices.minByOrNull { kotlin.math.abs(FREQUENCY_HOURS[it] - hours) } ?: 2
    }

    override fun onPause() {
        saveConfig()
        super.onPause()
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    companion object {
        private val FREQUENCY_HOURS = longArrayOf(6L, 12L, 24L, 72L, 168L)
        private val FREQUENCY_LABELS = listOf("每 6 小时", "每 12 小时", "每天", "每 3 天", "每 7 天")
    }
}

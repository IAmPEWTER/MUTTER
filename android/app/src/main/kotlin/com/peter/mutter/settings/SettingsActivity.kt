package com.peter.mutter.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.peter.mutter.BuildConfig
import com.peter.mutter.Prefs
import com.peter.mutter.R
import com.peter.mutter.updater.UpdateChecker
import com.peter.mutter.updater.UpdateCheckResult
import com.peter.mutter.updater.UpdateInstallResult
import com.peter.mutter.updater.UpdateInstaller
import com.peter.mutter.updater.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var updaterStatus: TextView
    private lateinit var btnCheckUpdates: Button
    private var pendingManifest: UpdateManifest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toggle = findViewById<CompoundButton>(R.id.toggle_intercept)
        toggle.isChecked = Prefs.isInterceptEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            Prefs.setInterceptEnabled(this, checked)
        }

        findViewById<TextView>(R.id.about_text).text =
            getString(R.string.settings_about_desc, BuildConfig.VERSION_NAME)

        updaterStatus = findViewById(R.id.updater_status)
        btnCheckUpdates = findViewById(R.id.btn_check_updates)
        updaterStatus.text = getString(R.string.updater_idle)
        btnCheckUpdates.setOnClickListener { onUpdaterButtonClicked() }
    }

    private fun onUpdaterButtonClicked() {
        val manifest = pendingManifest
        if (manifest != null) {
            startInstall(manifest)
        } else {
            startCheck()
        }
    }

    private fun startCheck() {
        btnCheckUpdates.isEnabled = false
        updaterStatus.text = getString(R.string.updater_checking)
        lifecycleScope.launch {
            val result = UpdateChecker(this@SettingsActivity).check(force = true)
            withContext(Dispatchers.Main) {
                btnCheckUpdates.isEnabled = true
                when (result) {
                    is UpdateCheckResult.UpToDate -> {
                        updaterStatus.text = getString(R.string.updater_up_to_date)
                        pendingManifest = null
                        btnCheckUpdates.text = getString(R.string.action_check_updates)
                    }
                    is UpdateCheckResult.Available -> {
                        pendingManifest = result.manifest
                        updaterStatus.text =
                            getString(R.string.updater_available, result.manifest.versionName)
                        btnCheckUpdates.text = getString(R.string.updater_install)
                    }
                    is UpdateCheckResult.Error -> {
                        updaterStatus.text = getString(R.string.updater_error, result.message)
                    }
                }
            }
        }
    }

    private fun startInstall(manifest: UpdateManifest) {
        btnCheckUpdates.isEnabled = false
        lifecycleScope.launch {
            val result = UpdateInstaller(this@SettingsActivity).downloadAndStage(manifest) { d, t ->
                runOnUiThread {
                    val pct = if (t > 0) ((d * 100 / t).toInt()) else 0
                    updaterStatus.text = getString(R.string.updater_downloading, pct.coerceIn(0, 100))
                }
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is UpdateInstallResult.Staged -> {
                        // System installer UI opens via the broadcast receiver
                        // once PackageInstaller sends STATUS_PENDING_USER_ACTION.
                        updaterStatus.text = getString(R.string.updater_downloading, 100)
                    }
                    is UpdateInstallResult.NeedsInstallPermission -> {
                        updaterStatus.text = getString(R.string.updater_needs_perm)
                        btnCheckUpdates.isEnabled = true
                        openUnknownSourcesSettings()
                    }
                    is UpdateInstallResult.Failure -> {
                        updaterStatus.text = getString(R.string.updater_error, result.message)
                        btnCheckUpdates.isEnabled = true
                    }
                }
            }
        }
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
        }
        try { startActivity(intent) } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
        }
    }
}

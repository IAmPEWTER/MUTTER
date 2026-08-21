package com.peter.mutter.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.peter.mutter.ModelDownloader
import com.peter.mutter.MutterAccessibilityService
import com.peter.mutter.R
import com.peter.mutter.settings.SettingsActivity
import com.peter.mutter.updater.UpdateCheckResult
import com.peter.mutter.updater.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : AppCompatActivity() {

    private lateinit var statusMic: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusBattery: TextView
    private lateinit var statusModel: TextView
    private lateinit var btnMic: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnBattery: Button
    private lateinit var btnModel: Button
    private lateinit var progressModel: ProgressBar
    private lateinit var modelDetail: TextView
    private lateinit var testField: EditText

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh()
        }

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        statusMic = findViewById(R.id.status_mic)
        statusAccessibility = findViewById(R.id.status_accessibility)
        statusBattery = findViewById(R.id.status_battery)
        statusModel = findViewById(R.id.status_model)
        btnMic = findViewById(R.id.btn_mic)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnBattery = findViewById(R.id.btn_battery)
        btnModel = findViewById(R.id.btn_model)
        progressModel = findViewById(R.id.progress_model)
        modelDetail = findViewById(R.id.model_detail)
        testField = findViewById(R.id.test_field)

        val btnSettings = findViewById<Button>(R.id.btn_settings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        kickOffSilentUpdateCheck(btnSettings)
        // Ask up front rather than as a rider on the mic button: if mic was
        // already granted that button is disabled, and the service reports a
        // blocked microphone or a missing model through notifications.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        btnMic.setOnClickListener {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        findViewById<Button>(R.id.btn_restricted).setOnClickListener {
            // Lands on this app's App-info page; user taps ⋮ → "Allow
            // restricted settings". No public intent for the toggle itself
            // (Android 13+ deliberately requires the menu action).
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            try { startActivity(intent) } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                // some OEMs hide this; fall back to top-level settings
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        btnBattery.setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                refresh()
                return@setOnClickListener
            }
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try { startActivity(intent) } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        btnModel.setOnClickListener { startDownload() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        setStatus(statusMic, btnMic, micOk)

        val accOk = isAccessibilityEnabled()
        setStatus(statusAccessibility, btnAccessibility, accOk)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        setStatus(statusBattery, btnBattery, battOk)

        val modelOk = ModelDownloader(this).isPresent()
        setStatus(statusModel, btnModel, modelOk)
        modelDetail.visibility = if (modelOk) View.GONE else View.VISIBLE
    }

    private fun setStatus(label: TextView, button: Button, ok: Boolean) {
        label.text = if (ok) "✓ done" else "✗ pending"
        label.setTextColor(getColor(if (ok) R.color.text_muted else R.color.text))
        button.isEnabled = !ok
        // Stash the original label the first time through. Reading button.text
        // for the not-ok case meant that once a step went green its button was
        // stuck reading DONE, so a permission revoked later showed "✗ pending"
        // next to a button that claimed it was already handled.
        val original = button.tag as? CharSequence ?: button.text.also { button.tag = it }
        button.text = if (ok) getString(R.string.action_done) else original
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = packageName + "/" + MutterAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun kickOffSilentUpdateCheck(settingsBtn: Button) {
        lifecycleScope.launch {
            val result = UpdateChecker(this@SetupActivity).check(force = false)
            if (result is UpdateCheckResult.Available) {
                withContext(Dispatchers.Main) {
                    settingsBtn.text = getString(R.string.action_settings) +
                        " · update " + result.manifest.versionName
                }
            }
        }
    }

    private fun startDownload() {
        btnModel.isEnabled = false
        progressModel.visibility = View.VISIBLE
        progressModel.progress = 0
        modelDetail.text = "Starting…"
        val downloader = ModelDownloader(this)
        lifecycleScope.launch {
            val result = downloader.download { downloaded, total, file ->
                runOnUiThread {
                    val pct = if (total > 0) ((downloaded * 100 / total).toInt()) else 0
                    progressModel.progress = pct.coerceIn(0, 100)
                    modelDetail.text = String.format(
                        "%s · %s / %s",
                        file,
                        Formatter.formatShortFileSize(this@SetupActivity, downloaded),
                        if (total > 0) Formatter.formatShortFileSize(this@SetupActivity, total) else "?",
                    )
                }
            }
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    modelDetail.text = "Download complete."
                    // The service loads the model once, at connect. Without
                    // this it would keep running with no engine and a degraded
                    // VAD until something restarted it.
                    sendBroadcast(
                        Intent(MutterAccessibilityService.ACTION_MODEL_READY)
                            .setPackage(packageName)
                    )
                } else {
                    modelDetail.text = "Failed: ${result.exceptionOrNull()?.message}"
                }
                btnModel.isEnabled = result.isFailure
                refresh()
            }
        }
    }
}

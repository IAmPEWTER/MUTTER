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

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnMic.setOnClickListener {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            // Also request POST_NOTIFICATIONS (Android 13+) so the FG notif shows.
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        button.isEnabled = !ok
        button.text = if (ok) getString(R.string.action_done) else button.text
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = packageName + "/" + MutterAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
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
                } else {
                    modelDetail.text = "Failed: ${result.exceptionOrNull()?.message}"
                }
                btnModel.isEnabled = result.isFailure
                refresh()
            }
        }
    }
}

package com.peter.mutter.settings

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.peter.mutter.Prefs
import com.peter.mutter.R

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toggle = findViewById<SwitchCompat>(R.id.toggle_intercept)
        toggle.isChecked = Prefs.isInterceptEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            Prefs.setInterceptEnabled(this, checked)
        }

        findViewById<TextView>(R.id.about_text).text = getString(R.string.settings_about_desc)
    }
}

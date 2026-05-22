package com.peter.mutter.updater

import android.content.Context
import android.util.Log
import com.peter.mutter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateCheckResult {
    data class Available(val manifest: UpdateManifest) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class UpdateChecker(private val context: Context) {

    private val tag = "MutterUpdater"

    suspend fun check(force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("mutter_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force) {
            val last = prefs.getLong(UpdateConstants.PREF_LAST_CHECK_AT, 0L)
            if (last > 0 && now - last < UpdateConstants.MIN_CHECK_INTERVAL_MS) {
                Log.d(tag, "skip check: last=${now - last}ms ago")
                return@withContext UpdateCheckResult.UpToDate
            }
        }
        val url = prefs.getString(UpdateConstants.PREF_MANIFEST_URL_OVERRIDE, null)
            ?: UpdateConstants.DEFAULT_MANIFEST_URL
        try {
            val body = fetch(url)
            val manifest = UpdateManifest.parse(body)
            prefs.edit().putLong(UpdateConstants.PREF_LAST_CHECK_AT, now).apply()
            val current = BuildConfig.VERSION_CODE
            Log.i(tag, "manifest v${manifest.versionCode} (${manifest.versionName}), current=$current")
            val skip = prefs.getInt(UpdateConstants.PREF_SKIP_VERSION_CODE, 0)
            when {
                manifest.versionCode <= current -> UpdateCheckResult.UpToDate
                !force && manifest.versionCode == skip -> {
                    Log.i(tag, "user skipped v${manifest.versionCode}")
                    UpdateCheckResult.UpToDate
                }
                else -> UpdateCheckResult.Available(manifest)
            }
        } catch (t: Throwable) {
            Log.w(tag, "check failed: ${t.message}")
            UpdateCheckResult.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    fun skip(versionCode: Int) {
        val prefs = context.getSharedPreferences("mutter_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt(UpdateConstants.PREF_SKIP_VERSION_CODE, versionCode).apply()
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UpdateConstants.USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return conn.inputStream.use { it.bufferedReader().readText() }
    }
}

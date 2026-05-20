package com.peter.mutter

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "mutter_prefs"
    const val KEY_INTERCEPT_ENABLED = "intercept_enabled"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isInterceptEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_INTERCEPT_ENABLED, true)

    fun setInterceptEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_INTERCEPT_ENABLED, enabled).apply()
    }
}

package com.peter.mutter.updater

object UpdateConstants {
    // GH Releases `latest/download/` resolves to the most recent release's
    // asset of the given name. Stable URL once any release exists.
    const val DEFAULT_MANIFEST_URL =
        "https://github.com/IAmPEWTER/mutter-releases/releases/latest/download/latest.json"

    const val USER_AGENT = "MutterUpdater/1"

    // SharedPreferences keys (share `mutter_prefs` file with Prefs.kt).
    const val PREF_MANIFEST_URL_OVERRIDE = "updater_manifest_url"
    const val PREF_LAST_CHECK_AT = "updater_last_check_at"
    const val PREF_SKIP_VERSION_CODE = "updater_skip_version_code"

    const val MIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
}

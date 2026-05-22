package com.peter.mutter.updater

import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String?,
    val apkSize: Long?,
    val notes: String?,
) {
    companion object {
        fun parse(body: String): UpdateManifest {
            val json = JSONObject(body)
            val vc = json.getInt("versionCode")
            val vn = json.getString("versionName")
            val url = json.getString("apkUrl")
            require(vc > 0) { "versionCode must be positive" }
            require(vn.isNotBlank()) { "versionName must not be blank" }
            // Schemes other than https are allowed for local testing; integrity
            // is enforced via SHA-256 verify in UpdateInstaller, not the URL.
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "apkUrl must be http(s)"
            }
            return UpdateManifest(
                versionCode = vc,
                versionName = vn,
                apkUrl = url,
                apkSha256 = json.optString("apkSha256").ifBlank { null },
                apkSize = json.optLong("apkSize", -1L).takeIf { it > 0 },
                notes = json.optString("notes").ifBlank { null },
            )
        }
    }
}

package com.peter.mutter.updater

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {

    @Test
    fun parsesAllFields() {
        val body = """
            {
              "versionCode": 2,
              "versionName": "0.2.0",
              "apkUrl": "https://example.com/app.apk",
              "apkSha256": "abc123",
              "apkSize": 12345,
              "notes": "first update"
            }
        """.trimIndent()
        val m = UpdateManifest.parse(body)
        assertEquals(2, m.versionCode)
        assertEquals("0.2.0", m.versionName)
        assertEquals("https://example.com/app.apk", m.apkUrl)
        assertEquals("abc123", m.apkSha256)
        assertEquals(12345L, m.apkSize)
        assertEquals("first update", m.notes)
    }

    @Test
    fun optionalFieldsBecomeNull() {
        val body = """
            {"versionCode": 5, "versionName": "1.0", "apkUrl": "https://x.com/a.apk"}
        """.trimIndent()
        val m = UpdateManifest.parse(body)
        assertNull(m.apkSha256)
        assertNull(m.apkSize)
        assertNull(m.notes)
    }

    @Test
    fun rejectsNonHttpScheme() {
        val body = """
            {"versionCode": 2, "versionName": "0.2.0", "apkUrl": "ftp://insecure.com/a.apk"}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(body) }
    }

    @Test
    fun rejectsMissingVersionCode() {
        val body = """{"versionName": "1.0", "apkUrl": "https://x.com/a.apk"}"""
        assertThrows(JSONException::class.java) { UpdateManifest.parse(body) }
    }

    @Test
    fun rejectsBlankVersionName() {
        val body = """{"versionCode": 2, "versionName": "", "apkUrl": "https://x.com/a.apk"}"""
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(body) }
    }

    @Test
    fun rejectsZeroVersionCode() {
        val body = """{"versionCode": 0, "versionName": "1.0", "apkUrl": "https://x.com/a.apk"}"""
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(body) }
    }

    @Test
    fun rejectsMalformedJson() {
        assertThrows(JSONException::class.java) { UpdateManifest.parse("not json") }
    }
}

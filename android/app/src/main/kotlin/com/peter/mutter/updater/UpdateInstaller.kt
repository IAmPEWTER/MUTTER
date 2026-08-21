package com.peter.mutter.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class UpdateInstallResult {
    data class Staged(val sessionId: Int) : UpdateInstallResult()
    object NeedsInstallPermission : UpdateInstallResult()
    data class Failure(val message: String) : UpdateInstallResult()
}

class UpdateInstaller(private val context: Context) {

    private val tag = "MutterUpdater"

    suspend fun downloadAndStage(
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): UpdateInstallResult = withContext(Dispatchers.IO) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return@withContext UpdateInstallResult.NeedsInstallPermission
        }
        val cacheDir = File(context.cacheDir, "updater").apply { mkdirs() }
        cacheDir.listFiles()?.forEach { it.delete() }
        val apkFile = File(cacheDir, "mutter-${manifest.versionCode}.apk")
        try {
            download(manifest.apkUrl, apkFile, onProgress)
            manifest.apkSha256?.let { expected ->
                val actual = sha256(apkFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    apkFile.delete()
                    return@withContext UpdateInstallResult.Failure(
                        "sha256 mismatch: expected ${expected.take(12)}…, got ${actual.take(12)}…"
                    )
                }
            }
            manifest.apkSize?.let { expected ->
                if (apkFile.length() != expected) {
                    apkFile.delete()
                    return@withContext UpdateInstallResult.Failure(
                        "size mismatch: expected $expected, got ${apkFile.length()}"
                    )
                }
            }
            val sessionId = openSessionAndCommit(apkFile, manifest.versionCode)
            UpdateInstallResult.Staged(sessionId)
        } catch (t: Throwable) {
            Log.e(tag, "stage failed", t)
            UpdateInstallResult.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UpdateConstants.USER_AGENT)
        }
        val code = conn.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code for $url")
        val total = conn.contentLengthLong
        var downloaded = 0L
        target.outputStream().use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
            }
        }
    }

    private fun openSessionAndCommit(apk: File, versionCode: Int): Int {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_COMPLETE
                putExtra(UpdateInstallReceiver.EXTRA_VERSION_CODE, versionCode)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Log.i(tag, "committed session $sessionId for v$versionCode")
        return sessionId
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

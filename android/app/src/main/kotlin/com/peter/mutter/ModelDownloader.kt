package com.peter.mutter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches the files named by [SttModel] into filesDir/models/<dir>/.
 *
 * Every download is SHA-256 verified against the canonical k2-fsa release
 * before it is put in place. Size alone used to be the only check, which a
 * truncated resume or a substituted mirror can satisfy — and a model file that
 * is wrong but plausible fails at load time as an opaque native error.
 */
class ModelDownloader(private val context: Context) {

    private val tag = "MutterDL"

    fun modelDir(): File {
        val dir = File(context.filesDir, "models/${SttModel.DIR}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun vadModelPath(): String = File(modelDir(), SttModel.VAD).absolutePath

    /**
     * Cheap startup check: names and sizes. Hashing 620 MB on every service
     * connect would cost seconds; the hash is enforced where a bad file can
     * first appear, at download time.
     */
    fun isPresent(): Boolean {
        val dir = modelDir()
        return SttModel.ASSETS.all { File(dir, it.filename).length() == it.size }
    }

    /**
     * Delete cached models that are no longer the active one. An app update
     * that changes models would otherwise leave the old weights on the phone
     * forever — hundreds of megabytes nothing will ever open again.
     */
    fun pruneOtherModels() {
        val root = File(context.filesDir, "models")
        val stale = root.listFiles()?.filter { it.isDirectory && it.name != SttModel.DIR } ?: return
        for (dir in stale) {
            val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) {
                Log.i(tag, "removed stale model ${dir.name} (${freed / 1_000_000} MB)")
            } else {
                Log.w(tag, "could not remove stale model ${dir.name}")
            }
        }
    }

    suspend fun download(
        onProgress: (downloaded: Long, total: Long, file: String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = modelDir()
        val total = SttModel.TOTAL_BYTES
        var done = 0L
        try {
            for (asset in SttModel.ASSETS) {
                val target = File(dir, asset.filename)
                if (target.length() == asset.size) {
                    done += asset.size
                    onProgress(done, total, asset.filename)
                    continue
                }
                val partial = File(dir, "${asset.filename}.part")
                fetch(asset, partial, done, total, onProgress)
                val digest = sha256(partial)
                if (digest != asset.sha256) {
                    partial.delete() // a resume onto this would inherit the corruption
                    return@withContext Result.failure(
                        RuntimeException("${asset.filename}: checksum mismatch (got $digest)")
                    )
                }
                if (target.exists() && !target.delete()) {
                    return@withContext Result.failure(
                        RuntimeException("could not remove old ${asset.filename}")
                    )
                }
                if (!partial.renameTo(target)) {
                    return@withContext Result.failure(
                        RuntimeException("rename failed for ${asset.filename}")
                    )
                }
                done += asset.size
            }
            pruneOtherModels()
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(tag, "download failed", t)
            Result.failure(t)
        }
    }

    private fun fetch(
        asset: SttModel.Asset,
        partial: File,
        alreadyDone: Long,
        total: Long,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val resumeFrom = if (partial.exists()) partial.length() else 0L
        val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code for ${asset.url}")
        // A server that ignores Range answers 200 with the whole file; appending
        // that onto the partial would produce a size-correct, corrupt file.
        val append = resumeFrom > 0 && code == HttpURLConnection.HTTP_PARTIAL
        var soFar = if (append) resumeFrom else 0L
        FileOutputStream(partial, append).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    soFar += n
                    onProgress(alreadyDone + soFar, total, asset.filename)
                }
            }
        }
    }

    fun sha256(file: File): String {
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

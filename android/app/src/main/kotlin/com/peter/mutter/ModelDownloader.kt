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

class ModelDownloader(private val context: Context) {

    private val tag = "MutterDL"

    data class Asset(
        val url: String,
        val filename: String,
        val expectedSize: Long,
    )

    private val assets = listOf(
        Asset(
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-distil-small.en/resolve/main/distil-small.en-encoder.int8.onnx",
            filename = WhisperEngine.ENCODER,
            expectedSize = 102_961_431L,
        ),
        Asset(
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-distil-small.en/resolve/main/distil-small.en-decoder.int8.onnx",
            filename = WhisperEngine.DECODER,
            expectedSize = 195_079_097L,
        ),
        Asset(
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-distil-small.en/resolve/main/distil-small.en-tokens.txt",
            filename = WhisperEngine.TOKENS,
            expectedSize = 835_554L,
        ),
    )

    fun modelDir(): File {
        val dir = File(context.filesDir, "models/distil-small.en")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isPresent(): Boolean {
        val dir = modelDir()
        return assets.all {
            val f = File(dir, it.filename)
            f.exists() && f.length() == it.expectedSize
        }
    }

    suspend fun download(onProgress: (downloaded: Long, total: Long, file: String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val dir = modelDir()
            val totalExpected = assets.sumOf { it.expectedSize }
            var totalDownloaded = 0L
            try {
                for (asset in assets) {
                    val target = File(dir, asset.filename)
                    if (target.exists() && asset.expectedSize > 0 && target.length() == asset.expectedSize) {
                        totalDownloaded += target.length()
                        onProgress(totalDownloaded, totalExpected, asset.filename)
                        continue
                    }
                    val partial = File(dir, "${asset.filename}.part")
                    val resumeFrom = if (partial.exists()) partial.length() else 0L
                    val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        return@withContext Result.failure(
                            RuntimeException("HTTP $code for ${asset.url}")
                        )
                    }
                    FileOutputStream(partial, resumeFrom > 0).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var soFar = resumeFrom
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                soFar += n
                                totalDownloaded += n
                                onProgress(totalDownloaded, totalExpected, asset.filename)
                            }
                        }
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
                }
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(tag, "download failed", t)
                Result.failure(t)
            }
        }

    @Suppress("unused")
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

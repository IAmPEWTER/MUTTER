package com.peter.mutter

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches [SttModel.PREFERRED] into filesDir/models/<dir>/ and reports which
 * model the phone can actually load right now.
 *
 * Every download is SHA-256 verified against the canonical k2-fsa release
 * before it is put in place. Size alone used to be the only check, which a
 * truncated resume or a substituted mirror can satisfy — and a model file that
 * is wrong but plausible fails at load time as an opaque native error.
 *
 * Nothing here deletes a model the app could still use. That is not a policy,
 * it is enforced by [pruneSupersededModels] refusing to run until the preferred
 * model is verified present.
 */
class ModelDownloader(private val context: Context) {

    private val tag = "MutterDL"

    private fun root(): File = File(context.filesDir, "models")

    fun dirFor(spec: SttModel.Spec): File =
        File(root(), spec.dir).apply { if (!exists()) mkdirs() }

    /** Where the preferred model lives — the download target. */
    fun modelDir(): File = dirFor(SttModel.PREFERRED)

    /**
     * The best model this phone can load right now, or null if it has none.
     *
     * Only the recognizer files are required: a missing VAD costs segmentation
     * quality, not dictation, and [VadSegmenter] already degrades to RMS. A
     * model that can transcribe should never be reported as absent.
     */
    fun resolve(): SttModel.Spec? = SttModel.KNOWN.firstOrNull { spec ->
        val dir = File(root(), spec.dir)
        spec.recognizerAssets.all { File(dir, it.filename).length() == it.size }
    }

    fun vadModelPath(): String {
        val spec = resolve() ?: SttModel.PREFERRED
        return File(dirFor(spec), SttModel.VAD).absolutePath
    }

    /**
     * Cheap startup check: names and sizes for the *preferred* model. Hashing
     * 620 MB on every service connect would cost seconds; the hash is enforced
     * where a bad file can first appear, at download time.
     */
    fun isPresent(): Boolean {
        val dir = File(root(), SttModel.PREFERRED.dir)
        return SttModel.PREFERRED.assets.all { File(dir, it.filename).length() == it.size }
    }

    /** True when the phone has something to dictate with, preferred or not. */
    fun hasAnyModel(): Boolean = resolve() != null

    /** Bytes recoverable by dropping models that are no longer the active one. */
    fun reclaimableBytes(): Long =
        supersededDirs().sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    private fun supersededDirs(): List<File> =
        root().listFiles()?.filter { it.isDirectory && it.name != SttModel.PREFERRED.dir }.orEmpty()

    /**
     * Delete the weights of models the app has moved on from.
     *
     * Refuses to run unless the preferred model is verified present, which is
     * the whole safety property: v0.7.0 pruned on service connect regardless
     * and left installs with no model at all. An update may cost storage until
     * its download finishes; it may never cost dictation.
     */
    fun pruneSupersededModels() {
        val complete = isPresent()
        val candidates = supersededDirs()
        val allowed = ModelPolicy.prunable(
            dirNames = candidates.map { it.name },
            preferredDir = SttModel.PREFERRED.dir,
            preferredComplete = complete,
        ).toSet()
        if (allowed.isEmpty()) {
            Log.i(tag, "nothing safe to prune (${SttModel.PREFERRED.dir} complete: $complete)")
            return
        }
        for (dir in candidates.filter { it.name in allowed }) {
            val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) {
                Log.i(tag, "removed superseded model ${dir.name} (${freed / 1_000_000} MB)")
            } else {
                Log.w(tag, "could not remove superseded model ${dir.name}")
            }
        }
    }

    /**
     * Serialized across every caller: the setup wizard and [ModelBootstrap] can
     * both decide the model is missing at the same moment, and two writers on
     * one .part file produce a size-correct, corrupt download.
     */
    suspend fun download(
        onProgress: (downloaded: Long, total: Long, file: String) -> Unit,
    ): Result<Unit> = gate.withLock {
        withContext(Dispatchers.IO) { fetchPreferred(onProgress) }
    }

    private fun fetchPreferred(
        onProgress: (downloaded: Long, total: Long, file: String) -> Unit,
    ): Result<Unit> {
        val spec = SttModel.PREFERRED
        val dir = dirFor(spec)
        val total = spec.totalBytes
        val needed = spec.assets.filterNot { File(dir, it.filename).length() == it.size }
            .sumOf { it.size }
        val free = allocatableBytes(dir)
        if (free < needed + HEADROOM_BYTES) {
            // Deliberately not solved by deleting the old model first: that
            // trades a storage problem the user can fix for an app that cannot
            // dictate. Say what is needed and keep what works.
            val reclaimable = reclaimableBytes()
            val hint = if (reclaimable > 0) {
                " (${reclaimable / 1_000_000} MB frees up once this finishes)"
            } else {
                ""
            }
            return Result.failure(
                RuntimeException(
                    "needs ${(needed + HEADROOM_BYTES) / 1_000_000} MB free, " +
                        "phone has ${free / 1_000_000} MB$hint"
                )
            )
        }
        var done = 0L
        return try {
            for (asset in spec.assets) {
                val target = File(dir, asset.filename)
                if (target.length() == asset.size) {
                    File(dir, "${asset.filename}.part").delete() // abandoned resume
                    done += asset.size
                    onProgress(done, total, asset.filename)
                    continue
                }
                val partial = File(dir, "${asset.filename}.part")
                fetch(asset, partial, done, total, onProgress)
                val digest = sha256(partial)
                if (digest != asset.sha256) {
                    partial.delete() // a resume onto this would inherit the corruption
                    return Result.failure(
                        RuntimeException("${asset.filename}: checksum mismatch (got $digest)")
                    )
                }
                if (target.exists() && !target.delete()) {
                    return Result.failure(
                        RuntimeException("could not remove old ${asset.filename}")
                    )
                }
                if (!partial.renameTo(target)) {
                    return Result.failure(
                        RuntimeException("rename failed for ${asset.filename}")
                    )
                }
                done += asset.size
            }
            // Only now, with the replacement verified on disk, is the old one
            // safe to lose.
            pruneSupersededModels()
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(tag, "download failed", t)
            Result.failure(t)
        }
    }

    /**
     * Space available for a large write. Not [File.usableSpace]: the system
     * will evict other apps' caches to satisfy an allocation, and on a phone
     * near full that is often the difference between this working and refusing
     * to start.
     */
    private fun allocatableBytes(dir: File): Long = try {
        val sm = context.getSystemService(StorageManager::class.java)
        sm.getAllocatableBytes(sm.getUuidForPath(dir))
    } catch (t: Throwable) {
        Log.d(tag, "getAllocatableBytes unavailable", t)
        dir.usableSpace
    }

    private companion object {
        // Android starts misbehaving well before a volume is truly full.
        const val HEADROOM_BYTES = 200_000_000L
        val gate = Mutex()
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

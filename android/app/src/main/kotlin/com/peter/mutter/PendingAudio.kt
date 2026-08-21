package com.peter.mutter

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Last-resort persistence: when transcription fails (engine won't load,
 * native error), the chunk is written here as a 16 kHz mono WAV so the
 * dictation is recoverable instead of silently lost. Files land in
 * filesDir/pending/ and the user is told via notification.
 *
 * Nothing ever deleted these. A failure that repeats — a corrupt model, a
 * device that keeps refusing the mic — writes a WAV per chunk forever, so the
 * directory is bounded by [prune] on every save: newest [KEEP_FILES] kept,
 * anything past [MAX_AGE_MS] dropped regardless.
 */
object PendingAudio {

    private const val TAG = "MutterPending"

    fun save(context: Context, samples: FloatArray, sampleRate: Int): File? = try {
        val dir = File(context.filesDir, "pending").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.wav")
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            pcm.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        FileOutputStream(file).use { out ->
            out.write(wavHeader(samples.size * 2, sampleRate))
            out.write(pcm.array())
        }
        Log.i(TAG, "saved ${file.name} (${samples.size / sampleRate}s)")
        prune(dir, System.currentTimeMillis())
        file
    } catch (t: Throwable) {
        Log.e(TAG, "failed to persist audio", t)
        null
    }

    /** Bound the directory. Public for [MutterAccessibilityService] at startup. */
    fun prune(context: Context) = prune(File(context.filesDir, "pending"), System.currentTimeMillis())

    private fun prune(dir: File, nowMillis: Long) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: return
        for (f in selectForDeletion(files, nowMillis)) {
            if (!f.delete()) Log.w(TAG, "could not delete ${f.name}")
        }
    }

    /** Pure so it is unit-testable: which of [files] should go. */
    fun selectForDeletion(
        files: List<File>,
        nowMillis: Long,
        keep: Int = KEEP_FILES,
        maxAgeMs: Long = MAX_AGE_MS,
    ): List<File> {
        val newestFirst = files.sortedByDescending { it.lastModified() }
        return newestFirst.filterIndexed { i, f ->
            i >= keep || nowMillis - f.lastModified() > maxAgeMs
        }
    }

    private const val KEEP_FILES = 20
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private fun wavHeader(dataLen: Int, sampleRate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + dataLen)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)             // PCM fmt chunk size
        h.putShort(1)            // PCM
        h.putShort(1)            // mono
        h.putInt(sampleRate)
        h.putInt(sampleRate * 2) // byte rate
        h.putShort(2)            // block align
        h.putShort(16)           // bits per sample
        h.put("data".toByteArray())
        h.putInt(dataLen)
        return h.array()
    }
}

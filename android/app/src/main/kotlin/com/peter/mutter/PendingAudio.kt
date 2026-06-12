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
        file
    } catch (t: Throwable) {
        Log.e(TAG, "failed to persist audio", t)
        null
    }

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

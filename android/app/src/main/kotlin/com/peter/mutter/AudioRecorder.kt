package com.peter.mutter

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures the mic in fixed [windowSize]-sample windows and hands each one to
 * [onWindow] on the capture thread. No length cap — a hold can run as long as
 * the user talks; segmentation downstream keeps Whisper-sized chunks. VAD is
 * ~1000x faster than realtime, so running it inside [onWindow] never starves
 * the read loop.
 */
class AudioRecorder(
    private val sampleRate: Int = 16_000,
    private val windowSize: Int = 512,
    private val onWindow: (FloatArray) -> Unit,
) {
    private val tag = "MutterAudio"
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return false
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(tag, "AudioRecord.getMinBufferSize returned $minBuf")
            return false
        }
        // Headroom so brief per-window work can't overrun the OS capture buffer.
        val bufSize = maxOf(minBuf, windowSize * 2 * 8)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (t: Throwable) {
            Log.e(tag, "AudioRecord ctor failed", t)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(tag, "AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(tag, "startRecording failed", t)
            rec.release()
            return false
        }
        record = rec
        running.set(true)

        worker = thread(name = "MutterAudioWorker", isDaemon = true) {
            val shortBuf = ShortArray(windowSize)
            while (running.get()) {
                val read = try {
                    rec.read(shortBuf, 0, windowSize)
                } catch (t: Throwable) {
                    Log.e(tag, "read failed", t)
                    break
                }
                if (read <= 0) {
                    if (read < 0) break // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE
                    continue
                }
                val floats = FloatArray(read)
                for (i in 0 until read) floats[i] = shortBuf[i] / 32768f
                try {
                    onWindow(floats)
                } catch (t: Throwable) {
                    Log.e(tag, "onWindow handler threw", t)
                }
            }
        }
        return true
    }

    fun stop() {
        running.set(false)
        worker?.join(1_000)
        worker = null
        val rec = record
        record = null
        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }
}

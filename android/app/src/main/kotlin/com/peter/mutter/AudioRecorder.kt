package com.peter.mutter

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val blockMs: Int = 50,
    private val maxSeconds: Int = 60,
) {
    private val tag = "MutterAudio"
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val collected = ArrayList<FloatArray>()
    private var capturedSamples = 0
    private val capacityCap = sampleRate * maxSeconds

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
        val blockSamples = sampleRate * blockMs / 1000
        val bufSize = maxOf(minBuf, blockSamples * 4)

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
        synchronized(collected) {
            collected.clear()
            capturedSamples = 0
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
            val shortBuf = ShortArray(blockSamples)
            while (running.get()) {
                val read = try {
                    rec.read(shortBuf, 0, shortBuf.size)
                } catch (t: Throwable) {
                    Log.e(tag, "read failed", t)
                    break
                }
                if (read <= 0) {
                    // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE — quit loop.
                    if (read < 0) break
                    continue
                }
                val floats = FloatArray(read)
                for (i in 0 until read) floats[i] = shortBuf[i] / 32768f
                synchronized(collected) {
                    if (capturedSamples + read <= capacityCap) {
                        collected.add(floats)
                        capturedSamples += read
                    } else {
                        running.set(false)
                    }
                }
            }
        }
        return true
    }

    fun stop(): FloatArray {
        if (!running.compareAndSet(true, false)) {
            // already stopped; still try to return whatever was captured
        }
        worker?.join(500)
        worker = null
        val rec = record
        record = null
        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
        return concat()
    }

    fun isRecording(): Boolean = running.get()

    fun capturedSeconds(): Float = synchronized(collected) {
        capturedSamples.toFloat() / sampleRate
    }

    private fun concat(): FloatArray = synchronized(collected) {
        val total = capturedSamples
        if (total == 0) return@synchronized FloatArray(0)
        val out = FloatArray(total)
        var pos = 0
        for (chunk in collected) {
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        collected.clear()
        capturedSamples = 0
        out
    }
}

package com.peter.mutter

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures the mic in fixed [windowSize]-sample windows and hands each one to
 * the `onWindow` callback on the capture thread. No length cap — a hold can run
 * as long as the user talks; segmentation downstream keeps model-sized chunks.
 * VAD is ~1000x faster than realtime, so running it inside `onWindow` never
 * starves the read loop.
 *
 * One instance lives for the life of the service and is reused across holds.
 * [prepare] builds the AudioRecord — that is where the audio HAL opens the
 * input — so [start] at key-down only has to leave standby. Building one per
 * hold put the whole HAL open on the critical path.
 *
 * Head-of-utterance instrumentation: [start] logs how long the first non-silent
 * window took, and if the head is all silence it asks the framework whether we
 * were silenced ([AudioRecord.getActiveRecordingConfiguration]). A background
 * app is handed zeros rather than an error, so without this the failure is
 * invisible. See [MutterAccessibilityService.handleDown] for the ordering that
 * keeps us out of that state.
 */
class AudioRecorder(
    private val sampleRate: Int = 16_000,
    private val windowSize: Int = 512,
) {
    private val tag = "MutterAudio"
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var source: Int = -1

    /**
     * Build the AudioRecord without starting it. Idempotent and cheap once
     * warm; call it whenever a hold ends so the next one starts hot.
     */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun prepare(): Boolean {
        if (record != null) return true
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

        // VOICE_RECOGNITION is the ASR source: no AGC and no call-tuned noise
        // suppressor, which is what the model wants. Some devices refuse it —
        // fall back to MIC rather than lose dictation.
        for (src in SOURCES) {
            val rec = try {
                AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (t: Throwable) {
                Log.w(tag, "AudioRecord ctor failed for source $src", t)
                null
            } ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(tag, "source $src not initialized (state=${rec.state})")
                try { rec.release() } catch (_: Throwable) {}
                continue
            }
            record = rec
            source = src
            return true
        }
        Log.e(tag, "no usable audio source")
        return false
    }

    /** Begin capture. The caller must already be in a foreground state. */
    @Synchronized
    fun start(onWindow: (FloatArray) -> Unit): Boolean {
        if (running.get()) return false
        if (!prepare()) return false
        val rec = record ?: return false
        val t0 = SystemClock.elapsedRealtime()
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(tag, "startRecording failed", t)
            releaseLocked()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(tag, "recordingState=${rec.recordingState} after startRecording")
            releaseLocked()
            return false
        }
        running.set(true)
        worker = thread(name = "MutterAudioWorker", isDaemon = true) { loop(rec, t0, onWindow) }
        return true
    }

    private fun loop(rec: AudioRecord, t0: Long, onWindow: (FloatArray) -> Unit) {
        val shortBuf = ShortArray(windowSize)
        var sawAudio = false
        var checkedSilenced = false
        var silentMs = 0
        val windowMs = windowSize * 1000 / sampleRate
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
            var peak = 0
            for (i in 0 until read) {
                val s = shortBuf[i].toInt()
                val mag = if (s < 0) -s else s
                if (mag > peak) peak = mag
                floats[i] = s / 32768f
            }
            if (!sawAudio) {
                if (peak > SILENT_PEAK) {
                    sawAudio = true
                    Log.i(tag, "head: first audio at ${SystemClock.elapsedRealtime() - t0} ms (source=$source)")
                } else {
                    silentMs += windowMs
                    // Zeros are also what a silenced client reads, so ask.
                    if (!checkedSilenced && silentMs >= SILENCE_PROBE_MS) {
                        checkedSilenced = true
                        val silenced = try {
                            rec.activeRecordingConfiguration?.isClientSilenced
                        } catch (t: Throwable) {
                            Log.d(tag, "recording config unavailable", t); null
                        }
                        if (silenced == true) {
                            Log.e(tag, "head: SILENCED by the framework — not in a foreground state")
                        }
                    }
                }
            }
            try {
                onWindow(floats)
            } catch (t: Throwable) {
                Log.e(tag, "onWindow handler threw", t)
            }
        }
    }

    /** End capture but keep the HAL input open for the next hold. */
    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.join(1_000)
        worker = null
        val rec = record ?: return
        try {
            rec.stop()
        } catch (t: Throwable) {
            // A stop that throws leaves the track in an unknown state; drop it
            // so the next prepare() builds a clean one.
            Log.e(tag, "stop failed — rebuilding on next use", t)
            releaseLocked()
        }
    }

    /** Free the HAL input. Capture must already be stopped. */
    @Synchronized
    fun release() {
        running.set(false)
        worker?.join(1_000)
        worker = null
        releaseLocked()
    }

    private fun releaseLocked() {
        val rec = record ?: return
        record = null
        source = -1
        try { rec.stop() } catch (_: Throwable) {}
        try { rec.release() } catch (_: Throwable) {}
    }

    private companion object {
        val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        // int16 magnitude below this is indistinguishable from a muted stream.
        const val SILENT_PEAK = 8
        const val SILENCE_PROBE_MS = 200
    }
}

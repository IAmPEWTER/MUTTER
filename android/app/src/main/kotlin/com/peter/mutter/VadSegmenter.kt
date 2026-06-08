package com.peter.mutter

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Streams 512-sample audio windows through Silero VAD, runs [AdaptiveEndpointer]
 * on the per-window speech decision, and emits each completed chunk via
 * [onChunk] the instant a cut is found — so transcription can start while the
 * user is still talking.
 *
 * Buffering is ours, not sherpa's: [Vad.compute] only scores a window and
 * advances the model state, it never queues audio, so memory stays flat.
 *
 * Robustness: if the VAD model is missing or a window is the wrong size, every
 * window counts as speech and the endpointer's 25s emergency cut still bounds
 * chunk length — degraded (no pause-splitting) but never a failure.
 */
class VadSegmenter(
    private val modelPath: String,
    private val onChunk: (FloatArray) -> Unit,
    private val sampleRate: Int = 16_000,
    private val windowSize: Int = 512,
    private val threshold: Float = 0.5f,
) {
    private val tag = "MutterVad"
    private var vad: Vad? = null
    private val endpointer = AdaptiveEndpointer(windowMs = windowSize * 1000 / sampleRate)
    private val buffer = ArrayList<FloatArray>()
    private var chunkSamples = 0
    private var chunkHasSpeech = false

    @Synchronized
    fun load(): Boolean {
        if (vad != null) return true
        if (!File(modelPath).exists()) {
            Log.e(tag, "vad model missing at $modelPath")
            return false
        }
        return try {
            val cfg = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = threshold,
                    minSilenceDuration = 0.1f, // unused: endpointing is ours, not sherpa's
                    minSpeechDuration = 0.1f,
                    windowSize = windowSize,
                    maxSpeechDuration = 30f,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
            )
            // null AssetManager -> load from file path (see Vad bytecode).
            vad = Vad(config = cfg)
            true
        } catch (t: Throwable) {
            Log.e(tag, "vad load failed", t)
            vad = null
            false
        }
    }

    /** Feed one audio window (called continuously on the capture thread). */
    @Synchronized
    fun feed(window: FloatArray) {
        if (window.isEmpty()) return
        val v = vad
        val isSpeech = if (v != null && window.size == windowSize) {
            try {
                v.compute(window) >= threshold
            } catch (t: Throwable) {
                Log.e(tag, "vad compute failed", t)
                true // fall back to speech so the emergency cut still bounds length
            }
        } else {
            true
        }
        buffer.add(window)
        chunkSamples += window.size
        if (isSpeech) chunkHasSpeech = true
        if (endpointer.feed(isSpeech)) emitChunk()
    }

    /** End of hold: emit the final partial chunk (no minimum), then reset. */
    @Synchronized
    fun flush() {
        emitChunk()
        reset()
    }

    /** Start of hold: drop any leftover audio and zero the VAD state. */
    @Synchronized
    fun reset() {
        buffer.clear()
        chunkSamples = 0
        chunkHasSpeech = false
        endpointer.reset()
        try { vad?.reset() } catch (_: Throwable) {}
    }

    @Synchronized
    fun release() {
        try { vad?.release() } catch (_: Throwable) {}
        vad = null
        buffer.clear()
        chunkSamples = 0
        chunkHasSpeech = false
    }

    private fun emitChunk() {
        if (chunkHasSpeech && chunkSamples > 0) {
            val out = FloatArray(chunkSamples)
            var pos = 0
            for (w in buffer) {
                System.arraycopy(w, 0, out, pos, w.size)
                pos += w.size
            }
            onChunk(out)
        }
        buffer.clear()
        chunkSamples = 0
        chunkHasSpeech = false
    }
}

package com.peter.mutter

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx offline recognizer over the model named by [SttModel].
 *
 * A transducer, not an encoder-decoder: it consumes exactly the audio it is
 * given. Whisper's encoder is a fixed 30 s window, so a 4 s chunk cost the same
 * as a 30 s one, and its decoder could lock into a repeat loop on marginal
 * audio. Neither applies here.
 */
class SttEngine(private val modelDir: File) {

    private val tag = "MutterStt"
    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun load(): Boolean {
        if (recognizer != null) return true
        val encoder = File(modelDir, SttModel.ENCODER)
        val decoder = File(modelDir, SttModel.DECODER)
        val joiner = File(modelDir, SttModel.JOINER)
        val tokens = File(modelDir, SttModel.TOKENS)
        val missing = listOf(encoder, decoder, joiner, tokens).filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            Log.e(tag, "model files missing: ${missing.joinToString { it.name }}")
            return false
        }
        return try {
            val modelCfg = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = SttModel.NUM_THREADS,
                debug = false,
                modelType = SttModel.MODEL_TYPE,
            )
            recognizer = OfflineRecognizer(
                null,
                OfflineRecognizerConfig(modelConfig = modelCfg, decodingMethod = "greedy_search"),
            )
            // Pre-warm so the first real transcribe is hot.
            try {
                transcribe(FloatArray(1600), 16000) // 0.1 s @ 16 kHz
            } catch (t: Throwable) {
                Log.d(tag, "warmup failed (non-fatal)", t)
            }
            true
        } catch (t: Throwable) {
            Log.e(tag, "OfflineRecognizer init failed", t)
            recognizer = null
            false
        }
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
    }

    @Synchronized
    fun isLoaded(): Boolean = recognizer != null

    /**
     * Returns the transcript, "" when the engine legitimately heard nothing,
     * or null when transcription FAILED (not loaded / native error) — callers
     * must persist the audio on null so speech is never silently lost.
     *
     * Synchronized so release()/load() (daily recycle, unbind) can never free
     * the native recognizer mid-decode — that was a native-crash window.
     */
    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        val rec = recognizer ?: return null
        if (samples.isEmpty()) return ""
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = sampleRate)
            rec.decode(stream)
            rec.getResult(stream).text ?: ""
        } catch (t: Throwable) {
            Log.e(tag, "transcribe failed", t)
            null
        } finally {
            try { stream.release() } catch (_: Throwable) {}
        }
    }
}

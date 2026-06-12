package com.peter.mutter

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

class WhisperEngine(private val modelDir: File) {

    private val tag = "MutterWhisper"
    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun load(): Boolean {
        if (recognizer != null) return true
        val encoder = File(modelDir, ENCODER).absolutePath
        val decoder = File(modelDir, DECODER).absolutePath
        val tokens = File(modelDir, TOKENS).absolutePath
        if (!File(encoder).exists() || !File(decoder).exists() || !File(tokens).exists()) {
            Log.e(tag, "model files missing under ${modelDir.absolutePath}")
            return false
        }
        return try {
            val whisper = OfflineWhisperModelConfig(
                encoder = encoder,
                decoder = decoder,
                language = "en",
                task = "transcribe",
            )
            val modelCfg = OfflineModelConfig(
                whisper = whisper,
                tokens = tokens,
                numThreads = 4,
                debug = false,
                modelType = "whisper",
            )
            val cfg = OfflineRecognizerConfig(
                modelConfig = modelCfg,
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(null, cfg)
            // Pre-warm with a small zero buffer so first real transcribe is hot.
            try {
                val warmup = FloatArray(1600) // 0.1 s @ 16 kHz
                transcribe(warmup, 16000)
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

    companion object {
        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.int8.onnx"
        const val TOKENS = "tokens.txt"
    }
}

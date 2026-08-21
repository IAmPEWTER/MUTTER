package com.peter.mutter

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * sherpa-onnx offline recognizer over whichever model the phone actually has.
 *
 * The model is resolved at [load] time rather than at construction, so a
 * download that finishes mid-session is picked up by a plain reload, and an
 * install still carrying the previous model dictates with that instead of
 * failing. [SttModel.KNOWN] is the preference order.
 */
class SttEngine(private val downloader: ModelDownloader) {

    private val tag = "MutterStt"
    private var recognizer: OfflineRecognizer? = null
    @Volatile private var loaded: SttModel.Spec? = null

    /** Which model is behind the current recognizer, or null if none is up. */
    fun loadedSpec(): SttModel.Spec? = loaded

    @Synchronized
    fun load(): Boolean {
        if (recognizer != null) return true
        val spec = downloader.resolve()
        if (spec == null) {
            Log.e(tag, "no usable model on this device")
            return false
        }
        val dir = downloader.dirFor(spec)
        val tokens = File(dir, SttModel.TOKENS)
        return try {
            val modelCfg = when (spec.family) {
                SttModel.Family.TRANSDUCER -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, SttModel.ENCODER).absolutePath,
                        decoder = File(dir, SttModel.DECODER).absolutePath,
                        joiner = File(dir, SttModel.JOINER).absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = SttModel.NUM_THREADS,
                    debug = false,
                    modelType = spec.modelType,
                )
                SttModel.Family.WHISPER -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, SttModel.ENCODER).absolutePath,
                        decoder = File(dir, SttModel.DECODER).absolutePath,
                        language = "en",
                        task = "transcribe",
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = SttModel.NUM_THREADS,
                    debug = false,
                    modelType = spec.modelType,
                )
            }
            recognizer = OfflineRecognizer(
                null,
                OfflineRecognizerConfig(modelConfig = modelCfg, decodingMethod = "greedy_search"),
            )
            loaded = spec
            Log.i(tag, "loaded ${spec.dir}")
            // Pre-warm so the first real transcribe is hot.
            try {
                transcribe(FloatArray(1600), 16000) // 0.1 s @ 16 kHz
            } catch (t: Throwable) {
                Log.d(tag, "warmup failed (non-fatal)", t)
            }
            true
        } catch (t: Throwable) {
            Log.e(tag, "OfflineRecognizer init failed for ${spec.dir}", t)
            recognizer = null
            loaded = null
            false
        }
    }

    @Synchronized
    fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        loaded = null
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

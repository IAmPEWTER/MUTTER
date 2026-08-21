package com.peter.mutter

/**
 * Every speech model the app can load, preferred first.
 *
 * There is more than one entry so that changing models can never dark the app.
 * v0.7.0 shipped a new [PREFERRED], deleted the old weights on first launch and
 * had nothing left to fall back to, so dictation stopped until a 620 MB
 * download the user was never told was mandatory. Now an install keeps
 * dictating with whatever it already has while the replacement arrives.
 *
 * Sizes and hashes are the canonical k2-fsa release artifact's. URLs point at
 * the author's HuggingFace copy because it publishes files individually — the
 * GitHub release ships one .tar.bz2 and Android has no bzip2 decoder. The
 * canonical hash is what makes that indirection safe.
 */
object SttModel {

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val VAD = "silero_vad.onnx"

    /** SD8G2 is 1+4 big cores; more threads than this buys nothing here. */
    const val NUM_THREADS = 4

    /** Which sherpa-onnx config shape the files plug into. */
    enum class Family { TRANSDUCER, WHISPER }

    data class Asset(
        val url: String,
        val filename: String,
        val size: Long,
        /** null → loadable but not fetchable. See [Spec.downloadable]. */
        val sha256: String? = null,
    )

    data class Spec(
        val dir: String,
        val family: Family,
        val modelType: String,
        val assets: List<Asset>,
    ) {
        val totalBytes: Long get() = assets.sumOf { it.size }

        /**
         * Everything the recognizer itself needs. Excludes the VAD, whose
         * absence degrades segmentation but never stops dictation — so it must
         * not make a usable model look missing.
         */
        val recognizerAssets: List<Asset> get() = assets.filterNot { it.filename == VAD }

        /**
         * Only a fully hashed spec may be fetched. A model whose hashes were
         * never recorded can still be *loaded* off a phone that already has it,
         * which is all a fallback needs to do.
         */
        val downloadable: Boolean get() = assets.all { it.sha256 != null }
    }

    private const val PARAKEET_HF =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
    private const val WHISPER_HF =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-distil-small.en/resolve/main"
    private const val GH =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /** Shared by every spec — the segmenter's model, not the recognizer's. */
    private val VAD_ASSET = Asset(
        "$GH/$VAD", VAD, 643_854L,
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    )

    /** NeMo Parakeet TDT 0.6B v2, int8. Measured the most accurate that fits. */
    val PARAKEET = Spec(
        dir = "parakeet-tdt-0.6b-v2-int8",
        family = Family.TRANSDUCER,
        modelType = "nemo_transducer",
        assets = listOf(
            Asset("$PARAKEET_HF/$ENCODER", ENCODER, 652_184_296L,
                "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            Asset("$PARAKEET_HF/$DECODER", DECODER, 7_257_753L,
                "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            Asset("$PARAKEET_HF/$JOINER", JOINER, 1_739_080L,
                "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            Asset("$PARAKEET_HF/$TOKENS", TOKENS, 9_384L,
                "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            VAD_ASSET,
        ),
    )

    /** What shipped through v0.6.0 — the last build known to dictate. */
    val DISTIL_SMALL_EN = Spec(
        dir = "distil-small.en",
        family = Family.WHISPER,
        modelType = "whisper",
        assets = listOf(
            Asset("$WHISPER_HF/distil-small.en-encoder.int8.onnx", ENCODER, 102_961_431L,
                "397a76d2308c2c1ec91a4ecc12f20fede69bb17be41a1cef050993520328beca"),
            Asset("$WHISPER_HF/distil-small.en-decoder.int8.onnx", DECODER, 195_079_097L,
                "3074092bca078786ecda9c9e88449f14e9ebde1d60be4d41de8cacda55e065e0"),
            Asset("$WHISPER_HF/distil-small.en-tokens.txt", TOKENS, 835_554L,
                "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
            VAD_ASSET,
        ),
    )

    /**
     * What a fresh install fetches.
     *
     * Back to v0.6.0's model. Parakeet is the more accurate engine and stays
     * loadable, but it was swapped in during the same session that broke
     * dictation, and it is the larger variable: 620 MB to fetch against 291 MB,
     * and ~740 MB resident against ~660 MB. Until a phone that failed is seen
     * to dictate again, the model is the known-good one.
     */
    val PREFERRED = DISTIL_SMALL_EN

    /** Preferred first — [ModelDownloader.resolve] loads the first one present. */
    val KNOWN = listOf(DISTIL_SMALL_EN, PARAKEET)

    /** The preferred model's assets. Kept for call sites that only ever fetch. */
    val ASSETS = PREFERRED.assets
    val TOTAL_BYTES: Long = PREFERRED.totalBytes
}

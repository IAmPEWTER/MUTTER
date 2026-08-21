package com.peter.mutter

/**
 * The on-device speech model, in one place, so [ModelDownloader] and
 * [SttEngine] cannot disagree about which files should exist.
 *
 * Sizes and hashes are of the canonical k2-fsa release artifact. The URLs point
 * at the author's HuggingFace copy because that publishes the files
 * individually — the GitHub release ships one .tar.bz2, and Android has no
 * bzip2 decoder in the platform. Verifying the canonical hash is what makes
 * that indirection safe: a substituted file cannot pass.
 *
 * To swap models: change [DIR] and [ASSETS]. [ModelDownloader.pruneOtherModels]
 * removes whatever the previous one left behind.
 */
object SttModel {

    /** Cache directory under filesDir/models/. Changing it forces a re-download. */
    const val DIR = "parakeet-tdt-0.6b-v2-int8"

    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val VAD = "silero_vad.onnx"

    /** sherpa-onnx model family: selects feature normalisation and decoding. */
    const val MODEL_TYPE = "nemo_transducer"

    /** SD8G2 is 1+4 big cores; more threads than this buys nothing here. */
    const val NUM_THREADS = 4

    data class Asset(
        val url: String,
        val filename: String,
        val size: Long,
        val sha256: String,
    )

    private const val HF =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
    private const val GH =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val ASSETS = listOf(
        Asset("$HF/$ENCODER", ENCODER, 652_184_296L,
            "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
        Asset("$HF/$DECODER", DECODER, 7_257_753L,
            "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
        Asset("$HF/$JOINER", JOINER, 1_739_080L,
            "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
        Asset("$HF/$TOKENS", TOKENS, 9_384L,
            "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
        Asset("$GH/$VAD", VAD, 643_854L,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    )

    val TOTAL_BYTES: Long = ASSETS.sumOf { it.size }
}

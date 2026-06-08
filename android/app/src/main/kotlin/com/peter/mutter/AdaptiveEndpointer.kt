package com.peter.mutter

/**
 * Decides where to cut a long push-to-talk hold into chunks Whisper can
 * transcribe (Whisper's encoder is a hard 30s window). Pure logic, fed one
 * VAD window at a time — no audio, no native deps — so it is fully unit-tested.
 *
 * Per chunk (all counters reset on every cut, so the search restarts):
 *   - speechMs   : speech-only time      -> the minimum-speech gate
 *   - chunkMs    : total time            -> the silence-threshold ramp + emergency
 *   - silenceRun : trailing silence      -> resets to 0 on any speech window
 *
 * Cut when chunkMs hits the emergency ceiling (unconditional — guarantees every
 * chunk stays under Whisper's 30s limit), OR once there is enough speech and a
 * silence gap longer than the current threshold. The threshold shrinks as the
 * chunk grows, so we hold out for a clear pause early and accept a quick breath
 * late, keeping chunks responsive without ballooning.
 */
class AdaptiveEndpointer(
    private val windowMs: Int = 32, // 512 samples @ 16 kHz
    private val minSpeechMs: Int = 4_000,
    private val rampMidMs: Int = 7_000,
    private val rampFloorMs: Int = 15_000,
    private val silenceEarlyMs: Int = 500,
    private val silenceMidMs: Int = 300,
    private val silenceFloorMs: Int = 200,
    private val emergencyMs: Int = 25_000,
) {
    private var chunkMs = 0
    private var speechMs = 0
    private var silenceRunMs = 0

    /** Feed one VAD window. Returns true if a chunk boundary should be cut here. */
    fun feed(isSpeech: Boolean): Boolean {
        chunkMs += windowMs
        if (isSpeech) {
            speechMs += windowMs
            silenceRunMs = 0
        } else {
            silenceRunMs += windowMs
        }
        val cut = chunkMs >= emergencyMs ||
            (speechMs >= minSpeechMs && silenceRunMs >= requiredSilenceMs())
        if (cut) reset()
        return cut
    }

    private fun requiredSilenceMs(): Int = when {
        chunkMs < rampMidMs -> silenceEarlyMs
        chunkMs < rampFloorMs -> silenceMidMs
        else -> silenceFloorMs
    }

    /** Reset all counters — call at the start of each new hold. */
    fun reset() {
        chunkMs = 0
        speechMs = 0
        silenceRunMs = 0
    }
}

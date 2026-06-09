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
class AdaptiveEndpointer(private val windowMs: Int = 32) { // 512 samples @ 16 kHz

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
        val cut = chunkMs >= EMERGENCY_MS ||
            (speechMs >= MIN_SPEECH_MS && silenceRunMs >= requiredSilenceMs())
        if (cut) reset()
        return cut
    }

    private fun requiredSilenceMs(): Int = when {
        chunkMs < RAMP_MID_MS -> SILENCE_EARLY_MS
        chunkMs < RAMP_FLOOR_MS -> SILENCE_MID_MS
        else -> SILENCE_FLOOR_MS
    }

    /** Reset all counters — call at the start of each new hold. */
    fun reset() {
        chunkMs = 0
        speechMs = 0
        silenceRunMs = 0
    }

    private companion object {
        const val MIN_SPEECH_MS = 4_000      // no cut until this much speech
        const val RAMP_MID_MS = 7_000        // below → 500 ms gap, below RAMP_FLOOR → 300 ms
        const val RAMP_FLOOR_MS = 15_000     // at/above → 200 ms floor
        const val SILENCE_EARLY_MS = 500
        const val SILENCE_MID_MS = 300
        const val SILENCE_FLOOR_MS = 200
        const val EMERGENCY_MS = 25_000      // unconditional cut — keeps chunks < Whisper's 30 s
    }
}

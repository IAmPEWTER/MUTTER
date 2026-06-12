package com.peter.mutter

import kotlin.math.sqrt

/** RMS helper — the degraded-mode stand-in for Silero VAD (see [VadSegmenter]). */
object EnergyGate {

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) sumSq += (s.toDouble() * s.toDouble())
        return sqrt(sumSq / samples.size).toFloat()
    }
}

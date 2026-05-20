package com.peter.mutter

import kotlin.math.sqrt

object EnergyGate {

    const val DEFAULT_RMS_THRESHOLD = 0.01f
    const val DEFAULT_MIN_DURATION_SEC = 1.0f

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) sumSq += (s.toDouble() * s.toDouble())
        return sqrt(sumSq / samples.size).toFloat()
    }

    fun isSilent(
        samples: FloatArray,
        sampleRate: Int,
        rmsThreshold: Float = DEFAULT_RMS_THRESHOLD,
        minDurationSec: Float = DEFAULT_MIN_DURATION_SEC,
    ): Boolean {
        if (samples.isEmpty()) return true
        val durationSec = samples.size.toFloat() / sampleRate
        if (durationSec >= minDurationSec) return false
        return rms(samples) < rmsThreshold
    }
}

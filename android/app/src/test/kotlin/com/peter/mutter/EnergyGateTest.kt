package com.peter.mutter

import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyGateTest {

    @Test
    fun rmsOfEmptyIsZero() {
        assertEquals(0f, EnergyGate.rms(FloatArray(0)), 0f)
    }

    @Test
    fun rmsOfSilenceIsZero() {
        assertEquals(0f, EnergyGate.rms(FloatArray(16000)), 0.0001f)
    }

    @Test
    fun rmsOfConstantSignal() {
        val samples = FloatArray(1000) { 0.5f }
        assertEquals(0.5f, EnergyGate.rms(samples), 0.001f)
    }
}

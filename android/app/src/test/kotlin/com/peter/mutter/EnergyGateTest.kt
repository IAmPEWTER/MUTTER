package com.peter.mutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun shortAndSilentIsDropped() {
        val halfSecond = FloatArray(8000) // 0.5 s @ 16 kHz
        assertTrue(EnergyGate.isSilent(halfSecond, 16000))
    }

    @Test
    fun shortButLoudIsDropped() {
        // Short clip below min-duration AND below threshold → still silent.
        val halfSecond = FloatArray(8000) { 0.005f } // RMS = 0.005, below threshold
        assertTrue(EnergyGate.isSilent(halfSecond, 16000))
    }

    @Test
    fun longClipPassesEvenIfQuiet() {
        // After min-duration, energy gate says "not silent" so transcription runs.
        val twoSeconds = FloatArray(32000) // 2 s of zeros
        assertFalse(EnergyGate.isSilent(twoSeconds, 16000))
    }

    @Test
    fun shortAndLoudPasses() {
        val halfSecond = FloatArray(8000) { 0.1f }
        assertFalse(EnergyGate.isSilent(halfSecond, 16000))
    }

    @Test
    fun emptySamplesAreSilent() {
        assertTrue(EnergyGate.isSilent(FloatArray(0), 16000))
    }
}

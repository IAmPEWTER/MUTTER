package com.peter.mutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encodes the segmentation spec directly. Window = 32 ms (512 @ 16 kHz), so
 * speech/silence durations map to whole window counts:
 *   4 s speech = 125 windows, 500 ms = 16 windows, 300 ms = 10, 200 ms = 7,
 *   25 s = 782 windows.
 */
class AdaptiveEndpointerTest {

    private fun ep() = AdaptiveEndpointer()

    /** Feed n identical windows; return the 1-based index where a cut fired (asserts ≤1), else -1. */
    private fun feed(ep: AdaptiveEndpointer, isSpeech: Boolean, n: Int): Int {
        var cutAt = -1
        for (i in 1..n) {
            if (ep.feed(isSpeech)) {
                assertEquals("more than one cut in batch", -1, cutAt)
                cutAt = i
            }
        }
        return cutAt
    }

    @Test
    fun noCutBeforeFourSecondsOfSpeech() {
        val ep = ep()
        assertEquals(-1, feed(ep, true, 60))    // 1.92 s speech — gate not met
        assertEquals(-1, feed(ep, false, 200))  // 6.4 s silence, still blocked by the gate
    }

    @Test
    fun cutsAt500msSilence_earlyChunk() {
        val ep = ep()
        assertEquals(-1, feed(ep, true, 125))   // exactly 4 s speech, chunk < 7 s → 500 ms
        assertEquals(-1, feed(ep, false, 15))   // 480 ms < 500 → no cut
        assertTrue(ep.feed(false))              // 512 ms ≥ 500 → cut
    }

    @Test
    fun rampTo300msAfterSevenSeconds() {
        val ep = ep()
        assertEquals(-1, feed(ep, true, 250))   // 8 s speech, chunk in [7,15) s → 300 ms
        assertEquals(-1, feed(ep, false, 9))    // 288 ms < 300 → no cut
        assertTrue(ep.feed(false))              // 320 ms ≥ 300 → cut
    }

    @Test
    fun floorTo200msAfterFifteenSeconds() {
        val ep = ep()
        assertEquals(-1, feed(ep, true, 480))   // 15.36 s speech, chunk ≥ 15 s → 200 ms floor
        assertEquals(-1, feed(ep, false, 6))    // 192 ms < 200 → no cut
        assertTrue(ep.feed(false))              // 224 ms ≥ 200 → cut
    }

    @Test
    fun emergencyCutAt25sOfContinuousSpeech() {
        val ep = ep()
        assertEquals(-1, feed(ep, true, 781))   // 24.99 s, no silence at all → no normal cut
        assertTrue(ep.feed(true))               // 25.02 s ≥ 25 s → emergency cut
    }

    @Test
    fun searchRestartsAfterCut() {
        val ep = ep()
        assertEquals(-1, feed(ep, true, 125))
        assertEquals(16, feed(ep, false, 16))   // first cut at the 16th silence window
        // Second chunk must re-earn the 4 s gate: short speech + long silence → no cut.
        assertEquals(-1, feed(ep, true, 60))    // 1.92 s speech only
        assertEquals(-1, feed(ep, false, 40))   // 1.28 s silence, gate not met
    }

    @Test
    fun silenceRunResetsOnSpeech() {
        val ep = ep()
        assertEquals(-1, feed(ep, true, 125))   // gate met, req 500 ms
        assertEquals(-1, feed(ep, false, 15))   // 480 ms
        assertEquals(-1, feed(ep, true, 1))     // one speech window resets the silence run
        assertEquals(-1, feed(ep, false, 15))   // 480 ms again — never 500 ms consecutive → no cut
    }
}

package com.peter.mutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Stress: drive the endpointer over long, adversarial holds and assert the
 * invariant the whole feature rests on — every emitted chunk is short enough
 * for Whisper's 30 s encoder window, and no audio is ever dropped on the floor.
 *
 * The emergency cut fires when chunkMs ≥ 25 000, i.e. window 782 (32 ms each),
 * so the ceiling is 782 windows = 25.02 s < 30 s.
 */
class AdaptiveEndpointerStressTest {

    private val maxChunkWindows = 782
    private fun sec(x: Double): Int = (x * 16_000 / 512).toInt()

    /** Run a window pattern through a fresh endpointer; return chunk lengths in windows (incl. final flush). */
    private fun run(pattern: BooleanArray): List<Int> {
        val ep = AdaptiveEndpointer()
        val chunks = ArrayList<Int>()
        var cur = 0
        for (b in pattern) {
            cur++
            if (ep.feed(b)) {
                chunks.add(cur)
                cur = 0
            }
        }
        if (cur > 0) chunks.add(cur) // final partial chunk flushed on release
        return chunks
    }

    private fun assertBounded(chunks: List<Int>, totalWindows: Int) {
        for (c in chunks) {
            assertTrue("chunk $c windows exceeds Whisper-safe ceiling", c <= maxChunkWindows)
        }
        assertEquals("every window must land in exactly one chunk", totalWindows, chunks.sum())
    }

    @Test
    fun tenMinutesNonstopSpeech_neverExceedsCeiling() {
        val total = sec(600.0)
        val chunks = run(BooleanArray(total) { true })
        assertBounded(chunks, total)
        // With zero pauses, every cut is the emergency cut → uniform max-size chunks.
        assertTrue(chunks.size >= 23)
        assertTrue(chunks.dropLast(1).all { it == maxChunkWindows })
    }

    @Test
    fun twoMinutesPureSilence_neverExceedsCeiling() {
        val total = sec(120.0)
        assertBounded(run(BooleanArray(total) { false }), total)
    }

    @Test
    fun realisticFiveMinuteDictation_boundedAndNaturallyCut() {
        val rnd = Random(42)
        val target = sec(300.0)
        val pattern = ArrayList<Boolean>()
        while (pattern.size < target) {
            repeat(sec(2.0 + rnd.nextDouble() * 6.0)) { pattern.add(true) }  // 2–8 s burst
            repeat(sec(0.2 + rnd.nextDouble() * 1.3)) { pattern.add(false) } // 0.2–1.5 s pause
        }
        val arr = pattern.toBooleanArray()
        val chunks = run(arr)
        assertBounded(chunks, arr.size)
        assertTrue("a long dictation should split into many chunks", chunks.size >= 8)
        // Natural pause-cutting should dominate — most chunks finish before the emergency ceiling.
        assertTrue(chunks.count { it < maxChunkWindows } >= chunks.size / 2)
    }

    @Test
    fun alternatingShortBurstsBelowGate_flushOnlyAtEnd() {
        // 2 s speech + 1 s silence, repeated: never reaches the 4 s speech gate,
        // so nothing cuts until the 25 s emergency ceiling — still bounded.
        val pattern = ArrayList<Boolean>()
        repeat(20) {
            repeat(sec(2.0)) { pattern.add(true) }
            repeat(sec(1.0)) { pattern.add(false) }
        }
        val arr = pattern.toBooleanArray()
        assertBounded(run(arr), arr.size)
    }
}

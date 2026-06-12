package com.peter.mutter

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizerTest {

    @Test
    fun newlinesBecomeSpaces() {
        assertEquals("hello world", Sanitizer.sanitize("hello\nworld"))
        assertEquals("hello world", Sanitizer.sanitize("hello\r\nworld"))
        assertEquals("a b c", Sanitizer.sanitize("a\nb\rc"))
    }

    @Test
    fun runsOfSpacesCollapse() {
        assertEquals("hello world", Sanitizer.sanitize("hello   world"))
        assertEquals("a b c", Sanitizer.sanitize("a\t\tb \nc"))
    }

    @Test
    fun trimsEdges() {
        assertEquals("hello", Sanitizer.sanitize("  hello  "))
        assertEquals("hello", Sanitizer.sanitize("\n\nhello\n"))
    }

    @Test
    fun emptyAndWhitespaceProduceEmpty() {
        assertEquals("", Sanitizer.sanitize(""))
        assertEquals("", Sanitizer.sanitize("   "))
        assertEquals("", Sanitizer.sanitize("\n\n\r\t  "))
    }

    // --- collapseRepeats: whisper repeat-loop pathology bounded to one phrase ---

    @Test
    fun collapseKillsSpew() {
        val spew = ("Thank you. ".repeat(500)).trim()
        assertEquals("Thank you.", Sanitizer.collapseRepeats(spew))
    }

    @Test
    fun collapseHandlesMultiWordLoops() {
        val spew = ("Thanks for watching! ".repeat(12)).trim()
        assertEquals("Thanks for watching!", Sanitizer.collapseRepeats(spew))
        assertEquals("you", Sanitizer.collapseRepeats("you you you you you you"))
    }

    @Test
    fun collapseInsideRealSpeech() {
        assertEquals(
            "okay so Thank you. done",
            Sanitizer.collapseRepeats("okay so Thank you. Thank you. Thank you. Thank you. done"),
        )
    }

    @Test
    fun collapsePreservesRealSpeech() {
        for (s in listOf(
            "",
            "okay",
            "thank you",
            "I really really like it",
            "no no no", // three repeats — below the collapse threshold
            "that that was weird",
            "send the report to bob and then to alice please",
        )) {
            assertEquals(s, Sanitizer.collapseRepeats(s))
        }
    }
}

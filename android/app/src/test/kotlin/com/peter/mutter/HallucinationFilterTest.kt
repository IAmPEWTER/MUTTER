package com.peter.mutter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationFilterTest {

    @Test
    fun emptyAndNullAreHallucinations() {
        assertTrue(HallucinationFilter.isHallucination(null))
        assertTrue(HallucinationFilter.isHallucination(""))
        assertTrue(HallucinationFilter.isHallucination("   "))
        assertTrue(HallucinationFilter.isHallucination("\t\n"))
    }

    @Test
    fun exactPhrasesAreHallucinations() {
        for (phrase in listOf(
            "thank you", "Thank You", "thank you.", "THANK YOU!",
            "thanks", "thanks for watching", "subscribe", "please subscribe",
            "like and subscribe", "bye", "goodbye", "you",
            "the end", "so", "yeah", "okay", "ok",
            "um", "uh", "mm", "hmm",
        )) {
            assertTrue("Expected $phrase to be hallucination", HallucinationFilter.isHallucination(phrase))
        }
    }

    @Test
    fun repeatedFillerIsHallucination() {
        assertTrue(HallucinationFilter.isHallucination("thank you. thank you. thank you."))
        assertTrue(HallucinationFilter.isHallucination("you you you you"))
        assertTrue(HallucinationFilter.isHallucination("ok ok ok"))
        assertTrue(HallucinationFilter.isHallucination("mm hmm"))
    }

    @Test
    fun realTranscriptsArePreserved() {
        assertFalse(HallucinationFilter.isHallucination("Open the door, please."))
        assertFalse(HallucinationFilter.isHallucination("Thank you for the help"))
        assertFalse(HallucinationFilter.isHallucination("I think so"))
        assertFalse(HallucinationFilter.isHallucination("Subscribe to the newsletter"))
        assertFalse(HallucinationFilter.isHallucination("hello from mutter"))
    }

    @Test
    fun trailingPunctuationDoesNotChangeResult() {
        assertTrue(HallucinationFilter.isHallucination("you."))
        assertTrue(HallucinationFilter.isHallucination("you!"))
        assertTrue(HallucinationFilter.isHallucination("you?"))
        assertTrue(HallucinationFilter.isHallucination("you,"))
    }
}

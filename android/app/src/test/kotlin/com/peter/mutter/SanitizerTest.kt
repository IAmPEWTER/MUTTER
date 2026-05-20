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
}

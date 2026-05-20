package com.peter.mutter

object Sanitizer {

    private val WHITESPACE_RUN = Regex("\\s+")

    fun sanitize(text: String): String {
        val noNewlines = text.replace('\n', ' ').replace('\r', ' ')
        return WHITESPACE_RUN.replace(noNewlines, " ").trim()
    }
}

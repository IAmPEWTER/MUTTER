package com.peter.mutter

object HallucinationFilter {

    private val PHRASES = setOf(
        "thank you",
        "thanks",
        "thanks for watching",
        "thank you for watching",
        "thank you so much",
        "subscribe",
        "please subscribe",
        "like and subscribe",
        "subscribe to my channel",
        "bye",
        "goodbye",
        "you",
        "the end",
        "so",
        "yeah",
        "okay",
        "ok",
        "um",
        "uh",
        "mm",
        "hmm",
    )

    private val REPEAT_REGEX = Regex(
        "^\\s*(?:(?:thank you|thanks|bye|you|ok|okay|the end|so|yeah|mm|hmm|uh|um)" +
            "[\\s.!?,]*){1,}$",
        RegexOption.IGNORE_CASE,
    )

    private val TRAILING_PUNCT = ".!?,"

    fun isHallucination(text: String?): Boolean {
        if (text == null) return true
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return true
        val lowered = cleaned.lowercase().trimEnd { it in TRAILING_PUNCT }
        if (PHRASES.contains(lowered)) return true
        if (REPEAT_REGEX.matches(cleaned)) return true
        return false
    }
}

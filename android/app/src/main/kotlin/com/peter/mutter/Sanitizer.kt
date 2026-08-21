package com.peter.mutter

object Sanitizer {

    private val WHITESPACE_RUN = Regex("\\s+")
    private const val WORD_EDGE_PUNCT = ".,!?;:"

    fun sanitize(text: String): String {
        val noNewlines = text.replace('\n', ' ').replace('\r', ' ')
        return WHITESPACE_RUN.replace(noNewlines, " ").trim()
    }

    /**
     * Collapse runs of [minRepeats]+ consecutive identical word groups
     * (1..[maxPeriod] words, compared case-/punctuation-insensitively) down to
     * a single instance, repeating until stable.
     *
     * An autoregressive decoder can lock into a loop on noisy or marginal
     * audio and emit the same phrase hundreds of times ("Thank you. Thank
     * you. ..."). That was Whisper's pathology; a transducer emits per frame
     * and is far less prone to it, but the guard is cheap and the failure it
     * prevents is ugly, so it stays. Real dictation is untouched — nobody
     * says the same phrase four times running; if they truly do, one instance
     * still types.
     */
    fun collapseRepeats(text: String, minRepeats: Int = 4, maxPeriod: Int = 8): String {
        var current = text
        while (true) {
            val words = current.split(WHITESPACE_RUN).filter { it.isNotEmpty() }
            if (words.size < minRepeats) return current
            val norm = words.map { w -> w.lowercase().trim { it in WORD_EDGE_PUNCT } }
            val collapsed = collapseOnce(words, norm, minRepeats, maxPeriod).joinToString(" ")
            if (collapsed == current) return current
            current = collapsed
        }
    }

    private fun collapseOnce(
        words: List<String>,
        norm: List<String>,
        minRepeats: Int,
        maxPeriod: Int,
    ): List<String> {
        val n = words.size
        val out = ArrayList<String>(n)
        var i = 0
        outer@ while (i < n) {
            for (period in 1..minOf(maxPeriod, (n - i) / minRepeats)) {
                var reps = 1
                while (i + (reps + 1) * period <= n &&
                    norm.subList(i + reps * period, i + (reps + 1) * period) ==
                    norm.subList(i, i + period)
                ) reps++
                if (reps >= minRepeats) {
                    out.addAll(words.subList(i, i + period))
                    i += reps * period
                    continue@outer
                }
            }
            out.add(words[i])
            i++
        }
        return out
    }
}

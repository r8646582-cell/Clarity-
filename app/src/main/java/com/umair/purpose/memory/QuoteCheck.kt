package com.umair.purpose.memory

/**
 * A "quote" the coach stores or later shows back to him must be something he really wrote. Models paraphrase
 * while believing they are copying, so this is checked in code: a quote survives only if, ignoring case, spacing
 * and curly-versus-straight quote marks, it appears inside one of his own messages.
 */
object QuoteCheck {
    /** Anything shorter than this is too generic to count as a quote ("ok", "no"). */
    const val MIN_CHARS = 6

    private fun norm(s: String): String = s.lowercase()
        .replace('\u2019', '\'').replace('\u2018', '\'')
        .replace('\u201C', '"').replace('\u201D', '"')
        .replace(Regex("\\s+"), " ")
        .trim()

    /** True if [quote] appears inside one of [userMessages]. */
    fun isHis(quote: String, userMessages: List<String>): Boolean {
        val q = norm(quote).trim('"', '\'', ' ', '.', '\u2026').trim()
        if (q.length < MIN_CHARS) return false
        return userMessages.any { q in norm(it) }
    }

    /** Only the quotes he really wrote, in their original order. */
    fun verified(quotes: List<String>, userMessages: List<String>): List<String> =
        quotes.filter { isHis(it, userMessages) }
}

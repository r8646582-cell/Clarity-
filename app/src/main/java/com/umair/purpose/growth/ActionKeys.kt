package com.umair.purpose.growth

import com.umair.purpose.data.db.Promise

/**
 * UPDATE-18: which promises are "the same kind of action", so consistency can be counted over weeks. Reflection
 * tags promises with a short `action_key` ("study_5pm"); untagged ones are grouped by their normalized text.
 */
object ActionKeys {
    /** "Study 5pm" / "study-5pm " → "study_5pm". Null when there's nothing usable. */
    fun clean(raw: String?): String? {
        val k = raw?.trim()?.lowercase()?.replace(Regex("""[^a-z0-9]+"""), "_")?.trim('_') ?: return null
        return k.takeIf { it.length in 2..40 && !it.equals("null") && !it.equals("none") }
    }

    private val FILLER = setOf(
        "a", "an", "the", "to", "for", "of", "at", "in", "on", "my", "i", "will", "and", "with", "by", "before", "after",
        "tonight", "today", "tomorrow", "morning", "evening", "night", "minutes", "minute", "mins", "min", "one", "two",
        "some", "just", "then", "from", "every", "day", "daily",
    )

    /** For untagged promises: the words that name the action, sorted, so "Read FAR for 20 minutes" ~ "read FAR". */
    fun fromText(text: String): String =
        text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 && it !in FILLER && it.any(Char::isLetter) }
            .distinct().sorted().take(4).joinToString("_").ifEmpty { "other" }

    /** The group a promise counts in. */
    fun of(p: Promise): String = clean(p.actionKey) ?: ("~" + fromText(p.text))

    /** Distinct keys reflection already used, so it can reuse them. */
    fun used(promises: List<Promise>): List<String> = promises.mapNotNull { clean(it.actionKey) }.distinct().sorted()
}

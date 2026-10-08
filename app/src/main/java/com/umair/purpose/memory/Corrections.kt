package com.umair.purpose.memory

import com.umair.purpose.data.db.ProfileEntry

/**
 * UPDATE-19 "Corrections stick": a profile value he corrected ends with "(you confirmed)" (reflection.md adds it).
 * Confirmed entries are protected: gardening and reflection may only change them when he corrects them again.
 */
object Corrections {
    const val MARK = "(you confirmed)"

    fun isConfirmed(value: String): Boolean = value.trimEnd().endsWith(MARK, ignoreCase = true)

    /** The value without the marker, for showing in What I know (a "confirmed" tag shows instead). */
    fun display(value: String): String =
        if (isConfirmed(value)) value.trimEnd().dropLast(MARK.length).trimEnd() else value

    fun confirm(value: String): String = if (isConfirmed(value)) value.trim() else value.trim() + " " + MARK

    /** Corrected or edited by him: shown with the "confirmed" tag, and left alone by the AI. */
    fun isProtected(p: ProfileEntry): Boolean = p.editedByUser || isConfirmed(p.value)

    /** Reflection may replace [existing] with [newValue] only if it isn't confirmed, or if this is a new correction. */
    fun mayReplace(existing: ProfileEntry, newValue: String): Boolean = !isConfirmed(existing.value) || isConfirmed(newValue)

    /**
     * The one-time fix from UPDATE-19: his blocker app is Dechainer; Purpose is the coach's name.
     * Returns the corrected value, or null when [value] doesn't say the blocker is named Purpose.
     */
    fun dechainerFix(value: String): String? {
        val wrong = Regex("""(blocker[^.;\n]*?(?:named|called)\s+)["“']?Purpose["”']?""", RegexOption.IGNORE_CASE)
        if (!wrong.containsMatchIn(value)) return null
        return confirm(wrong.replace(value) { it.groupValues[1] + "Dechainer" })
    }
}

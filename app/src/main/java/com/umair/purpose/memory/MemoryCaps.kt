package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Strength

/**
 * CLAUDE.md "Built for a lifetime", part 2: memory in layers, so it never grows without limit. Active memory has
 * caps; when one is exceeded, memory gardening merges, and anything still over goes to the archive (kept forever,
 * searchable, never sent in full). The weakest go first: lowest confidence, then least recently seen, then least
 * often seen. Anything he edited himself is never archived (his edits are final).
 */
object MemoryCaps {
    const val PROFILE = 30
    const val STRENGTHS = 20
    val NOTES: Map<String, Int> = mapOf("pattern" to 15, "what_helps" to 15, "what_doesnt" to 15, "thread" to 10)

    data class Overflow(
        val notes: List<Note> = emptyList(),
        val strengths: List<Strength> = emptyList(),
        val profile: List<ProfileEntry> = emptyList(),
    ) {
        val isEmpty get() = notes.isEmpty() && strengths.isEmpty() && profile.isEmpty()
    }

    /** Whether anything is over its cap (a reason to run gardening soon). */
    fun over(notes: List<Note>, strengths: List<Strength>, profile: List<ProfileEntry>): Boolean =
        !overflow(notes, strengths, profile).isEmpty

    /** What to archive so every cap holds. Pure: the caller applies it (as retired) in one transaction. */
    fun overflow(notes: List<Note>, strengths: List<Strength>, profile: List<ProfileEntry>): Overflow {
        val rank = mapOf("guess" to 0, "likely" to 1, "confirmed" to 2)
        val outNotes = NOTES.flatMap { (type, cap) ->
            val active = notes.filter { it.status == Note.ACTIVE && it.type == type }
            val excess = active.size - cap
            if (excess <= 0) emptyList()
            // A confirmed note is protected (CLAUDE.md "Corrections stick"), so the cap may stay exceeded rather
            // than archive something he agreed with. Notes he edited are protected the same way.
            else active.filter { !it.editedByUser && it.confidence != "confirmed" }
                .sortedWith(compareBy<Note> { rank[it.confidence] ?: 0 }.thenBy { it.lastSeen }.thenBy { it.timesSeen }.thenBy { it.id })
                .take(excess)
                .map { it.copy(status = Note.RETIRED) }
        }
        val liveStrengths = strengths.filter { !it.deletedByUser && !it.retired }
        val outStrengths = (liveStrengths.size - STRENGTHS).takeIf { it > 0 }?.let { excess ->
            liveStrengths.filter { !it.editedByUser }.sortedWith(compareBy<Strength> { it.createdAt }.thenBy { it.id })
                .take(excess).map { it.copy(retired = true) }
        }.orEmpty()
        val liveProfile = profile.filter { !it.deletedByUser && !it.retired && it.value.isNotBlank() }
        val outProfile = (liveProfile.size - PROFILE).takeIf { it > 0 }?.let { excess ->
            liveProfile.filter { !Corrections.isProtected(it) }.sortedWith(compareBy<ProfileEntry> { it.updatedAt }.thenBy { it.key })
                .take(excess).map { it.copy(retired = true) }
        }.orEmpty()
        return Overflow(outNotes, outStrengths, outProfile)
    }

    /** For gardening's request: the caps, as a plain line it can work towards. */
    fun describe(): String =
        "Keep active memory within these limits, merging first: at most $PROFILE profile entries, " +
            "${NOTES["pattern"]} patterns, ${NOTES["what_helps"]} what-helps, ${NOTES["what_doesnt"]} what-doesn't, " +
            "${NOTES["thread"]} open threads and $STRENGTHS strengths. Anything still over will be archived (kept, not deleted)."
}

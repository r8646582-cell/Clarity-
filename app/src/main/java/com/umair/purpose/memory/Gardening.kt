package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.db.Strength
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

/** gardening.md output. */
@Serializable
data class GardeningResult(
    val merge: List<Merge> = emptyList(),
    val rewrite: List<Rewrite> = emptyList(),
    val retire: List<Retire> = emptyList(),
    @SerialName("merge_strengths") val mergeStrengths: List<MergeStrengths> = emptyList(),
    @SerialName("merge_people") val mergePeople: List<MergePeople> = emptyList(),
    @SerialName("rewrite_profile") val rewriteProfile: List<RewriteProfile> = emptyList(),
) {
    @Serializable
    data class RewriteProfile(val key: String = "", val value: String = "")

    @Serializable
    data class MergePeople(val ids: List<Long> = emptyList(), val name: String = "", val relation: String? = null, val notes: String? = null)

    @Serializable
    data class Merge(val ids: List<Long> = emptyList(), val text: String = "", val type: String? = null, val confidence: String? = null)

    @Serializable
    data class Rewrite(val id: Long = 0, val text: String = "")

    @Serializable
    data class Retire(val id: Long = 0, val reason: String? = null)

    @Serializable
    data class MergeStrengths(val ids: List<Long> = emptyList(), val text: String = "")
}

data class GardeningPlan(
    val notes: List<Note> = emptyList(),
    val strengths: List<Strength> = emptyList(),
    val deleteStrengthIds: List<Long> = emptyList(),
    val people: List<Person> = emptyList(),
    /** Duplicates merged into another row. */
    val deletePeopleIds: List<Long> = emptyList(),
    /** Profile lines rewritten to "you" (UPDATE-12). */
    val profile: List<ProfileEntry> = emptyList(),
) {
    val isEmpty get() = notes.isEmpty() && strengths.isEmpty() && deleteStrengthIds.isEmpty() && people.isEmpty() &&
        deletePeopleIds.isEmpty() && profile.isEmpty()
}

object Gardening {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(raw: String): GardeningResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw ReflectionParseException("No JSON object in reply")
        return try {
            json.decodeFromString(GardeningResult.serializer(), raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw ReflectionParseException("Reply was not valid gardening JSON", e)
        }
    }

    /** {{NOTES}}: active notes with what the prompt asks for. */
    fun notesBlock(notes: List<Note>, zone: ZoneId): String =
        notes.filter { it.status == Note.ACTIVE }.sortedBy { it.id }.joinToString("\n") { n ->
            "- id ${n.id} [${n.type}, ${n.confidence}, seen ${n.timesSeen}x, first ${ContextFormatter.date(n.firstSeen, zone)}, " +
                "last ${ContextFormatter.date(n.lastSeen, zone)}, edited_by_user: ${n.editedByUser}] ${n.text}"
        }.ifEmpty { "(no notes)" }

    fun strengthsBlock(strengths: List<Strength>): String =
        strengths.filter { !it.deletedByUser && !it.retired }.sortedBy { it.id }
            .joinToString("\n") { "- id ${it.id} [edited_by_user: ${it.editedByUser}] ${it.text}" }.ifEmpty { "(no strengths)" }

    /** {{PEOPLE}}: everyone he hasn't deleted, with ids, so duplicates can be merged. */
    fun peopleBlock(people: List<Person>): String =
        people.filter { !it.deletedByUser }.sortedBy { it.id }.joinToString("\n") { p ->
            "- id ${p.id} [edited_by_user: ${p.editedByUser}] ${p.name}" +
                (p.relation?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                (p.notes?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }.ifEmpty { "(no people)" }

    /** {{PROFILE}}: "Who you are" lines he hasn't deleted, with whether he edited them. */
    fun profileBlock(profile: List<ProfileEntry>): String =
        profile.filter { !it.deletedByUser && !it.retired && it.value.isNotBlank() }.sortedBy { it.key }
            .joinToString("\n") { "- key \"${it.key}\" [edited_by_user: ${Corrections.isProtected(it)}] ${it.value}" }.ifEmpty { "(no profile)" }

    fun summariesBlock(sessions: List<Session>, zone: ZoneId): String =
        sessions.filter { !it.summary.isNullOrBlank() }.sortedBy { it.startedAt }
            .joinToString("\n") { "- ${ContextFormatter.date(it.startedAt, zone)}: ${it.summary!!.trim()}" }.ifEmpty { "(no summaries)" }

    /**
     * What the model may change. Only active notes he never edited; each note at most once; merges need two or
     * more notes, keep the oldest note's row, add up times seen and keep the highest confidence. Merged-away and
     * retired notes become [Note.RETIRED] (kept, never shown or sent). Strengths he edited or deleted are untouched.
     */
    fun plan(
        result: GardeningResult,
        notes: List<Note>,
        strengths: List<Strength>,
        people: List<Person> = emptyList(),
        profile: List<ProfileEntry> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): GardeningPlan {
        // CLAUDE.md "Corrections stick": a confirmed entry is protected, so gardening may not merge, rewrite or
        // retire it. Excluding it here also fails the merge group's size check below, so a group containing one
        // is skipped whole rather than partly applied.
        val usable = notes.filter { it.status == Note.ACTIVE && !it.editedByUser && it.confidence != "confirmed" }
            .associateBy { it.id }
        val used = HashSet<Long>()
        val out = LinkedHashMap<Long, Note>()

        for (m in result.merge) {
            val group = m.ids.distinct().mapNotNull { usable[it] }.filter { it.id !in used }
            val text = m.text.trim()
            if (group.size < 2 || text.isEmpty() || group.size != m.ids.distinct().size) continue
            val keep = group.minBy { it.id }
            val type = m.type?.trim()?.lowercase()?.takeIf { it in Note.TYPES } ?: keep.type
            val confidence = group.maxBy { Note.CONFIDENCES.indexOf(it.confidence) }.confidence
            out[keep.id] = keep.copy(
                text = text,
                type = type,
                confidence = confidence,
                timesSeen = group.sumOf { it.timesSeen },
                firstSeen = group.minOf { it.firstSeen },
                lastSeen = group.maxOf { it.lastSeen },
                sourceSessionIds = Provenance.merge(group.map { it.sourceSessionIds }),
            )
            group.filter { it.id != keep.id }.forEach { out[it.id] = it.retiredAt(now) }
            used += group.map { it.id }
        }
        for (r in result.rewrite) {
            val n = usable[r.id] ?: continue
            val text = r.text.trim()
            if (n.id in used || text.isEmpty()) continue
            out[n.id] = n.copy(text = text)
            used += n.id
        }
        for (r in result.retire) {
            val n = usable[r.id] ?: continue
            if (n.id in used) continue
            out[n.id] = n.retiredAt(now)
            used += n.id
        }

        val okStrengths = strengths.filter { !it.editedByUser && !it.deletedByUser && !it.retired }.associateBy { it.id }
        val usedS = HashSet<Long>()
        val keptS = mutableListOf<Strength>()
        val deleteS = mutableListOf<Long>()
        for (m in result.mergeStrengths) {
            val group = m.ids.distinct().mapNotNull { okStrengths[it] }.filter { it.id !in usedS }
            val text = m.text.trim()
            if (group.size < 2 || text.isEmpty() || group.size != m.ids.distinct().size) continue
            // Strength stores a single source session. Cross-session merging would silently lose that history.
            if (group.map { it.sessionId }.distinct().size != 1) continue
            val keep = group.minBy { it.id }
            keptS += keep.copy(text = text)
            deleteS += group.filter { it.id != keep.id }.map { it.id }
            usedS += group.map { it.id }
        }
        // People: duplicates ("Father", "father", "Abbu") become one row, the oldest. Never anyone he edited.
        val okPeople = people.filter { !it.editedByUser && !it.deletedByUser }.associateBy { it.id }
        val usedP = HashSet<Long>()
        val keptP = mutableListOf<Person>()
        val deleteP = mutableListOf<Long>()
        for (m in result.mergePeople) {
            val ids = m.ids.distinct()
            val group = ids.mapNotNull { okPeople[it] }.filter { it.id !in usedP }
            val name = m.name.trim()
            if (group.size < 2 || name.isEmpty() || group.size != ids.size) continue
            val keep = group.minBy { it.id }
            keptP += keep.copy(
                name = name,
                relation = m.relation?.trim()?.takeIf { it.isNotEmpty() } ?: keep.relation,
                notes = m.notes?.trim()?.takeIf { it.isNotEmpty() } ?: keep.notes,
                updatedAt = group.maxOf { it.updatedAt },
                sourceSessionIds = Provenance.merge(group.map { it.sourceSessionIds }),
            )
            deleteP += group.filter { it.id != keep.id }.map { it.id }
            usedP += group.map { it.id }
        }
        // Profile: rewritten in place (to "you"), only lines that exist and that he never edited or deleted.
        val okProfile = profile.filter { !Corrections.isProtected(it) && !it.deletedByUser && !it.retired }.associateBy { it.key.lowercase() }
        val keptProfile = LinkedHashMap<String, ProfileEntry>()
        for (r in result.rewriteProfile) {
            val e = okProfile[r.key.trim().lowercase()] ?: continue
            val value = r.value.trim()
            if (value.isEmpty() || value == e.value || e.key.lowercase() in keptProfile) continue
            keptProfile[e.key.lowercase()] = e.copy(value = value, updatedAt = now)
        }
        return GardeningPlan(out.values.toList(), keptS, deleteS, keptP, deleteP, keptProfile.values.toList())
    }
}

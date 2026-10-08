package com.umair.purpose.memory

import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.IdeaUsed
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Quote
import com.umair.purpose.data.db.Strength
import com.umair.purpose.growth.ActionKeys
import com.umair.purpose.promise.Due
import com.umair.purpose.promise.PromiseRules

/** All stored memory, including what he deleted (so it is never re-created). */
data class MemorySnapshot(
    val profile: List<ProfileEntry>,
    val people: List<Person>,
    val notes: List<Note>,
    val promises: List<Promise>,
    val areas: List<AreaStatus>,
    val strengths: List<Strength> = emptyList(),
)

/** Rows to write. Id 0 means insert. Applied in one DB transaction. */
data class ApplyPlan(
    val summary: String,
    val profile: List<ProfileEntry> = emptyList(),
    val people: List<Person> = emptyList(),
    val notes: List<Note> = emptyList(),
    val promises: List<Promise> = emptyList(),
    val areas: List<AreaStatus> = emptyList(),
    val behaviorEvents: List<BehaviorEvent> = emptyList(),
    val strengths: List<Strength> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    val ideas: List<IdeaUsed> = emptyList(),
    /** Phase 2: earlier values of profile lines that changed, kept as retired notes (never deleted, never shown as current). */
    val history: List<Note> = emptyList(),
    val significance: Int? = null,
    val tone: String? = null,
    /** Null = keep the current title. */
    val title: String? = null,
)

/**
 * Decides what a reflection is allowed to change. The model proposes; this enforces the rules:
 * his edits and deletions are final, new notes start as guesses, only open promises move,
 * unknown ids and invalid values are ignored.
 */
object ReflectionPlanner {

    /**
     * {{SESSION_DATE}}: the day it started and, when it ran past midnight or was continued another day, the day
     * it ended, so "tomorrow" said in it resolves to the right date (UPDATE-12).
     */
    fun sessionDate(session: com.umair.purpose.data.db.Session, transcript: List<com.umair.purpose.data.db.Message>, zone: java.time.ZoneId): String {
        fun day(ms: Long): String {
            val d = ContextFormatter.date(ms, zone)
            return "$d (${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)})"
        }
        val start = day(session.startedAt)
        val end = day(transcript.maxOfOrNull { it.createdAt } ?: session.startedAt)
        return if (end == start) start else "$start, continuing until $end"
    }

    /** Ended, not yet reflected, and never an off-the-record one. Checked again just before reflecting. */
    fun needsReflection(s: com.umair.purpose.data.db.Session): Boolean = s.endedAt != null && !s.reflected && !s.offTheRecord

    fun plan(result: ReflectionResult, current: MemorySnapshot, sessionId: Long, now: Long): ApplyPlan = ApplyPlan(
        summary = result.sessionSummary.trim(),
        profile = planProfile(result, current, sessionId, now),
        people = planPeople(result, current, sessionId, now),
        notes = planNotes(result, current, sessionId, now),
        history = planHistory(result, current, now),
        promises = planPromises(result, current, sessionId, now),
        areas = planAreas(result, current, now),
        behaviorEvents = result.behaviorEvents.mapNotNull { b ->
            val e = BehaviorEvent(
                sessionId = sessionId, createdAt = now, whenText = b.whenText.clean(), situation = b.situation.clean(),
                feelingBefore = b.feelingBefore.clean(), action = b.action.clean(), payoff = b.payoff.clean(),
                outcome = b.outcome.clean(),
            )
            e.takeIf { it.situation != null || it.action != null }
        },
        strengths = planStrengths(result, current, sessionId, now),
        quotes = result.hisWords.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct().take(2)
            .map { Quote(sessionId = sessionId, text = it, createdAt = now) },
        ideas = result.ideasUsed.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinctBy { it.lowercase() }
            .map { IdeaUsed(sessionId = sessionId, tag = it, createdAt = now) },
        significance = result.significance?.coerceIn(1, 5),
        tone = result.tone.clean(),
        title = result.title.trim().trim('"').takeIf { it.isNotEmpty() && it.length <= 80 },
    )

    /** New strengths only; never one he deleted or one already there in other words. */
    private fun planStrengths(r: ReflectionResult, cur: MemorySnapshot, sessionId: Long, now: Long): List<Strength> {
        val out = mutableListOf<Strength>()
        for (raw in r.strengths) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if ((cur.strengths + out).any { similar(it.text, text) }) continue
            out += Strength(sessionId = sessionId, text = text, createdAt = now)
        }
        return out
    }

    /** Prefix of a history note's text; also how the app tells one apart from an ordinary archived note. */
    const val HISTORY_PREFIX = "Used to be true, "

    /**
     * Phase 2: when reflection changes what a profile line says, the old words are not lost. They become a retired
     * note ("Used to be true, city: Karachi") whose validTo is when it changed, so a question about the past can
     * still find it. Only changes the model made from his words: his own edits and deletions are corrections or
     * erasures, not history, and are never kept here. Same source conversations as the line, so forgetting one
     * conversation forgets its history too.
     */
    fun planHistory(r: ReflectionResult, cur: MemorySnapshot, now: Long): List<Note> {
        val byKey = cur.profile.associateBy { it.key.lowercase() }
        val kept = cur.notes.map { it.text.trim().lowercase() }.toSet()
        val out = LinkedHashMap<String, Note>()
        for (u in r.profileUpdates) {
            val key = u.key.trim()
            val value = u.value.trim()
            val old = byKey[key.lowercase()] ?: continue
            if (value.isEmpty() || old.value.isBlank() || old.value.trim() == value) continue
            if (old.editedByUser || old.deletedByUser || !Corrections.mayReplace(old, value)) continue
            val text = "$HISTORY_PREFIX${old.key}: ${old.value.trim()}"
            if (text.trim().lowercase() in kept) continue
            out[key.lowercase()] = Note(
                type = "thread", text = text, confidence = "guess", status = Note.RETIRED,
                timesSeen = 1, firstSeen = old.updatedAt, lastSeen = old.updatedAt,
                sourceSessionIds = old.sourceSessionIds,
                recordedAt = now, validFrom = old.validFrom.takeIf { it > 0 } ?: old.updatedAt, validTo = now,
            )
        }
        return out.values.toList()
    }

    private fun planProfile(r: ReflectionResult, cur: MemorySnapshot, sessionId: Long, now: Long): List<ProfileEntry> {
        val byKey = cur.profile.associateBy { it.key.lowercase() }
        val out = LinkedHashMap<String, ProfileEntry>()
        for (u in r.profileUpdates) {
            val key = u.key.trim()
            val value = u.value.trim()
            if (key.isEmpty() || value.isEmpty()) continue
            val existing = byKey[key.lowercase()]
            if (existing != null && (existing.editedByUser || existing.deletedByUser)) continue
            // UPDATE-19: a confirmed value changes only when he corrects it again.
            if (existing != null && !Corrections.mayReplace(existing, value)) continue
            out[key.lowercase()] = ProfileEntry(
                key = existing?.key ?: key, value = value, updatedAt = now,
                sourceSessionIds = existing?.let { Provenance.extend(it.sourceSessionIds, sessionId) } ?: sessionId.toString(),
                // A line the cap archived stays archived: reflection may update its words, but un-retiring it
                // would push it back into the context block and What I know.
                retired = existing?.retired ?: false,
                // Same words = same fact, so it keeps when it was written and when it began; new words start a new span.
                recordedAt = if (existing != null && existing.value == value) existing.recordedAt else now,
                validFrom = if (existing != null && existing.value == value) existing.validFrom else now,
                validTo = existing?.validTo,
            )
        }
        return out.values.toList()
    }

    private fun planPeople(r: ReflectionResult, cur: MemorySnapshot, sessionId: Long, now: Long): List<Person> {
        val byName = cur.people.associateBy { it.name.trim().lowercase() }
        val out = LinkedHashMap<String, Person>()
        for (u in r.peopleUpdates) {
            val name = u.name.trim()
            if (name.isEmpty()) continue
            val norm = name.lowercase()
            val existing = out[norm] ?: byName[norm]
            if (existing != null && (existing.editedByUser || existing.deletedByUser)) continue
            out[norm] = if (existing == null) {
                Person(
                    name = name, relation = u.relation.clean(), notes = u.notes.clean(), updatedAt = now,
                    sourceSessionIds = Provenance.add("", sessionId),
                )
            } else {
                existing.copy(
                    relation = u.relation.clean() ?: existing.relation,
                    notes = u.notes.clean() ?: existing.notes,
                    updatedAt = now,
                    sourceSessionIds = Provenance.extend(existing.sourceSessionIds, sessionId),
                )
            }
        }
        return out.values.toList()
    }

    private fun planNotes(r: ReflectionResult, cur: MemorySnapshot, sessionId: Long, now: Long): List<Note> {
        val byId = cur.notes.associateBy { it.id }.toMutableMap()
        val changed = LinkedHashMap<Long, Note>()
        val added = mutableListOf<Note>()

        for (u in r.notes.update) {
            val note = changed[u.id] ?: byId[u.id] ?: continue
            if (note.status != Note.ACTIVE) continue
            if (note.confidence == "confirmed") continue
            var next = note
            if (u.seenAgain) next = next.copy(timesSeen = next.timesSeen + 1, lastSeen = now)
            val text = u.text?.trim()
            if (!note.editedByUser && !text.isNullOrEmpty()) next = next.copy(text = text)
            val conf = u.confidence?.trim()?.lowercase()
            if (!note.editedByUser && conf != null && conf in Note.CONFIDENCES && confidenceAllowed(note.confidence, conf, u.seenAgain)) {
                next = next.copy(confidence = conf)
            }
            if (next != note) changed[note.id] = next.copy(sourceSessionIds = Provenance.extend(next.sourceSessionIds, sessionId))
        }

        for (id in r.notes.resolve) {
            val note = changed[id] ?: byId[id] ?: continue
            // Anything he edits in What I know is final. Resolving a note he wrote or edited would hide it from
            // him permanently — RESOLVED notes vanish from What I know and from the context, and the Archived
            // section only shows retired ones — so his own notes are never resolved this way.
            if (note.status == Note.ACTIVE && !note.editedByUser && note.confidence != "confirmed") {
                changed[id] = note.copy(status = Note.RESOLVED, lastSeen = now, validTo = note.validTo ?: now)
            }
        }

        val deleted = cur.notes.filter { it.status == Note.DELETED_BY_USER }
        val active = cur.notes.filter { it.status == Note.ACTIVE }
        for (a in r.notes.add) {
            val type = a.type.trim().lowercase()
            val text = a.text.trim()
            if (type !in Note.TYPES || text.isEmpty()) continue
            if (deleted.any { similar(it.text, text) }) continue
            if ((active + added).any { similar(it.text, text) }) continue
            // A new note always starts as a guess, whatever the model said.
            added += Note(
                type = type, text = text, confidence = "guess", status = Note.ACTIVE,
                timesSeen = 1, firstSeen = now, lastSeen = now, sourceSessionIds = Provenance.add("", sessionId),
                recordedAt = now, validFrom = now,
            )
        }
        return changed.values.toList() + added
    }

    /** Lowering is always fine. Rising to "likely" needs it to have shown up again; "confirmed" means he agreed. */
    private fun confidenceAllowed(from: String, to: String, seenAgain: Boolean): Boolean {
        val rank = Note.CONFIDENCES
        return when {
            rank.indexOf(to) <= rank.indexOf(from) -> true
            to == "confirmed" -> true
            else -> seenAgain
        }
    }

    private fun planPromises(r: ReflectionResult, cur: MemorySnapshot, sessionId: Long, now: Long): List<Promise> {
        val state = cur.promises.associateBy { it.id }.toMutableMap()
        val changed = LinkedHashMap<Long, Promise>()
        val added = mutableListOf<Promise>()

        fun transition(id: Long, f: (Promise) -> Promise) {
            val p = state[id] ?: return
            if (p.status != Promise.OPEN) return
            val next = f(p)
            state[id] = next
            changed[id] = next
        }
        r.promises.kept.forEach { o -> transition(o.id) { PromiseRules.keep(it, now, o.whatHappened, o.lesson) } }
        r.promises.broken.forEach { o -> transition(o.id) { PromiseRules.breakPromise(it, now, o.whatHappened, o.lesson) } }
        r.promises.dropped.forEach { id -> transition(id) { PromiseRules.drop(it, now) } }
        r.promises.renegotiated.forEach { rn ->
            val p = state[rn.id] ?: return@forEach
            if (p.status != Promise.OPEN) return@forEach
            val (closed, replacement) =
                PromiseRules.renegotiate(p, rn.text, Due.parse(rn.dueDate), now, sessionId)
            state[p.id] = closed
            changed[p.id] = closed
            added += replacement.copy(actionKey = replacement.actionKey ?: p.actionKey)
        }

        // Promises saved during this chat (by the hidden line, or an earlier pass over a continued conversation)
        // are already there; anything like one of them is a duplicate, even in fewer words.
        val savedThisSession = cur.promises.filter { it.sourceSessionId == sessionId }
        for (n in r.promises.new) {
            val text = n.text.trim()
            val key = ActionKeys.clean(n.actionKey)
            val existing = (state.values.filter { it.status == Promise.OPEN } + added).firstOrNull { samePromise(it.text, text) }
                ?: savedThisSession.firstOrNull { samePromise(it.text, text) }
            if (existing != null) {
                // Already saved during the chat (without a key): it gets the key, so it counts towards consistency.
                if (key != null && existing.actionKey == null && existing.id != 0L) {
                    val tagged = (changed[existing.id] ?: existing).copy(actionKey = key)
                    state[existing.id] = tagged
                    changed[existing.id] = tagged
                }
                continue
            }
            if (text.isEmpty()) continue
            added += Promise(
                text = text,
                createdAt = now,
                dueAt = Due.parse(n.dueDate)?.toString(),
                status = Promise.OPEN,
                sourceSessionId = sessionId,
                actionKey = key,
            )
        }
        return changed.values.toList() + added
    }

    /** An area he edited or deleted is final: reflection may neither overwrite nor re-create it. */
    private fun planAreas(r: ReflectionResult, cur: MemorySnapshot, now: Long): List<AreaStatus> =
        r.areaStatus.mapNotNull { u ->
            val area = u.area.trim().lowercase()
            val status = u.status.trim().lowercase()
            val existing = cur.areas.firstOrNull { it.area == area }
            if (area !in AreaStatus.AREAS || status !in AreaStatus.STATUSES) null
            else if (existing != null && (existing.editedByUser || existing.deletedByUser)) null
            else AreaStatus(area = area, status = status, note = u.note.clean(), updatedAt = now)
        }.associateBy { it.area }.values.toList()

    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** Same idea in different words: identical after normalizing, or mostly the same words. */
    internal fun similar(a: String, b: String): Boolean {
        if (negated(a) != negated(b)) return false
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta == tb) return true
        val overlap = ta.intersect(tb).size.toDouble()
        return overlap / ta.union(tb).size >= 0.6
    }

    /**
     * The same promise, for duplicates: [similar], or most of the shorter one's words are in the longer one
     * ("Read one page of FAR tonight" vs "Read one page of FAR at 9pm with phone in another room").
     */
    fun samePromise(a: String, b: String): Boolean {
        if (negated(a) != negated(b)) return false
        if (similar(a, b)) return true
        val ta = tokens(a)
        val tb = tokens(b)
        val small = minOf(ta.size, tb.size)
        if (small < 2) return false
        return ta.intersect(tb).size.toDouble() / small >= 0.75
    }

    // A lexical overlap is never enough to fold an explicit denial into its opposite assertion.
    private val NEGATION = setOf("not", "never", "no", "cannot", "can't", "cant", "don't", "dont", "doesn't", "doesnt",
        "didn't", "didnt", "isn't", "isnt", "aren't", "arent", "won't", "wont", "without", "nahi", "nahin", "نہیں")
    private fun negated(text: String): Boolean = Regex("""[\p{L}\p{N}']+""")
        .findAll(text.lowercase().replace('’', '\'' )).any { it.value in NEGATION }

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    /** UPDATE-17: a one-line summary for a conversation too short to reflect on, from his own words. */
    fun minimalSummary(his: List<String>): String {
        val first = his.firstOrNull()?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        if (first.isEmpty()) return "A conversation where Umair didn't say anything yet."
        val clipped = if (first.length <= 80) first else first.take(80).substringBeforeLast(' ') + "…"
        return "A short exchange; Umair only wrote: \"$clipped\""
    }
}

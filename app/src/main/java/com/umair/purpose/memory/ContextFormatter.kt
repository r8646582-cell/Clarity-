package com.umair.purpose.memory

import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.IdeaUsed
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Pulse
import com.umair.purpose.data.db.Quote
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.data.db.Strength
import com.umair.purpose.promise.Due
import com.umair.purpose.promise.due
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Everything the coach currently knows, as stored. Deleted items are filtered out here. */
data class Memory(
    val profile: List<ProfileEntry>,
    val people: List<Person>,
    val notes: List<Note>,
    val areas: List<AreaStatus>,
    /** All promises; open ones are listed with ids, resolved ones with their lessons. */
    val promises: List<Promise> = emptyList(),
    val strengths: List<Strength> = emptyList(),
    /** Newest first. */
    val behaviorEvents: List<BehaviorEvent> = emptyList(),
    /** Newest first. */
    val quotes: List<Quote> = emptyList(),
    /** Newest first. */
    val ideas: List<IdeaUsed> = emptyList(),
)

/** Extra pieces of the chat context that aren't plain memory. */
data class ChatExtras(
    val snapshot: Snapshot? = null,
    /** Before the snapshot exists: what onboarding has gathered (values picked, Big Five), already formatted. */
    val onboardingSoFar: List<String> = emptyList(),
    /** The last 14 days, any order. */
    val pulses: List<Pulse> = emptyList(),
    /** e.g. "Break the avoidance loop, day 3 of 7. Today: Shrink the start | ... | ..." */
    val journeyLine: String? = null,
    val record: List<String> = emptyList(),
    /** The letter he's come to talk about, if this session started from one. */
    val letterText: String? = null,
    /** UPDATE-14: the exact names of every journey he can start (built-in and his own), so none is invented. */
    val availableJourneys: List<String> = emptyList(),
    /** UPDATE-15: the last two life chapters, any order. */
    val chapters: List<com.umair.purpose.data.db.Chapter> = emptyList(),
)

/**
 * Turns memory into the plain-text blocks the prompts receive.
 * Output is fully deterministic (stable sort orders, no clock) so the chat prefix stays cacheable.
 */
object ContextFormatter {
    private const val NONE = "(nothing yet)"
    const val QUOTES_IN_CHAT = 10
    const val IDEAS_IN_CHAT = 20
    const val EVENTS_IN_CHAT = 15
    const val RECENT_RESOLVED = 10
    /** CLAUDE.md "Memory at scale": at most this many notes go with each chat message. All stay stored. */
    const val NOTES_IN_CHAT = 40

    /**
     * The notes worth sending when there are more than [NOTES_IN_CHAT]: confirmed before likely before guess,
     * then the most often seen, then the most recently seen. Returned in id order, so the prefix stays stable.
     */
    fun notesForChat(notes: List<Note>, limit: Int = NOTES_IN_CHAT): List<Note> {
        if (notes.size <= limit) return notes
        val rank = mapOf("confirmed" to 0, "likely" to 1, "guess" to 2)
        return notes.sortedWith(
            compareBy<Note> { rank[it.confidence] ?: 3 }.thenByDescending { it.timesSeen }.thenByDescending { it.lastSeen }.thenBy { it.id }
        ).take(limit).sortedBy { it.id }
    }

    /** The chat "context" block, or null when he and the coach are still new to each other. */
    fun chatContext(memory: Memory, extras: ChatExtras = ChatExtras(), zone: ZoneId): String? {
        val m = memory.visible().let { it.copy(notes = notesForChat(it.notes)) }
        val body = buildString {
            section("Who he is", profileLines(m.profile))
            section(
                "His story so far, in life chapters (oldest first)",
                extras.chapters.sortedBy { it.periodEnd }.map { "${it.periodStart} to ${it.periodEnd}: ${it.title}\n${it.content.trim()}" },
            )
            extras.snapshot?.let { section("His snapshot", snapshotLines(it)) }
            when {
                extras.snapshot == null -> section("From getting to know him so far", extras.onboardingSoFar)
                // A snapshot written before he sorted his values: his values still come with every message.
                OnboardingFormat.valuesLine(extras.snapshot.valuesJson) == null ->
                    section("From getting to know him so far", extras.onboardingSoFar.filter { it.contains("values", ignoreCase = true) })
            }
            section(
                "Daily check-ins (mood and energy out of 5)",
                extras.pulses.sortedBy { it.date }.map { p ->
                    "- ${p.date}: mood ${p.mood}, energy ${p.energy}" + (p.word?.takeIf(String::isNotBlank)?.let { ", \"$it\"" } ?: "")
                },
            )
            extras.journeyLine?.let { section("His current journey", listOf(it)) }
            section("His strengths", m.strengths.sortedBy { it.id }.map { "- ${it.text}" })
            section("People in his life", peopleLines(m.people))
            section("Patterns (a guess is only a guess)", noteLines(m.notes, "pattern"))
            section("What works for him", noteLines(m.notes, "what_helps"))
            section("What doesn't work for him", noteLines(m.notes, "what_doesnt"))
            section("Open threads", noteLines(m.notes, "thread"))
            section("Recent behavior, newest first", m.behaviorEvents.take(EVENTS_IN_CHAT).map { "- " + eventLine(it, zone) })
            section("Life areas", areaLines(m.areas))
            section("What the record shows", extras.record.map { "- $it" })
            section("Things he said that matter", m.quotes.take(QUOTES_IN_CHAT).map { "- ${date(it.createdAt, zone)}: \"${it.text}\"" })
            section("Ideas used recently (choose different ones)", recentIdeas(m.ideas).let { if (it.isEmpty()) emptyList() else listOf(it.joinToString("; ")) })
            section("Open promises", openPromiseLines(m.promises))
            section("Recently kept or broken, with what they taught", resolvedLines(m.promises.filter { it.status == Promise.KEPT || it.status == Promise.BROKEN }, RECENT_RESOLVED))
            extras.letterText?.let { section("He just read this letter you wrote him", listOf(it.trim())) }
            section("Available journeys (start only these, by their exact name)", extras.availableJourneys.map { "- $it" })
        }.trimEnd()
        if (body.isEmpty()) return null
        return "What you know about Umair so far:\n\n$body"
    }

    /** UPDATE-15: the whole stable context (memory block plus summaries) stays under this many tokens. */
    const val CONTEXT_BUDGET_TOKENS = 12_000

    /** Rough tokens for English text: about four characters each. */
    fun tokens(s: String?): Int = (s?.length ?: 0) / 4

    /** The context block and the recent summaries, trimmed to fit [budget]. */
    data class Fitted(val contextBlock: String?, val summaries: String?, val droppedSummaries: Int, val droppedNotes: Int) {
        val tokens: Int get() = tokens(contextBlock) + tokens(summaries)
    }

    /**
     * UPDATE-15 part 3: the context stays the same size forever. If memory and summaries come to more than [budget]
     * tokens, the oldest summaries go first, then the lowest-confidence, least-seen, oldest notes. Nothing is
     * deleted: this is only what goes with each message.
     */
    fun fitted(
        memory: Memory,
        extras: ChatExtras,
        summarySessions: List<Session>,
        zone: ZoneId,
        budget: Int = CONTEXT_BUDGET_TOKENS,
        extraSummaries: List<String> = emptyList(),
    ): Fitted {
        var sessions = summarySessions.filter { !it.summary.isNullOrBlank() }.sortedBy { it.startedAt }
        var notes = notesForChat(memory.notes.filter { it.status == Note.ACTIVE })
        val rank = mapOf("guess" to 0, "likely" to 1, "confirmed" to 2)
        val dropOrder = notes.sortedWith(compareBy<Note> { rank[it.confidence] ?: 0 }.thenBy { it.timesSeen }.thenBy { it.lastSeen }.thenBy { it.id })
        var droppedS = 0
        var droppedN = 0
        while (true) {
            val block = chatContext(memory.copy(notes = notes), extras, zone)
            val sums = listOfNotNull(recentSummaries(sessions, zone)).plus(extraSummaries).joinToString("\n\n").ifBlank { null }
            val f = Fitted(block, sums, droppedS, droppedN)
            if (f.tokens <= budget) return f
            when {
                sessions.isNotEmpty() -> { sessions = sessions.drop(1); droppedS++ }
                droppedN < dropOrder.size -> {
                    // A few at a time: each pass rebuilds the whole block.
                    val next = dropOrder.drop(droppedN).take(3).map { it.id }.toSet()
                    notes = notes.filter { it.id !in next }
                    droppedN += next.size
                }
                else -> {
                    // Profiles, portraits, chapters and letter text can exceed the budget even with no notes.
                    // Keep an explicit omission notice instead of sending unbounded lifetime context.
                    val chars = (budget.coerceAtLeast(0).toLong() * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val sumsLimit = minOf(sums?.length ?: 0, chars / 4)
                    val shortSums = boundedText(sums, sumsLimit)
                    return f.copy(contextBlock = boundedText(block, chars - (shortSums?.length ?: 0)), summaries = shortSums)
                }
            }
        }
    }

    private fun boundedText(text: String?, maxChars: Int): String? {
        if (text == null || maxChars <= 0) return null
        if (text.length <= maxChars) return text
        val notice = "\n[Context shortened; some details are omitted.]"
        if (maxChars <= notice.length) return notice.trim().take(maxChars)
        return text.take(maxChars - notice.length).trimEnd() + notice
    }

    /** CONTEXT for reflection.md: same facts, plus ids and open promises so the model can refer to them. */
    fun reflectionContext(memory: Memory, zone: ZoneId): String {
        val m = memory.visible()
        return buildString {
            section("Profile", profileLines(m.profile), always = true)
            section("People", peopleLines(m.people), always = true)
            section(
                "Active notes",
                m.notes.map { "- id ${it.id} [${it.type}, ${it.confidence}, seen ${it.timesSeen}x] ${it.text}" },
                always = true,
            )
            section("Open promises", openPromiseLines(m.promises), always = true)
            // UPDATE-18: reuse these for promises of the same kind.
            section("Action keys already used", com.umair.purpose.growth.ActionKeys.used(m.promises).map { "- $it" }, always = true)
            section("Strengths already noted", m.strengths.sortedBy { it.id }.map { "- ${it.text}" }, always = true)
            section("Recent behavior events, newest first", m.behaviorEvents.take(EVENTS_IN_CHAT).map { "- " + eventLine(it, zone) }, always = true)
            section("Area statuses", areaLines(m.areas), always = true)
        }.trimEnd()
    }

    /** Context for snapshot.md and the letters: profile-like facts in one block. */
    fun portraitContext(memory: Memory, zone: ZoneId): String {
        val m = memory.visible()
        return buildString {
            section("Profile", profileLines(m.profile), always = true)
            section("People", peopleLines(m.people), always = true)
            section("Patterns", noteLines(m.notes, "pattern"), always = true)
            section("What works for him", noteLines(m.notes, "what_helps"), always = true)
            section("What doesn't", noteLines(m.notes, "what_doesnt"), always = true)
            section("Strengths", m.strengths.sortedBy { it.id }.map { "- ${it.text}" }, always = true)
            section("Recent behavior", m.behaviorEvents.take(EVENTS_IN_CHAT).map { "- " + eventLine(it, zone) }, always = true)
        }.trimEnd()
    }

    fun deletedNotes(notes: List<Note>): String =
        notes.filter { it.status == Note.DELETED_BY_USER }.sortedBy { it.id }
            .joinToString("\n") { "- ${it.text}" }.ifEmpty { "(none)" }

    /** Dated summaries of the last sessions, oldest first. Null when there are none. */
    fun recentSummaries(sessions: List<Session>, zone: ZoneId): String? {
        val withSummary = sessions.filter { !it.summary.isNullOrBlank() }.sortedBy { it.startedAt }
        if (withSummary.isEmpty()) return null
        return "Your last conversations:\n" + withSummary.joinToString("\n") {
            "${date(it.startedAt, zone)}: ${it.summary!!.trim()}"
        }
    }

    /** One due or overdue promise, for the volatile part of the chat request. */
    fun duePromise(p: Promise, now: java.time.LocalDateTime): String {
        val due = p.due ?: return "He promised: ${p.text}."
        val whenText = com.umair.purpose.time.TimeFacts.duePhrase(due, now)
        val stale = if (staleOverdue(due, now)) " $OVERDUE_TAG" else ""
        return "He promised: ${p.text} ($whenText)$stale." + (p.why?.let { " Why: $it." } ?: "") + " Promise id ${p.id}."
    }

    /**
     * UPDATE-19: when every open promise is due, worked out in code ("due Monday 6 Oct, 8:00am (in 31 hours)").
     * A promise more than [STALE_OVERDUE_HOURS] past due is tagged [OVERDUE_UNCONFIRMED], so the coach confirms
     * with him before treating it as still open instead of a live blocker. Null when nothing has a due date.
     */
    fun timeFacts(promises: List<Promise>, now: java.time.LocalDateTime): String? {
        val lines = promises.filter { it.status == Promise.OPEN && !it.offTheRecord }.sortedBy { it.id }.mapNotNull { p ->
            val d = p.due
            val whenText = if (d == null) "no due date" else {
                val stale = if (staleOverdue(d, now)) " $OVERDUE_TAG" else ""
                com.umair.purpose.time.TimeFacts.duePhrase(d, now) + stale
            }
            "- Promise ${p.id}: ${p.text.trim()}: $whenText"
        }
        if (lines.isEmpty()) return null
        return "When things are (worked out for you; use these exact phrases, never calculate):\n" + lines.joinToString("\n")
    }

    /** True when a promise's due moment passed more than [STALE_OVERDUE_HOURS] ago. */
    internal fun staleOverdue(due: Due, now: LocalDateTime): Boolean {
        val moment = LocalDateTime.of(due.date, due.time ?: LocalTime.NOON)
        return Duration.between(moment, now).toMinutes() > STALE_OVERDUE_HOURS * 60
    }

    /** Overdue by more than this many hours: the promise is unconfirmed until he says what happened. */
    const val STALE_OVERDUE_HOURS = 12L

    const val OVERDUE_TAG = "[OVERDUE_UNCONFIRMED]"

    fun transcript(messages: List<Message>): String = messages.joinToString("\n\n", transform = ::transcriptLine)

    fun transcriptLine(m: Message): String {
        val who = if (m.role == Message.ROLE_USER) "Umair" else "Purpose"
        return "$who: ${m.content.trim()}"
    }

    /** Full local message date for reflection: a continued conversation can span months or cross midnight. */
    fun transcriptLine(m: Message, zone: ZoneId): String =
        "[${Instant.ofEpochMilli(m.createdAt).atZone(zone).toLocalDateTime()}] " + transcriptLine(m)

    fun date(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** The last [IDEAS_IN_CHAT] distinct idea tags, newest first. */
    fun recentIdeas(ideas: List<IdeaUsed>): List<String> =
        ideas.sortedWith(compareByDescending<IdeaUsed> { it.createdAt }.thenByDescending { it.id })
            .map { it.tag.trim() }.distinctBy { it.lowercase() }.take(IDEAS_IN_CHAT)

    internal fun profileLines(p: List<ProfileEntry>) = p.sortedBy { it.key }.map { "- ${it.key}: ${it.value}" }

    internal fun peopleLines(p: List<Person>) = p.sortedBy { it.name.lowercase() }.map { person ->
        buildString {
            append("- ").append(person.name)
            person.relation?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            person.notes?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
    }

    internal fun noteLines(notes: List<Note>, type: String) = notes.filter { it.type == type }.sortedBy { it.id }
        .map { "- [${it.confidence}, seen ${it.timesSeen}x] ${it.text}" }

    internal fun areaLines(a: List<AreaStatus>) =
        a.filterNot { it.deletedByUser }
            .sortedBy { AreaStatus.AREAS.indexOf(it.area).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .map { "- ${it.area}: ${it.status}" + (it.note?.takeIf { n -> n.isNotBlank() }?.let { n -> ". $n" } ?: "") }

    fun eventLine(e: BehaviorEvent, zone: ZoneId): String = buildString {
        append(date(e.createdAt, zone))
        e.whenText?.let { append(", ").append(it) }
        append(": ")
        append(
            listOfNotNull(
                e.situation,
                e.feelingBefore?.let { "felt $it" },
                e.action?.let { "→ $it" },
                e.payoff?.let { "→ payoff: $it" },
                e.outcome?.let { "→ $it" },
            ).joinToString(" ")
        )
    }

    /** Open promises he can be held to. An off-the-record promise keeps its reminder but never its words. */
    fun openPromiseLines(promises: List<Promise>) = promises.filter { it.status == Promise.OPEN && !it.offTheRecord }.sortedBy { it.id }.map { p ->
        buildString {
            append("- id ").append(p.id).append(": ").append(p.text)
            append(" (due ").append(p.dueAt?.replace('T', ' ') ?: "no date").append(')')
            p.why?.let { append(". Why: ").append(it) }
            if (p.remindAt != null) append(". Reminder set")
        }
    }

    /** Newest first, at most [limit]. Ordered with a tiebreaker: one reflection closes several promises with the
     *  same `resolvedAt`, and an unstable order here would change the cached stable block between requests. */
    fun resolvedLines(promises: List<Promise>, limit: Int) =
        promises.filterNot { it.offTheRecord }
            .sortedWith(compareByDescending<Promise> { it.resolvedAt ?: it.createdAt }.thenByDescending { it.id })
            .take(limit).map { p ->
            buildString {
                append("- ").append(p.status).append(": ").append(p.text)
                p.whatHappened?.let { append(". What happened: ").append(it) }
                p.lesson?.let { append(". Lesson: ").append(it) }
            }
        }

    private fun snapshotLines(s: Snapshot): List<String> = buildList {
        add("Title: ${s.title}")
        OnboardingFormat.bigFiveLine(s.bigFiveJson)?.let { add("Big Five: $it") }
        OnboardingFormat.valuesLine(s.valuesJson)?.let { add("Top values, in order: $it") }
        add("Portrait:\n${s.portrait.trim()}")
    }

    private fun Memory.visible() = copy(
        // Archived (retired) memory stays in the database and the search index, never in the context.
        profile = profile.filter { !it.deletedByUser && !it.retired },
        people = people.filter { !it.deletedByUser },
        notes = notes.filter { it.status == Note.ACTIVE }.sortedBy { it.id },
        strengths = strengths.filter { !it.deletedByUser && !it.retired },
        behaviorEvents = behaviorEvents.filter { !it.deletedByUser },
    )

    private fun StringBuilder.section(title: String, lines: List<String>, always: Boolean = false) {
        if (lines.isEmpty() && !always) return
        append(title).append(":\n")
        append(if (lines.isEmpty()) NONE else lines.joinToString("\n"))
        append("\n\n")
    }
}

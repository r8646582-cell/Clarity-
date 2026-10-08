package com.umair.purpose.growth

import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.JourneyAdjustment
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Pulse
import com.umair.purpose.data.db.Session
import com.umair.purpose.promise.due
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One kind of action he keeps promising (by action key, or by its words), and how it went. */
data class ActionStats(
    val key: String,
    /** A recent promise's words, so the model knows what the key means. */
    val example: String,
    val first: LocalDate,
    /** Planned and kept days in the last 14, 30 and 60 days. */
    val planned14: Int, val kept14: Int,
    val planned30: Int, val kept30: Int,
    val planned60: Int, val kept60: Int,
    val keptInLast7: Boolean,
    /** Days from the first planned day to today. */
    val spanDays: Int,
) {
    /** habit_built's minimum: 21+ days of evidence, kept on 75%+ of planned days, still happening this week. */
    val meetsHabitBuilt: Boolean
        get() = spanDays >= MIN_HABIT_DAYS && planned60 > 0 && kept60.toDouble() / planned60 >= MIN_KEPT_SHARE && keptInLast7

    companion object {
        const val MIN_HABIT_DAYS = 21
        const val MIN_KEPT_SHARE = 0.75
    }
}

/** A pattern note's history: how often, when first and last, and how much he talked since it was last seen. */
data class PatternStats(
    val noteId: Long,
    val text: String,
    val status: String,
    val sightings: Int,
    val first: LocalDate,
    val last: LocalDate,
    val daysSinceLast: Int,
    /** Conversations (with his messages in them) since it was last seen. */
    val conversationsSince: Int,
    /** Days he talked to Purpose since it was last seen. */
    val activeDaysSince: Int,
) {
    /**
     * habit_unlearned's minimum: seen 3+ times, then not for 30+ days WHILE he was active (6+ conversations).
     * Absence of data is not evidence: no conversations, no claim.
     */
    val meetsUnlearned: Boolean
        get() = sightings >= MIN_SIGHTINGS && daysSinceLast >= MIN_ABSENT_DAYS && conversationsSince >= MIN_ACTIVE_CONVERSATIONS

    companion object {
        const val MIN_SIGHTINGS = 3
        const val MIN_ABSENT_DAYS = 30
        const val MIN_ACTIVE_CONVERSATIONS = 6
    }
}

data class EvidenceStats(
    val today: LocalDate,
    val actions: List<ActionStats>,
    val patterns: List<PatternStats>,
    val activeDays30: Int,
    val activeDays60: Int,
    val conversations30: Int,
    /** "Mood 3.8 (last 14 days) vs 3.1 (the 14 before)", when there's enough. */
    val pulseLines: List<String>,
    val journeyLines: List<String>,
) {
    val anyHabitBuilt: Boolean get() = actions.any { it.meetsHabitBuilt }
    val anyUnlearned: Boolean get() = patterns.any { it.meetsUnlearned }
}

/**
 * UPDATE-18 "Evidence stats (computed in code, never by the AI)" for prompts/milestone.md {{STATS}}. Everything
 * here is counted from the database; the model only reads it. Pure, so it's tested.
 */
object Evidence {
    fun compute(
        today: LocalDate,
        zone: ZoneId,
        promises: List<Promise>,
        notes: List<Note>,
        sessions: List<Session>,
        /** When he wrote, for active days. */
        userMessageTimes: List<Long>,
        pulses: List<Pulse> = emptyList(),
        journeys: List<Journey> = emptyList(),
        adjustments: List<JourneyAdjustment> = emptyList(),
    ): EvidenceStats {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val activeDates = userMessageTimes.map(::day).toSortedSet()
        val talked = sessions.filter { it.userMessageCount > 0 && !it.offTheRecord }
        return EvidenceStats(
            today = today,
            actions = actions(today, zone, promises),
            patterns = notes.filter { it.type == "pattern" && it.status != Note.DELETED_BY_USER }.map { n ->
                val last = day(n.lastSeen)
                PatternStats(
                    noteId = n.id, text = n.text, status = n.status, sightings = n.timesSeen,
                    first = day(n.firstSeen), last = last,
                    daysSinceLast = ChronoUnit.DAYS.between(last, today).toInt().coerceAtLeast(0),
                    conversationsSince = talked.count { day(it.startedAt).isAfter(last) },
                    activeDaysSince = activeDates.count { it.isAfter(last) },
                )
            },
            activeDays30 = activeDates.count { !it.isBefore(today.minusDays(29)) },
            activeDays60 = activeDates.count { !it.isBefore(today.minusDays(59)) },
            conversations30 = talked.count { !day(it.startedAt).isBefore(today.minusDays(29)) },
            pulseLines = pulseLines(today, pulses),
            journeyLines = journeyLines(journeys, adjustments, zone),
        )
    }

    /**
     * Each group's planned days are the distinct days a promise of that kind was due (or made, when it had no
     * date); kept days are the distinct days one was kept. Open promises still in the future don't count as planned.
     */
    fun actions(today: LocalDate, zone: ZoneId, promises: List<Promise>): List<ActionStats> {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        // Off-the-record promises are not remembered: they never feed the growth tree or its prompt.
        val relevant = promises.filter { it.status != Promise.DROPPED && it.status != Promise.RENEGOTIATED && !it.offTheRecord }
        return relevant.groupBy { ActionKeys.of(it) }.mapNotNull { (key, group) ->
            val dated = group.mapNotNull { p ->
                val d = p.due?.date ?: day(p.createdAt)
                if (p.status == Promise.OPEN && !d.isBefore(today)) null else d to (p.status == Promise.KEPT)
            }
            if (dated.isEmpty()) return@mapNotNull null
            fun planned(days: Long) = dated.map { it.first }.filter { !it.isBefore(today.minusDays(days - 1)) && !it.isAfter(today) }.toSet().size
            fun kept(days: Long) = dated.filter { it.second }.map { it.first }.filter { !it.isBefore(today.minusDays(days - 1)) && !it.isAfter(today) }.toSet().size
            val first = dated.minOf { it.first }
            ActionStats(
                key = key,
                example = group.maxBy { it.createdAt }.text,
                first = first,
                planned14 = planned(14), kept14 = kept(14),
                planned30 = planned(30), kept30 = kept(30),
                planned60 = planned(60), kept60 = kept(60),
                keptInLast7 = kept(7) > 0,
                spanDays = ChronoUnit.DAYS.between(first, today).toInt() + 1,
            )
        }.sortedByDescending { it.planned60 }
    }

    private fun pulseLines(today: LocalDate, pulses: List<Pulse>): List<String> {
        val recent = pulses.filter { LocalDate.parse(it.date).let { d -> !d.isBefore(today.minusDays(13)) && !d.isAfter(today) } }
        val before = pulses.filter { LocalDate.parse(it.date).let { d -> !d.isBefore(today.minusDays(27)) && d.isBefore(today.minusDays(13)) } }
        if (recent.size < 3 || before.size < 3) return emptyList()
        fun avg(l: List<Int>) = "%.1f".format(java.util.Locale.US, l.average())
        return listOf(
            "Mood ${avg(recent.map { it.mood })} (last 14 days) vs ${avg(before.map { it.mood })} (the 14 before)",
            "Energy ${avg(recent.map { it.energy })} (last 14 days) vs ${avg(before.map { it.energy })} (the 14 before)",
        )
    }

    private fun journeyLines(journeys: List<Journey>, adjustments: List<JourneyAdjustment>, zone: ZoneId): List<String> =
        journeys.filter { it.status == Journey.DONE }.sortedBy { it.completedAt ?: it.startedAt }.map { j ->
            val adj = adjustments.filter { it.journeyId == j.id && it.decision != "continue" }
            "${j.name}: finished ${j.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: "?"}, ${j.totalDays} days" +
                (j.takeaway?.let { ". Takeaway: $it" } ?: "") +
                (if (adj.isEmpty()) "" else ". Adjusted on the way: " + adj.joinToString("; ") { "day ${it.day} ${it.decision.replace('_', ' ')}" })
        }

    /** The {{STATS}} text. */
    fun format(s: EvidenceStats): String = buildString {
        append("Today: ").append(s.today).append('\n')
        append("Active days: ").append(s.activeDays30).append(" of the last 30, ").append(s.activeDays60).append(" of the last 60. ")
        append("Conversations in the last 30 days: ").append(s.conversations30).append(".\n\n")
        append("Promises by kind of action (planned days / kept days):\n")
        if (s.actions.isEmpty()) append("(none yet)\n")
        s.actions.take(20).forEach { a ->
            append("- ").append(a.key).append(" (e.g. \"").append(a.example.take(80)).append("\"): ")
            append("14 days ${a.kept14}/${a.planned14}, 30 days ${a.kept30}/${a.planned30}, 60 days ${a.kept60}/${a.planned60}; ")
            append("first planned ${a.first} (${a.spanDays} days ago); kept in the last 7 days: ${if (a.keptInLast7) "yes" else "no"}")
            append(if (a.meetsHabitBuilt) ". MEETS the habit_built minimum." else ". Does not meet the habit_built minimum.")
            append('\n')
        }
        append("\nPatterns (from reflections; dates are when Purpose saw them):\n")
        if (s.patterns.isEmpty()) append("(none yet)\n")
        s.patterns.forEach { p ->
            append("- note ${p.noteId} [${p.status}]: ${p.text} | seen ${p.sightings} times, first ${p.first}, last ${p.last} ")
            append("(${p.daysSinceLast} days ago); since then: ${p.conversationsSince} conversations on ${p.activeDaysSince} days")
            append(if (p.meetsUnlearned) ". MEETS the habit_unlearned minimum." else ". Does not meet the habit_unlearned minimum.")
            append('\n')
        }
        if (s.pulseLines.isNotEmpty()) {
            append("\nDaily check-ins:\n")
            s.pulseLines.forEach { append("- ").append(it).append('\n') }
        }
        append("\nCompleted journeys:\n")
        if (s.journeyLines.isEmpty()) append("(none yet)\n")
        s.journeyLines.forEach { append("- ").append(it).append('\n') }
    }.trimEnd()
}

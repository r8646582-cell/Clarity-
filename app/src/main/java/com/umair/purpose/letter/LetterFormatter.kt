package com.umair.purpose.letter

import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Quote
import com.umair.purpose.data.db.Session
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.memory.Memory
import com.umair.purpose.promise.due
import java.time.ZoneId

/** Everything a letter can draw on, already loaded from the database. */
data class LetterInputs(
    val memory: Memory,
    /** Sessions in the period. */
    val sessions: List<Session>,
    /** Messages of those sessions, by session id. */
    val messages: Map<Long, List<Message>>,
    val record: List<String>,
    /** Weekly: last week's letter. */
    val previousLetter: Letter? = null,
    /** Monthly: that month's weekly letters. Yearly: that year's monthly letters. */
    val subLetters: List<Letter> = emptyList(),
)

/**
 * Picks which conversations a letter reads in full: most significant first, until the token budget is
 * spent; the rest go in as their summaries. Tokens are estimated at 4 characters each.
 */
object TranscriptBudget {
    const val TOKENS = 120_000
    const val CHARS_PER_TOKEN = 4

    data class Piece(val session: Session, val fullText: String?)

    fun select(sessions: List<Session>, transcripts: Map<Long, String>, budgetTokens: Int = TOKENS): List<Piece> {
        var left = budgetTokens.toLong() * CHARS_PER_TOKEN
        return sessions
            .filter { !transcripts[it.id].isNullOrBlank() }
            .sortedWith(compareByDescending<Session> { it.significance ?: 2 }.thenBy { it.startedAt })
            .map { s ->
                val text = transcripts.getValue(s.id)
                if (text.length <= left) {
                    left -= text.length
                    Piece(s, text)
                } else {
                    Piece(s, null)
                }
            }
    }
}

/** Fills letter_weekly.md and letter_monthly.md. */
object LetterFormatter {
    private const val NONE = "(nothing recorded)"

    fun values(period: LetterPeriod, inputs: LetterInputs, zone: ZoneId): Map<String, String> {
        val m = inputs.memory
        val inPeriod = { ms: Long -> ContextFormatter.date(ms, zone) in period }
        val sessions = inputs.sessions.filter { inPeriod(it.startedAt) }.sortedBy { it.startedAt }
        val transcripts = sessions.associate { s -> s.id to ContextFormatter.transcript(inputs.messages[s.id].orEmpty()) }
        val withTalk = sessions.filter { s -> inputs.messages[s.id].orEmpty().any { it.role == Message.ROLE_USER } }

        val base = mapOf(
            "PERIOD_START" to period.start.toString(),
            "PERIOD_END" to period.end.toString(),
            "PROFILE" to ContextFormatter.profileLines(m.profile.filter { !it.deletedByUser }).joinOr(),
            "PEOPLE" to ContextFormatter.peopleLines(m.people.filter { !it.deletedByUser }).joinOr(),
            "NOTES" to m.notes.filter { it.status == Note.ACTIVE }.sortedBy { it.id }
                .map { "- [${it.type}, ${it.confidence}, seen ${it.timesSeen}x] ${it.text}" }.joinOr(),
            "AREAS" to ContextFormatter.areaLines(m.areas).joinOr(),
            "PROMISES" to promisesBlock(promisesInPeriod(m.promises, period, zone)),
            "BEHAVIOR_EVENTS" to m.behaviorEvents.filter { !it.deletedByUser && inPeriod(it.createdAt) }
                .sortedBy { it.createdAt }.map { "- " + ContextFormatter.eventLine(it, zone) }.joinOr(),
            "RECORD" to inputs.record.map { "- $it" }.joinOr(),
            "RECENT_IDEAS" to ContextFormatter.recentIdeas(m.ideas).joinToString("; ").ifEmpty { NONE },
        )
        return when (period.kind) {
            Letter.WEEKLY -> base + mapOf(
                "PREVIOUS_LETTER" to (inputs.previousLetter?.let { "${it.title}\n\n${it.content}" } ?: "(none)"),
                "TRANSCRIPTS" to transcriptsBlock(withTalk, transcripts, zone, summariesForRest = true),
            )
            else -> base + mapOf(
                "LETTER_KIND" to period.kind,
                "HIS_WORDS" to quotesBlock(m.quotes.filter { inPeriod(it.createdAt) }, zone),
                "SUB_LETTERS" to inputs.subLetters.sortedBy { it.periodStart }
                    .joinToString("\n\n---\n\n") { "${it.title} (${it.periodStart} to ${it.periodEnd})\n\n${it.content}" }
                    .ifEmpty { "(none)" },
                "SUMMARIES" to sessions.filter { !it.summary.isNullOrBlank() }.map { s ->
                    "${ContextFormatter.date(s.startedAt, zone)}" +
                        (s.significance?.let { " (significance $it" + (s.tone?.let { t -> ", $t" } ?: "") + ")" }
                            ?: s.tone?.let { " ($it)" } ?: "") +
                        ": ${s.summary!!.trim()}"
                }.joinOr(),
                "TRANSCRIPTS" to if (period.kind == Letter.YEARLY) "(not included for a yearly letter)"
                else transcriptsBlock(withTalk, transcripts, zone, summariesForRest = false),
            )
        }
    }

    internal fun transcriptsBlock(
        sessions: List<Session>,
        transcripts: Map<Long, String>,
        zone: ZoneId,
        summariesForRest: Boolean,
    ): String {
        val pieces = TranscriptBudget.select(sessions, transcripts)
        val lines = pieces.mapNotNull { p ->
            val head = "Conversation on ${ContextFormatter.date(p.session.startedAt, zone)}" +
                (p.session.significance?.let { " (significance $it)" } ?: "")
            when {
                p.fullText != null -> "$head:\n${p.fullText}"
                summariesForRest && !p.session.summary.isNullOrBlank() -> "$head, summary only:\n${p.session.summary!!.trim()}"
                else -> null
            }
        }
        return lines.joinToString("\n\n=====\n\n").ifEmpty { NONE }
    }

    internal fun quotesBlock(quotes: List<Quote>, zone: ZoneId) =
        quotes.sortedBy { it.createdAt }.map { "- ${ContextFormatter.date(it.createdAt, zone)}: \"${it.text}\"" }.joinOr()

    /** Promises made, due, or resolved within the period. */
    internal fun promisesInPeriod(all: List<Promise>, period: LetterPeriod, zone: ZoneId) = all.filter { p ->
        ContextFormatter.date(p.createdAt, zone) in period ||
            p.resolvedAt?.let { ContextFormatter.date(it, zone) in period } == true ||
            p.due?.date?.let { it in period } == true
    }

    internal fun promisesBlock(promises: List<Promise>): String {
        if (promises.isEmpty()) return NONE
        val kept = promises.count { it.status == Promise.KEPT }
        val broken = promises.count { it.status == Promise.BROKEN }
        val open = promises.count { it.status == Promise.OPEN }
        val header = "Kept $kept, broken $broken, still open $open."
        val lines = promises.sortedBy { it.createdAt }.map { p ->
            buildString {
                append("- ").append(p.status).append(": ").append(p.text)
                append(" (due ").append(p.dueAt?.replace('T', ' ') ?: "no date").append(')')
                p.why?.let { append(". Why: ").append(it) }
                p.whatHappened?.let { append(". What happened: ").append(it) }
                p.lesson?.let { append(". Lesson: ").append(it) }
            }
        }
        return header + "\n" + lines.joinToString("\n")
    }

    private fun List<String>.joinOr() = if (isEmpty()) NONE else joinToString("\n")
}

package com.umair.purpose.chat

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import com.umair.purpose.memory.OnboardingTopic
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A row in the conversations drawer. */
data class Conversation(val session: Session, val lastAt: Long)

/** A row of the paged drawer (UPDATE-15): a group label, or a conversation. */
sealed interface DrawerItem {
    val key: String
    data class Header(val label: String) : DrawerItem { override val key get() = "g:$label" }
    data class Row(val conversation: Conversation) : DrawerItem { override val key get() = "c:${conversation.session.id}" }
}

/** Titles, date groups and the reflection marker for conversations he can return to. */
object Conversations {
    const val MAX_TITLE_WORDS = 6
    const val MAX_TITLE_CHARS = 40

    /** The line reflection.md looks for in a continued conversation. */
    const val ALREADY_REFLECTED = "--- ALREADY REFLECTED ABOVE THIS LINE ---"

    /** Until reflection names it: the first few words of his first message. */
    fun titleFrom(firstMessage: String): String? {
        val words = firstMessage.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        var title = words.take(MAX_TITLE_WORDS).joinToString(" ")
        if (title.length > MAX_TITLE_CHARS) title = title.take(MAX_TITLE_CHARS).substringBeforeLast(' ').ifEmpty { title.take(MAX_TITLE_CHARS) }
        return if (words.size > MAX_TITLE_WORDS || title.length < firstMessage.trim().length) "$title…" else title
    }

    /** The drawer's title, with fallbacks for conversations reflection hasn't named yet. */
    fun title(session: Session): String =
        session.title?.takeIf { it.isNotBlank() }
            ?: session.summary?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { titleFrom(it) }
            ?: "A conversation"

    /** "Practice", "Journey, day 3", or null for a plain talk. */
    fun modeLabel(session: Session): String? = when (Mode.fromWire(session.mode)) {
        Mode.PRACTICE -> "Practice"
        Mode.JOURNEY -> "Journey" + (session.journeyDay?.let { ", day $it" } ?: "")
        Mode.DECISION -> "Decision"
        Mode.UNTANGLE -> "Untangle"
        Mode.ONBOARDING -> OnboardingTopic.fromStep(session.modeDetail)?.let { "Getting to know you: ${it.title.lowercase()}" } ?: "Getting to know you"
        null -> if (session.letterId != null) "About a letter" else null
    }

    /**
     * Today, Yesterday, Previous 7 days, Previous 30 days, then by month ("September", or "September 2025" for
     * another year). Groups keep the order of [items] (newest first).
     */
    fun groups(items: List<Conversation>, today: LocalDate, zone: ZoneId): List<Pair<String, List<Conversation>>> {
        val out = LinkedHashMap<String, MutableList<Conversation>>()
        items.forEach { c -> out.getOrPut(groupLabel(c.lastAt, today, zone)) { mutableListOf() } += c }
        return out.map { it.key to it.value }
    }

    /** The group a conversation falls in by when it was last active (also the drawer's paged headers). */
    fun groupLabel(lastAt: Long, today: LocalDate, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(lastAt).atZone(zone).toLocalDate()
        return when {
            !d.isBefore(today) -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(8)) -> "Previous 7 days"
            d.isAfter(today.minusDays(31)) -> "Previous 30 days"
            d.year == today.year -> d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.year
        }
    }

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** The row's meta line: the time today, else the date, then the mode if any. */
    fun meta(c: Conversation, today: LocalDate, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(c.lastAt).atZone(zone)
        val `when` = if (t.toLocalDate() == today) t.format(TIME).lowercase() else t.format(DATE)
        return listOfNotNull(`when`, modeLabel(c.session)).joinToString(", ")
    }

    /**
     * The transcript reflection gets for a conversation he came back to: everything, with the marker line after
     * the last message already reflected on, so nothing is learned twice.
     */
    fun transcriptWithMarker(messages: List<Message>, reflectedUpTo: Long?, line: (Message) -> String): String {
        if (reflectedUpTo == null || messages.none { it.id > reflectedUpTo } || messages.none { it.id <= reflectedUpTo }) {
            return messages.joinToString("\n\n", transform = line)
        }
        val (before, after) = messages.partition { it.id <= reflectedUpTo }
        return (before.map(line) + ALREADY_REFLECTED + after.map(line)).joinToString("\n\n")
    }
}

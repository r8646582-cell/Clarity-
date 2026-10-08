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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ContextFormatterTest {
    private val utc = ZoneOffset.UTC
    private val day = 86_400_000L

    private fun note(id: Long, text: String, status: String = Note.ACTIVE, conf: String = "guess", type: String = "pattern") =
        Note(id, type, text, conf, status, 1, 0, 0)

    private fun promise(id: Long, text: String, status: String = Promise.OPEN, due: String? = null, resolvedAt: Long? = null) =
        Promise(id = id, text = text, createdAt = 0, dueAt = due, status = status, sourceSessionId = 1, resolvedAt = resolvedAt)

    private val memory = Memory(
        profile = listOf(
            ProfileEntry("values", "honesty, family", 1),
            ProfileEntry("future_self", "a calm, reliable CA", 1),
            ProfileEntry("old_job", "secret", 1, deletedByUser = true),
        ),
        people = listOf(Person(2, "zara", "sister", "studies medicine", 1), Person(1, "Ali", "friend", null, 1)),
        notes = listOf(
            note(5, "Late night, study feels heavy → scrolls → relief → lost evening", conf = "likely"),
            note(6, "Phone in the other room", type = "what_helps"),
            note(3, "Gone note", status = Note.DELETED_BY_USER),
            note(4, "Old thing", status = Note.RESOLVED),
        ),
        areas = listOf(AreaStatus("health", "steady", null, 1), AreaStatus("studies_career", "stuck", "mock failed", 1)),
        promises = listOf(
            promise(7, "Two chapters of FAR", due = "2026-10-05T21:00").copy(why = "testing a smaller start"),
            promise(8, "Walk after dinner", Promise.KEPT, resolvedAt = 5).copy(lesson = "works when planned before dinner"),
        ),
        strengths = listOf(Strength(1, 1, "Honest about his slips", 0), Strength(2, 1, "Hidden one", 0, deletedByUser = true)),
        behaviorEvents = listOf(
            BehaviorEvent(2, 1, 2 * day, "late night", "studying felt heavy", "tired", "scrolled", "relief", "lost the evening"),
            BehaviorEvent(1, 1, day, situation = "deleted", deletedByUser = true),
        ),
        quotes = listOf(Quote(1, 1, "I'm not lazy, I'm scared", day)),
        ideas = listOf(IdeaUsed(1, 1, "two arrows", 2), IdeaUsed(2, 1, "Two Arrows", 3), IdeaUsed(3, 1, "if-then plan", 1)),
    )

    @Test
    fun `empty memory gives no chat context`() {
        assertNull(ContextFormatter.chatContext(Memory(emptyList(), emptyList(), emptyList(), emptyList()), zone = utc))
    }

    @Test
    fun `chat context lists only visible items in a stable order, without note ids`() {
        val text = ContextFormatter.chatContext(memory, zone = utc)!!
        assertTrue(text.indexOf("future_self") < text.indexOf("values"))
        assertTrue(text.indexOf("- Ali (friend)") < text.indexOf("- zara (sister): studies medicine"))
        assertTrue(text.contains("Patterns (a guess is only a guess):\n- [likely, seen 1x] Late night"))
        assertTrue(text.contains("What works for him:\n- [guess, seen 1x] Phone in the other room"))
        assertTrue(text.indexOf("studies_career: stuck. mock failed") < text.indexOf("health: steady"))
        assertTrue(text.contains("His strengths:\n- Honest about his slips"))
        assertTrue(text.contains("1970-01-03, late night: studying felt heavy felt tired → scrolled → payoff: relief → lost the evening"))
        assertTrue(text.contains("- 1970-01-02: \"I'm not lazy, I'm scared\""))
        assertTrue(text.contains("Ideas used recently (choose different ones):\nTwo Arrows; if-then plan"))
        assertTrue(text.contains("- id 7: Two chapters of FAR (due 2026-10-05 21:00). Why: testing a smaller start"))
        assertTrue(text.contains("- kept: Walk after dinner. Lesson: works when planned before dinner"))
        listOf("secret", "Gone note", "Old thing", "id 5", "Hidden one", "deleted").forEach { assertFalse(it, text.contains(it)) }
        assertEquals(text, ContextFormatter.chatContext(memory.copy(people = memory.people.reversed()), zone = utc))
    }

    @Test
    fun `extras are snapshot, pulses, journey, record and the letter`() {
        val extras = ChatExtras(
            snapshot = Snapshot(1, 0, """{"answers":[],"percents":{"O":62,"C":35}}""", """["family","faith"]""", "A builder", "He builds."),
            pulses = listOf(Pulse("2026-10-02", 2, 3, "tired"), Pulse("2026-10-01", 4, 4)),
            journeyLine = "Break the avoidance loop, day 3 of 7. Step: Day 3 | Shrink the start | x | y",
            record = listOf("Promises: kept 3 of 4 overall."),
            letterText = "The week you admitted it\n\nBody.",
        )
        val text = ContextFormatter.chatContext(memory, extras, utc)!!
        assertTrue(text.contains("Title: A builder"))
        assertTrue(text.contains("Big Five: Openness 62%, Conscientiousness 35%"))
        assertTrue(text.contains("Top values, in order: family, faith"))
        assertTrue(text.contains("- 2026-10-01: mood 4, energy 4\n- 2026-10-02: mood 2, energy 3, \"tired\""))
        assertTrue(text.contains("His current journey:\nBreak the avoidance loop, day 3"))
        assertTrue(text.contains("What the record shows:\n- Promises: kept 3 of 4 overall."))
        assertTrue(text.trimEnd().endsWith("He just read this letter you wrote him:\nThe week you admitted it\n\nBody."))
        val noSnapshot = ContextFormatter.chatContext(memory, ChatExtras(onboardingSoFar = listOf("- Top values he picked, in order: family")), utc)!!
        assertTrue(noSnapshot.contains("From getting to know him so far:\n- Top values he picked"))
    }

    @Test
    fun `reflection context includes ids, open promises and recent behavior`() {
        val text = ContextFormatter.reflectionContext(memory, utc)
        assertTrue(text.contains("- id 5 [pattern, likely, seen 1x] Late night"))
        assertTrue(text.contains("- id 7: Two chapters of FAR (due 2026-10-05 21:00)"))
        assertTrue(text.contains("Recent behavior events, newest first:\n- 1970-01-03"))
        assertFalse(text.contains("Gone note"))
    }

    @Test
    fun `empty reflection sections say so`() {
        val text = ContextFormatter.reflectionContext(Memory(emptyList(), emptyList(), emptyList(), emptyList()), utc)
        assertTrue(text.contains("Open promises:\n(nothing yet)"))
    }

    @Test
    fun `deleted notes are listed for reflection`() {
        assertEquals("- Gone note", ContextFormatter.deletedNotes(memory.notes))
        assertEquals("(none)", ContextFormatter.deletedNotes(emptyList()))
    }

    @Test
    fun `summaries are dated and oldest first`() {
        val text = ContextFormatter.recentSummaries(
            listOf(Session(2, 2 * day, 2 * day, "Second."), Session(1, 0, 0, "First."), Session(3, 3 * day, null, null)),
            utc,
        )
        assertEquals("Your last conversations:\n1970-01-01: First.\n1970-01-03: Second.", text)
        assertNull(ContextFormatter.recentSummaries(emptyList(), utc))
    }

    @Test
    fun `due promise wording`() {
        val p = promise(1, "Call Ami", due = "2026-10-01")
        fun at(d: Int, h: Int = 12) = java.time.LocalDateTime.of(2026, 10, d, h, 0)
        assertEquals("He promised: Call Ami (due today, Thursday 1 Oct). Promise id 1.", ContextFormatter.duePromise(p, at(1)))
        // 11 hours past the date-only due moment (noon) is still fresh; past 12 hours it becomes unconfirmed.
        assertEquals("He promised: Call Ami (due today, Thursday 1 Oct). Promise id 1.", ContextFormatter.duePromise(p, at(1, 23)))
        assertEquals(
            "He promised: Call Ami (was due Thursday 1 Oct (1 day ago)) ${ContextFormatter.OVERDUE_TAG}. Promise id 1.",
            ContextFormatter.duePromise(p, at(2)),
        )
        assertEquals(
            "He promised: Call Ami (was due Thursday 1 Oct (3 days ago)) ${ContextFormatter.OVERDUE_TAG}. Promise id 1.",
            ContextFormatter.duePromise(p, at(4)),
        )
        val timed = promise(2, "Read", due = "2026-10-01T21:00").copy(why = "smaller start")
        assertEquals(
            "He promised: Read (due Thursday 1 Oct, 9:00pm (in 9 hours)). Why: smaller start. Promise id 2.",
            ContextFormatter.duePromise(timed, at(1)),
        )
        assertEquals(
            "When things are (worked out for you; use these exact phrases, never calculate):\n- Promise 2: Read: due Thursday 1 Oct, 9:00pm (in 9 hours)\n- Promise 3: No date: no due date",
            ContextFormatter.timeFacts(listOf(timed, promise(3, "No date", due = null)), at(1)),
        )
        // A promise long past due is flagged so it isn't treated as a live blocker.
        assertEquals(
            "When things are (worked out for you; use these exact phrases, never calculate):\n" +
                "- Promise 1: Call Ami: was due Thursday 1 Oct (1 day ago) ${ContextFormatter.OVERDUE_TAG}",
            ContextFormatter.timeFacts(listOf(p), at(2)),
        )
    }

    @Test
    fun `transcript names the speakers`() {
        val t = ContextFormatter.transcript(listOf(Message(1, 1, "user", "hi ", 0), Message(2, 1, "assistant", "Hey.", 1)))
        assertEquals("Umair: hi\n\nPurpose: Hey.", t)
    }

    @Test
    fun `chat sends at most 40 notes, the strongest, in a stable order`() {
        val notes = (1..60L).map { i ->
            Note(
                id = i, type = "pattern", text = "note $i", confidence = if (i % 3 == 0L) "confirmed" else "guess",
                status = Note.ACTIVE, timesSeen = (i % 5).toInt() + 1, firstSeen = i, lastSeen = i,
            )
        }
        val picked = ContextFormatter.notesForChat(notes)
        assertEquals(40, picked.size)
        // All 20 confirmed ones are in.
        assertTrue(notes.filter { it.confidence == "confirmed" }.all { it in picked })
        assertEquals(picked.sortedBy { it.id }, picked)
        val ctx = ContextFormatter.chatContext(Memory(emptyList(), emptyList(), notes, emptyList()), zone = ZoneOffset.UTC)!!
        assertEquals(40, ctx.lines().count { it.startsWith("- [") })
        assertEquals(notes.take(10), ContextFormatter.notesForChat(notes.take(10)))
    }
    @Test
    fun `reflection transcript dates each message in the requested timezone`() {
        val stamp = java.time.Instant.parse("2026-10-05T21:30:00Z").toEpochMilli()
        val line = ContextFormatter.transcriptLine(
            com.umair.purpose.data.db.Message(sessionId = 1, role = "user", content = "tomorrow at 8", createdAt = stamp),
            java.time.ZoneId.of("Asia/Karachi"),
        )
        org.junit.Assert.assertEquals("[2026-10-06T02:30] Umair: tomorrow at 8", line)
    }
}

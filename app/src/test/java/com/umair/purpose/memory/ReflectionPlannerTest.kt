package com.umair.purpose.memory

import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Promise
import com.umair.purpose.memory.ReflectionResult.AreaUpdate
import com.umair.purpose.memory.ReflectionResult.NewNote
import com.umair.purpose.memory.ReflectionResult.NewPromise
import com.umair.purpose.memory.ReflectionResult.NoteChanges
import com.umair.purpose.memory.ReflectionResult.NoteUpdate
import com.umair.purpose.memory.ReflectionResult.Outcome
import com.umair.purpose.memory.ReflectionResult.PersonUpdate
import com.umair.purpose.memory.ReflectionResult.ProfileUpdate
import com.umair.purpose.memory.ReflectionResult.PromiseChanges
import com.umair.purpose.memory.ReflectionResult.Renegotiation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionPlannerTest {
    private val now = 1_000L
    private fun note(id: Long, text: String, status: String = Note.ACTIVE, conf: String = "guess", edited: Boolean = false) =
        Note(id, "pattern", text, conf, status, 1, 0, 0, edited)
    private fun promise(id: Long, text: String, status: String = Promise.OPEN) =
        Promise(id = id, text = text, createdAt = 0, dueAt = "2026-10-05", status = status, sourceSessionId = 1)

    private val empty = MemorySnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun plan(r: ReflectionResult, cur: MemorySnapshot = empty) = ReflectionPlanner.plan(r, cur, sessionId = 9, now = now)

    @Test
    fun `summary is carried through`() {
        assertEquals("Hi.", plan(ReflectionResult(sessionSummary = " Hi. ")).summary)
    }

    @Test
    fun `new notes always start as guesses and bad types are dropped`() {
        val r = ReflectionResult("s", notes = NoteChanges(add = listOf(
            NewNote("pattern", "Studies late when anxious", "confirmed"),
            NewNote("diagnosis", "Something clinical", "guess"),
            NewNote("thread", "  ", "guess"),
        )))
        val notes = plan(r).notes
        assertEquals(1, notes.size)
        assertEquals("guess", notes[0].confidence)
        assertEquals(0L, notes[0].id)
        assertEquals(Note.ACTIVE, notes[0].status)
        assertEquals(1, notes[0].timesSeen)
    }

    @Test
    fun `a note he deleted is never re-created, even reworded`() {
        val cur = empty.copy(notes = listOf(note(1, "Avoids calling his father after arguments", Note.DELETED_BY_USER)))
        val r = ReflectionResult("s", notes = NoteChanges(add = listOf(
            NewNote("pattern", "avoids calling his father after arguments"),
            NewNote("pattern", "After arguments he avoids calling his father"),
        )))
        assertTrue(plan(r, cur).notes.isEmpty())
    }

    @Test
    fun `duplicates of active notes are skipped`() {
        val cur = empty.copy(notes = listOf(note(1, "Delays revision to Monday")))
        val r = ReflectionResult("s", notes = NoteChanges(add = listOf(NewNote("pattern", "delays revision to monday"))))
        assertTrue(plan(r, cur).notes.isEmpty())
    }

    @Test
    fun `seen again bumps count and allows rising to likely`() {
        val cur = empty.copy(notes = listOf(note(1, "Delays revision")))
        val n = plan(ReflectionResult("s", notes = NoteChanges(update = listOf(NoteUpdate(1, null, "likely", true)))), cur).notes.single()
        assertEquals(2, n.timesSeen)
        assertEquals(now, n.lastSeen)
        assertEquals("likely", n.confidence)
    }

    @Test
    fun `confidence cannot rise to likely without being seen again`() {
        val cur = empty.copy(notes = listOf(note(1, "Delays revision")))
        val out = plan(ReflectionResult("s", notes = NoteChanges(update = listOf(NoteUpdate(1, null, "likely", false)))), cur).notes
        assertTrue(out.isEmpty())
    }

    @Test
    fun `confirmed is allowed when he agreed, and lowering is allowed`() {
        val cur = empty.copy(notes = listOf(note(1, "A"), note(2, "B", conf = "likely")))
        val out = plan(ReflectionResult("s", notes = NoteChanges(update = listOf(
            NoteUpdate(1, null, "confirmed", false), NoteUpdate(2, null, "guess", false),
        ))), cur).notes.associateBy { it.id }
        assertEquals("confirmed", out[1]!!.confidence)
        assertEquals("guess", out[2]!!.confidence)
    }

    @Test
    fun `his wording of an edited note is kept`() {
        val cur = empty.copy(notes = listOf(note(1, "His words", edited = true)))
        val n = plan(ReflectionResult("s", notes = NoteChanges(update = listOf(NoteUpdate(1, "Model words", null, true)))), cur).notes.single()
        assertEquals("His words", n.text)
        assertEquals(2, n.timesSeen)
    }

    @Test
    fun `unknown, deleted and resolved notes are not touched, active ones resolve`() {
        val cur = empty.copy(notes = listOf(note(1, "A"), note(2, "B", Note.DELETED_BY_USER), note(3, "C", Note.RESOLVED)))
        val out = plan(ReflectionResult("s", notes = NoteChanges(
            update = listOf(NoteUpdate(2, "x", null, true), NoteUpdate(99, "y", null, true)),
            resolve = listOf(1, 2, 3, 42),
        )), cur).notes
        assertEquals(listOf(1L), out.map { it.id })
        assertEquals(Note.RESOLVED, out.single().status)
    }

    @Test
    fun `profile edits and deletions by him are final`() {
        val cur = empty.copy(profile = listOf(
            ProfileEntry("values", "his words", 0, editedByUser = true),
            ProfileEntry("hometown", "x", 0, deletedByUser = true),
            ProfileEntry("Studies", "CA", 0),
        ))
        val out = plan(ReflectionResult("s", profileUpdates = listOf(
            ProfileUpdate("values", "model words"), ProfileUpdate("hometown", "Chitral"),
            ProfileUpdate("studies", "CA, second attempt at FAR"), ProfileUpdate("future_self", "calm"), ProfileUpdate("", "x"),
        )), cur).profile.associateBy { it.key }
        assertEquals(setOf("Studies", "future_self"), out.keys)
        assertEquals("CA, second attempt at FAR", out["Studies"]!!.value)
    }

    @Test
    fun `people merge by name and keep old fields when not given`() {
        val cur = empty.copy(people = listOf(
            Person(1, "Ali", "friend", "studies with him", 0),
            Person(2, "Sara", null, null, 0, deletedByUser = true),
        ))
        val out = plan(ReflectionResult("s", peopleUpdates = listOf(
            PersonUpdate("ali", null, "moved to Lahore"), PersonUpdate("Sara", "cousin", null), PersonUpdate("Hamza", "brother", "null"),
        )), cur).people
        assertEquals(2, out.size)
        val ali = out.first { it.id == 1L }
        assertEquals("friend", ali.relation)
        assertEquals("moved to Lahore", ali.notes)
        val hamza = out.first { it.name == "Hamza" }
        assertEquals(0L, hamza.id)
        assertEquals(null, hamza.notes)
    }

    @Test
    fun `promise changes only move open promises`() {
        val cur = empty.copy(promises = listOf(
            promise(3, "Read chapter 1"), promise(4, "Gym twice"), promise(6, "Call Ami"), promise(8, "Old", Promise.KEPT),
        ))
        val out = plan(ReflectionResult("s", promises = PromiseChanges(
            kept = listOf(3L, 8L, 99L).map { Outcome(it) }, broken = listOf(4L, 3L).map { Outcome(it, "late night", "too close to dinner") },
            dropped = listOf(6),
        )), cur).promises.associateBy { it.id }
        assertEquals(setOf(3L, 4L, 6L), out.keys)
        assertEquals(Promise.KEPT, out[3]!!.status)
        assertEquals(Promise.BROKEN, out[4]!!.status)
        assertEquals(Promise.DROPPED, out[6]!!.status)
        assertEquals(now, out[3]!!.resolvedAt)
        assertEquals("too close to dinner", out[4]!!.lesson)
        assertEquals("late night", out[4]!!.whatHappened)
    }

    @Test
    fun `renegotiation closes the old promise and opens a new one`() {
        val cur = empty.copy(promises = listOf(promise(5, "Two chapters by Thursday")))
        val out = plan(ReflectionResult("s", promises = PromiseChanges(
            renegotiated = listOf(Renegotiation(5, "One chapter by Saturday", "2026-10-10")),
        )), cur).promises
        assertEquals(Promise.RENEGOTIATED, out.first { it.id == 5L }.status)
        val fresh = out.first { it.id == 0L }
        assertEquals(Promise.OPEN, fresh.status)
        assertEquals("2026-10-10", fresh.dueAt)
        assertEquals(9L, fresh.sourceSessionId)
    }

    @Test
    fun `new promises get parsed dates and duplicates of open ones are skipped`() {
        val cur = empty.copy(promises = listOf(promise(1, "Two chapters of FAR")))
        val out = plan(ReflectionResult("s", promises = PromiseChanges(new = listOf(
            NewPromise("two chapters of FAR", "2026-10-09"),
            NewPromise("Walk 20 minutes daily", "YYYY-MM-DD or null"),
            NewPromise("Sleep by midnight", "2026-10-04"),
            NewPromise(" ", null),
        ))), cur).promises
        assertEquals(listOf("Walk 20 minutes daily", "Sleep by midnight"), out.map { it.text })
        assertEquals(listOf(null, "2026-10-04"), out.map { it.dueAt })
    }

    @Test
    fun `a promise saved during this chat isn't saved again by reflection`() {
        val saved = promise(11, "Read one page of FAR at 9pm").copy(sourceSessionId = 9)
        val out = plan(ReflectionResult("s", promises = PromiseChanges(new = listOf(NewPromise("read one page of FAR at 9pm", null)))), empty.copy(promises = listOf(saved))).promises
        assertTrue(out.isEmpty())
    }

    @Test
    fun `new fields are events, strengths, quotes, ideas, significance, tone`() {
        val cur = empty.copy(strengths = listOf(com.umair.purpose.data.db.Strength(1, 1, "Honest about his slips", 0)))
        val r = ReflectionResult(
            "s",
            behaviorEvents = listOf(
                ReflectionResult.BehaviorUpdate("late night", "studying felt heavy", "tired", "scrolled", "relief", "lost the evening"),
                ReflectionResult.BehaviorUpdate(whenText = "morning"),
            ),
            strengths = listOf("honest about his slips", "Kept going after a bad mock", " "),
            significance = 9,
            tone = " low, self-critical ",
            hisWords = listOf("I'm not lazy, I'm scared", "I'm not lazy, I'm scared", "third", "fourth"),
            ideasUsed = listOf("two arrows", "Two Arrows", "if-then plan"),
        )
        val p = plan(r, cur)
        assertEquals(1, p.behaviorEvents.size)
        assertEquals("scrolled", p.behaviorEvents[0].action)
        assertEquals(9L, p.behaviorEvents[0].sessionId)
        assertEquals(listOf("Kept going after a bad mock"), p.strengths.map { it.text })
        assertEquals(5, p.significance)
        assertEquals("low, self-critical", p.tone)
        assertEquals(listOf("I'm not lazy, I'm scared", "third"), p.quotes.map { it.text })
        assertEquals(listOf("two arrows", "if-then plan"), p.ideas.map { it.tag })
    }

    @Test
    fun `only valid areas and statuses are kept`() {
        val out = plan(ReflectionResult("s", areaStatus = listOf(
            AreaUpdate("studies_career", "stuck", "mock"), AreaUpdate("career", "stuck", null), AreaUpdate("health", "great", null),
            AreaUpdate("Health", "Growing", ""),
        ))).areas
        assertEquals(listOf("studies_career", "health"), out.map { it.area })
        assertEquals("growing", out[1].status)
        assertEquals(null, out[1].note)
    }

    @Test
    fun `similarity`() {
        assertTrue(ReflectionPlanner.similar("Gets anxious before exams", "gets anxious before exams!"))
        assertTrue(!ReflectionPlanner.similar("Gets anxious before exams", "Sleeps well after the gym"))
    }

    @Test
    fun `a confirmed profile value changes only when he corrects it again`() {
        val cur = empty.copy(profile = listOf(ProfileEntry("projects", "Built a blocker app named Dechainer (you confirmed)", 0)))
        val ignored = plan(ReflectionResult("s", profileUpdates = listOf(ProfileUpdate("projects", "Built a blocker app named Purpose"))), cur)
        assertTrue(ignored.profile.isEmpty())
        val corrected = plan(ReflectionResult("s", profileUpdates = listOf(ProfileUpdate("projects", "Built Dechainer, a blocker app (you confirmed)"))), cur)
        assertEquals("Built Dechainer, a blocker app (you confirmed)", corrected.profile.single().value)
    }
    @Test
    fun `confirmed notes cannot be rewritten downgraded or resolved by reflection`() {
        val cur = empty.copy(notes = listOf(note(1, "You value quiet study", conf = "confirmed")))
        val out = plan(ReflectionResult("s", notes = NoteChanges(
            update = listOf(NoteUpdate(1, "Model replacement", "guess", true)), resolve = listOf(1),
        )), cur)
        assertTrue(out.notes.isEmpty())
    }

    @Test
    fun `duplicates within one reflection produce only one new promise`() {
        val out = plan(ReflectionResult("s", promises = PromiseChanges(new = listOf(
            NewPromise("Read one chapter of FAR", null),
            NewPromise("Read one chapter of FAR tonight", null),
        )))).promises
        assertEquals(1, out.size)
    }
    @Test
    fun `reflection retains due clocks for new and renegotiated promises`() {
        val cur = empty.copy(promises = listOf(promise(5, "Read FAR")))
        val out = plan(ReflectionResult("s", promises = PromiseChanges(
            new = listOf(NewPromise("Sleep with phone outside", "2026-10-06T02:30")),
            renegotiated = listOf(Renegotiation(5, "Read tax", "2026-10-07T17:45")),
        )), cur).promises
        assertEquals("2026-10-06T02:30", out.first { it.text == "Sleep with phone outside" }.dueAt)
        assertEquals("2026-10-07T17:45", out.first { it.text == "Read tax" }.dueAt)
    }
    @Test fun `opposite assertions cannot merge by lexical overlap`() {
        assertFalse(ReflectionPlanner.similar("Walking helps you regulate stress", "Walking does not help you regulate stress"))
        assertFalse(ReflectionPlanner.samePromise("Skip the evening study session", "Do not skip the evening study session"))
        assertFalse(ReflectionPlanner.similar("You enjoy studying in groups", "You don’t enjoy studying in groups"))
        assertFalse(ReflectionPlanner.similar("Group study pasand hai", "Group study pasand nahi hai"))
        assertTrue(ReflectionPlanner.similar("Walking helps regulate stress", "Walking helps regulate stress"))
    }

}

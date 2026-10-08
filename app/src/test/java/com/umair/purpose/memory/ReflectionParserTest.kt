package com.umair.purpose.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReflectionParserTest {
    private val full = """
    {
      "session_summary": "He talked about the mock exam.",
      "profile_updates": [{"key": "future_self", "value": "calm CA"}],
      "people_updates": [{"name": "Ali", "relation": "friend", "notes": "studies with him"}],
      "notes": {
        "add": [{"type": "pattern", "text": "Delays revision", "confidence": "guess"}],
        "update": [{"id": 12, "text": "x", "confidence": "likely", "seen_again": true}],
        "resolve": [7]
      },
      "promises": {
        "new": [{"text": "Two chapters", "due_date": "2026-10-08"}],
        "kept": [{"id": 3, "what_happened": "did it at 9", "lesson": "phone out of the room works"}],
        "broken": [{"id": 4, "what_happened": "family argument", "lesson": "9pm is too close to dinner"}],
        "renegotiated": [{"id": 5, "text": "One chapter", "due_date": null}],
        "dropped": [6]
      },
      "area_status": [{"area": "studies_career", "status": "stuck", "note": "mock"}],
      "behavior_events": [{"when": "late night", "situation": "study felt heavy", "feeling_before": "tired", "action": "scrolled", "payoff": "relief", "outcome": "lost the evening"}],
      "strengths": ["Honest about slips"],
      "significance": 4,
      "tone": "low, self-critical",
      "his_words": ["I'm not lazy, I'm scared"],
      "ideas_used": ["two arrows"]
    }
    """

    @Test
    fun `parses the full shape from CLAUDE md`() {
        val r = ReflectionParser.parse(full)
        assertEquals("He talked about the mock exam.", r.sessionSummary)
        assertEquals("future_self", r.profileUpdates.single().key)
        assertEquals("Ali", r.peopleUpdates.single().name)
        assertEquals(12L, r.notes.update.single().id)
        assertTrue(r.notes.update.single().seenAgain)
        assertEquals(listOf(7L), r.notes.resolve)
        assertEquals("2026-10-08", r.promises.new.single().dueDate)
        assertEquals(ReflectionResult.Outcome(3, "did it at 9", "phone out of the room works"), r.promises.kept.single())
        assertEquals("9pm is too close to dinner", r.promises.broken.single().lesson)
        assertEquals("scrolled", r.behaviorEvents.single().action)
        assertEquals("tired", r.behaviorEvents.single().feelingBefore)
        assertEquals("late night", r.behaviorEvents.single().whenText)
        assertEquals(listOf("Honest about slips"), r.strengths)
        assertEquals(4, r.significance)
        assertEquals("low, self-critical", r.tone)
        assertEquals(listOf("I'm not lazy, I'm scared"), r.hisWords)
        assertEquals(listOf("two arrows"), r.ideasUsed)
        assertNull(r.promises.renegotiated.single().dueDate)
        assertEquals("stuck", r.areaStatus.single().status)
    }

    @Test
    fun `older bare-id kept and broken lists still parse`() {
        val r = ReflectionParser.parse("""{"session_summary": "s", "promises": {"kept": [3, 5], "broken": [4]}}""")
        assertEquals(listOf(3L, 5L), r.promises.kept.map { it.id })
        assertNull(r.promises.kept.first().lesson)
        assertEquals(4L, r.promises.broken.single().id)
        assertNull(r.significance)
    }

    @Test
    fun `tolerates code fences and missing categories`() {
        val r = ReflectionParser.parse("```json\n{\"session_summary\": \"Short one.\"}\n```")
        assertEquals("Short one.", r.sessionSummary)
        assertTrue(r.notes.add.isEmpty())
        assertTrue(r.promises.new.isEmpty())
    }

    @Test
    fun `null lists are treated as empty`() {
        val r = ReflectionParser.parse("""{"session_summary": "s", "profile_updates": null, "notes": {"add": null}}""")
        assertTrue(r.profileUpdates.isEmpty())
        assertTrue(r.notes.add.isEmpty())
    }

    @Test(expected = ReflectionParseException::class)
    fun `prose is rejected`() {
        ReflectionParser.parse("Sorry, I can't do that.")
    }

    @Test(expected = ReflectionParseException::class)
    fun `broken json is rejected`() {
        ReflectionParser.parse("""{"session_summary": "s", "notes": {"add": [}""")
    }

    @Test(expected = ReflectionParseException::class)
    fun `a reply without a summary is rejected`() {
        ReflectionParser.parse("""{"profile_updates": []}""")
    }

    @Test
    fun `dates`() {
        assertEquals(LocalDate.of(2026, 10, 8), ReflectionParser.parseDate("2026-10-08"))
        assertNull(ReflectionParser.parseDate(null))
        assertNull(ReflectionParser.parseDate("null"))
        assertNull(ReflectionParser.parseDate("YYYY-MM-DD or null"))
        assertNull(ReflectionParser.parseDate("2026-13-45"))
    }
}

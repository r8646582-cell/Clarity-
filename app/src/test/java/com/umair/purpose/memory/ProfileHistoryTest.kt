package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.memory.ReflectionResult.ProfileUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileHistoryTest {
    private val now = 5_000L
    private fun entry(key: String, value: String, edited: Boolean = false, deleted: Boolean = false) = ProfileEntry(
        key = key, value = value, updatedAt = 1_000L, editedByUser = edited, deletedByUser = deleted, sourceSessionIds = "3,4", validFrom = 900L,
    )
    private fun snap(vararg p: ProfileEntry, notes: List<Note> = emptyList()) =
        MemorySnapshot(p.toList(), emptyList(), notes, emptyList(), emptyList())
    private fun history(r: List<ProfileUpdate>, cur: MemorySnapshot) =
        ReflectionPlanner.planHistory(ReflectionResult("s", profileUpdates = r), cur, now)

    @Test
    fun `a changed profile line keeps its old words as a retired note with validity closed`() {
        val h = history(listOf(ProfileUpdate("city", "Lahore")), snap(entry("city", "Karachi"))).single()
        assertEquals("Used to be true, city: Karachi", h.text)
        assertEquals(Note.RETIRED, h.status)
        assertEquals(now, h.validTo)
        assertEquals(900L, h.validFrom)
        assertEquals("3,4", h.sourceSessionIds)
        assertEquals("guess", h.confidence)
        assertEquals(1, h.timesSeen)
    }

    @Test
    fun `no history for new lines, same values, or empty updates`() {
        assertTrue(history(listOf(ProfileUpdate("job", "Analyst")), snap()).isEmpty())
        assertTrue(history(listOf(ProfileUpdate("city", " Karachi ")), snap(entry("city", "Karachi"))).isEmpty())
        assertTrue(history(listOf(ProfileUpdate("city", "")), snap(entry("city", "Karachi"))).isEmpty())
    }

    @Test
    fun `his own edits and deletions are never kept as history`() {
        assertTrue(history(listOf(ProfileUpdate("city", "Lahore")), snap(entry("city", "Karachi", edited = true))).isEmpty())
        assertTrue(history(listOf(ProfileUpdate("city", "Lahore")), snap(entry("city", "", deleted = true))).isEmpty())
    }

    @Test
    fun `a confirmed value that reflection may not replace leaves no history`() {
        val confirmed = entry("city", "Karachi (you confirmed)")
        assertTrue(history(listOf(ProfileUpdate("city", "Lahore")), snap(confirmed)).isEmpty())
    }

    @Test
    fun `the same history is not written twice`() {
        val already = Note(1, "thread", "Used to be true, city: Karachi", "guess", Note.RETIRED, 1, 0, 0, false)
        assertTrue(history(listOf(ProfileUpdate("city", "Lahore")), snap(entry("city", "Karachi"), notes = listOf(already))).isEmpty())
    }

    @Test
    fun `the planner puts history in the plan and the archive line says it used to be true`() {
        val plan = ReflectionPlanner.plan(
            ReflectionResult("s", profileUpdates = listOf(ProfileUpdate("city", "Lahore"))), snap(entry("city", "Karachi")), 9, now,
        )
        assertEquals(1, plan.history.size)
        val line = RelevantMemories.block(listOf(
            com.umair.purpose.data.db.SearchHit(SearchDocs.NOTE, "n1", "2026-10-04", plan.history.single().text),
        ))!!
        assertTrue(line, line.contains("used to be true, changed then: city: Karachi"))
    }
}

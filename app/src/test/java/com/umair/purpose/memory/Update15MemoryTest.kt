package com.umair.purpose.memory

import com.umair.purpose.data.db.Chapter
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.SearchHit
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.db.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** UPDATE-15: memory in layers, chapters, relevant memories and a context that stays the same size. */
class Update15MemoryTest {
    private val zone = ZoneId.of("Asia/Karachi")

    private fun note(id: Long, type: String, confidence: String = "guess", last: Long = id, seen: Int = 1, edited: Boolean = false) =
        Note(id = id, type = type, text = "note $id", confidence = confidence, status = Note.ACTIVE, timesSeen = seen, firstSeen = 0, lastSeen = last, editedByUser = edited)

    @Test
    fun `caps archive the weakest, never his edits`() {
        val patterns = (1L..18L).map { note(it, "pattern", if (it <= 3) "guess" else "likely") } + note(19, "pattern", "guess", edited = true)
        val over = MemoryCaps.overflow(patterns, emptyList(), emptyList())
        // 19 patterns, cap 15: the 4 weakest go (the three guesses he didn't write, then the oldest likely one).
        assertEquals(listOf(1L, 2L, 3L, 4L), over.notes.map { it.id })
        assertTrue(over.notes.all { it.status == Note.RETIRED })
        assertFalse(MemoryCaps.over((1L..15L).map { note(it, "pattern") }, emptyList(), emptyList()))

        val strengths = (1L..22L).map { Strength(id = it, sessionId = 1, text = "s$it", createdAt = it) }
        assertEquals(listOf(1L, 2L), MemoryCaps.overflow(emptyList(), strengths, emptyList()).strengths.map { it.id })
        val profile = (1..32).map { ProfileEntry(key = "k$it", value = "v", updatedAt = it.toLong(), editedByUser = it == 1) }
        assertEquals(listOf("k2", "k3"), MemoryCaps.overflow(emptyList(), emptyList(), profile).profile.map { it.key })
    }

    @Test
    fun `caps never archive a confirmed note`() {
        // CLAUDE.md "Corrections stick": a confirmed entry is protected, so the cap stays exceeded rather than
        // archiving something he agreed with. The confirmed note here is the oldest and least seen, so without
        // the guard it would be the first to go.
        val patterns = (1L..15L).map { note(it, "pattern") } + note(16, "pattern", "confirmed", last = 0, seen = 0)
        val over = MemoryCaps.overflow(patterns, emptyList(), emptyList())
        assertTrue("nothing confirmed is archived", over.notes.none { it.confidence == "confirmed" })
        assertEquals(listOf(1L), over.notes.map { it.id })
    }

    @Test
    fun `a chapter per finished quarter with real conversation`() {
        val first = LocalDate.of(2026, 8, 20)
        val due = ChapterPlanning.due(LocalDate.of(2027, 1, 1), first, emptyList()) { true }
        assertEquals(listOf(LocalDate.of(2026, 8, 20) to LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 12, 31)), due.map { it.start to it.end })
        val written = listOf(Chapter(periodStart = "2026-08-20", periodEnd = "2026-09-30", createdAt = 0, title = "t", content = "c"))
        assertEquals(1, ChapterPlanning.due(LocalDate.of(2027, 1, 1), first, written) { true }.size)
        assertTrue(ChapterPlanning.due(LocalDate.of(2027, 1, 1), first, emptyList()) { false }.isEmpty())
        assertTrue(ChapterPlanning.due(LocalDate.of(2026, 9, 15), first, emptyList()) { true }.isEmpty())
        assertEquals("October to December 2026", ChapterPlanning.quarterOf(LocalDate.of(2026, 11, 3)).label)
    }

    @Test
    fun `relevant memories need a strong match`() {
        val terms = RelevantMemories.terms("I'm so stressed about my FAR exam and Abbu keeps asking about it")
        assertTrue("abbu" in terms && "exam" in terms && "far" in terms)
        assertFalse("about" in terms || "my" in terms)
        assertEquals("stressed* OR asking* OR keeps* OR exam* OR abbu* OR far*", RelevantMemories.matchQuery(terms))
        val hits = listOf(
            SearchHit("summary", "3", "2024-02-01", "Talked about the FAR exam and how Abbu reacted to the result."),
            SearchHit("quote", "4:9", "2025-05-01", "\"Abbu never asks how I am\""),
            SearchHit("summary", "12", "2026-10-01", "This conversation, FAR exam and Abbu."),
        )
        val ranked = RelevantMemories.rank(hits, terms, excludeSession = 12)
        assertEquals(listOf("3"), ranked.map { it.refId })
        val block = RelevantMemories.block(ranked)!!
        assertTrue(block.startsWith("Possibly relevant from the past"))
        assertTrue(block.contains("2024-02-01, a conversation: Talked about the FAR exam"))
        // Nothing strong: nothing sent.
        assertNull(RelevantMemories.block(RelevantMemories.rank(hits.take(2), RelevantMemories.terms("weather today"), null)))
        assertNull(RelevantMemories.matchQuery(RelevantMemories.terms("ok so")))
    }

    @Test
    fun `the context stays within its budget, oldest summaries first`() {
        val big = "word ".repeat(2_000)
        val sessions = (1L..5L).map { Session(id = it, startedAt = it * 86_400_000L, summary = "Summary $it. $big", reflected = true) }
        val notes = (1L..40L).map { note(it, "pattern", if (it < 20) "guess" else "confirmed", seen = 1).copy(text = "pattern $it " + "x".repeat(400)) }
        val memory = Memory(emptyList(), emptyList(), notes, emptyList())
        val f = ContextFormatter.fitted(memory, ChatExtras(), sessions, zone, budget = 6_000)
        assertTrue(f.tokens <= 6_000)
        assertTrue(f.droppedSummaries >= 1)
        assertFalse(f.summaries.orEmpty().contains("Summary 1."))
        // Plenty of room: nothing dropped.
        val roomy = ContextFormatter.fitted(memory, ChatExtras(), sessions, zone, budget = 100_000)
        assertEquals(0, roomy.droppedSummaries)
        assertEquals(0, roomy.droppedNotes)
        // Very tight: notes go too, lowest confidence first.
        val tight = ContextFormatter.fitted(memory, ChatExtras(), sessions, zone, budget = 2_000)
        assertTrue(tight.droppedNotes > 0)
        assertFalse(tight.contextBlock.orEmpty().contains("pattern 1 "))
    }

    @Test
    fun `archived memory stays out of the context but in the archive`() {
        val m = Memory(
            listOf(ProfileEntry("Home", "Chitral", 1), ProfileEntry("Old job", "x", 1, retired = true)),
            emptyList(), emptyList(), emptyList(),
            strengths = listOf(Strength(1, 1, "You keep going", 1), Strength(2, 1, "Old strength", 1, retired = true)),
        )
        val ctx = ContextFormatter.chatContext(m, zone = zone)!!
        assertTrue(ctx.contains("Chitral"))
        assertFalse(ctx.contains("Old job"))
        assertFalse(ctx.contains("Old strength"))
        assertEquals("note", SearchDocs.retiredProfile(m.profile[1], zone)!!.kind)
        assertNull(SearchDocs.retiredProfile(m.profile[0], zone))
    }

    @Test
    fun `chapters go in the context`() {
        val c = Chapter(periodStart = "2026-07-01", periodEnd = "2026-09-30", createdAt = 0, title = "The FAR summer", content = "You started.")
        val ctx = ContextFormatter.chatContext(Memory(emptyList(), emptyList(), emptyList(), emptyList()), ChatExtras(chapters = listOf(c)), zone)!!
        assertTrue(ctx.contains("2026-07-01 to 2026-09-30: The FAR summer\nYou started."))
    }

    @Test
    fun `a tiny conversation gets a minimal summary, not an AI call`() {
        assertEquals("A short exchange; Umair only wrote: \"hey just checking in\"", ReflectionPlanner.minimalSummary(listOf("hey   just checking in")))
    }
    @Test fun `profile caps leave confirmed values active`() {
        val profile = (1..31).map { ProfileEntry("k$it", if (it == 1) "confirmed value (you confirmed)" else "v", it.toLong()) }
        assertEquals(listOf("k2"), MemoryCaps.overflow(emptyList(), emptyList(), profile).profile.map { it.key })
    }
    @Test fun `large protected profile and conversation summary cannot bypass the context budget`() {
        val value = "word ".repeat(40_000)
        val profile = listOf(ProfileEntry("Your history", value, 1, editedByUser = true))
        val memory = Memory(profile, emptyList(), emptyList(), emptyList())
        val fitted = ContextFormatter.fitted(memory, ChatExtras(), emptyList(), zone, budget = 100,
            extraSummaries = listOf(value))
        assertTrue(fitted.tokens <= 100)
        assertTrue(fitted.contextBlock!!.contains("details are omitted"))
        assertEquals(value, profile.single().value)
        val zero = ContextFormatter.fitted(memory, ChatExtras(), emptyList(), zone, budget = 0)
        assertEquals(0, zero.tokens)
    }

}

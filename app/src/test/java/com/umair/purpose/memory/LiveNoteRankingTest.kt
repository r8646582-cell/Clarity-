package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNoteRankingTest {
    private fun note(id: Long, text: String, conf: String = "guess", seen: Long = 1_000L, status: String = Note.ACTIVE) =
        Note(id, "pattern", text, conf, status, 1, 0, seen, false)

    @Test
    fun `without meaning matches the ranking is the old one`() {
        val notes = listOf(note(1, "avoids calling his manager"), note(2, "sleeps late"))
        val terms = listOf("manager")
        assertEquals(RelevantMemories.rankNotes(notes, terms), RelevantMemories.rankNotesHybrid(notes, emptyList(), terms))
    }

    @Test
    fun `a note found only by meaning is added after the keyword match`() {
        val keyword = note(1, "avoids calling his manager")
        val meaning = note(2, "dreads difficult conversations with people above him")
        val out = RelevantMemories.rankNotesHybrid(listOf(keyword), listOf(meaning to 0.6, keyword to 0.5), listOf("manager"))
        assertEquals(listOf(1L, 2L), out.map { it.id })
    }

    @Test
    fun `retired and deleted notes never come back through meaning`() {
        val gone = note(3, "old thing", status = Note.RETIRED)
        val out = RelevantMemories.rankNotesHybrid(emptyList(), listOf(gone to 0.9), listOf("thing"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `at most five notes are returned`() {
        val many = (1L..9L).map { note(it, "note $it about money") }
        assertEquals(5, RelevantMemories.rankNotesHybrid(emptyList(), many.map { it to 0.5 }, listOf("money")).size)
    }
}

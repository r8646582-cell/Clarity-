package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPDATE-21: the tokenized "RELEVANT INSIGHTS & REALIZATIONS" block for durable memories. */
class RelevantMemoriesTest {
    private fun note(id: Long, text: String) = Note(
        id = id, type = "pattern", text = text, confidence = "likely",
        status = Note.ACTIVE, timesSeen = 1, firstSeen = 0, lastSeen = 0,
    )

    @Test
    fun `no notes means no block`() {
        assertNull(RelevantMemories.insights(emptyList()))
    }

    @Test
    fun `notes are listed under the header`() {
        val block = RelevantMemories.insights(listOf(note(1, "you study best at night"), note(2, "Abbu respects honesty")))
        assertTrue(block!!.startsWith(RelevantMemories.INSIGHTS_HEADER))
        assertTrue(block.contains("- you study best at night"))
        assertTrue(block.contains("- Abbu respects honesty"))
    }

    @Test
    fun `terms ignores filler and keeps the meaningful words`() {
        val terms = RelevantMemories.terms("I feel so alone at night because of my studies")
        assertTrue(terms.contains("studies"))
        assertTrue(terms.contains("alone"))
        assertTrue(terms.none { it == "the" || it == "feel" })
    }
    @Test fun `insights preserve uncertainty and exclude deleted and retired notes`() {
        val text = RelevantMemories.insights(listOf(note(1, "Possible pattern").copy(confidence = "guess"),
            note(2, "Deleted secret").copy(status = Note.DELETED_BY_USER),
            note(3, "Retired guess").copy(status = Note.RETIRED)))!!
        assertTrue(text.contains("[guess]"))
        assertTrue(!text.contains("Deleted secret") && !text.contains("Retired guess"))
    }

    @Test fun `live retrieval ranks actual word matches over substring matches and stays bounded`() {
        val rows = (1L..20L).map { note(it, "Walking helps studying").copy(lastSeen = it) } + note(21, "You enjoy talking")
        val ranked = RelevantMemories.rankNotes(rows, listOf("walking", "studying"))
        org.junit.Assert.assertEquals(5, ranked.size)
        org.junit.Assert.assertEquals(20L, ranked.first().id)
        assertTrue(ranked.none { it.id == 21L })
        assertTrue(RelevantMemories.rankNotes(rows, emptyList()).isEmpty())
    }

}

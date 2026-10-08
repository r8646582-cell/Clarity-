package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GardeningTest {
    private fun note(id: Long, conf: String = "guess", seen: Int = 1, edited: Boolean = false, src: String = "") =
        Note(id, "pattern", "n$id", conf, Note.ACTIVE, seen, id * 10, id * 100, editedByUser = edited, sourceSessionIds = src)

    @Test
    fun mergesIntoTheOldestNoteWithSummedCountsAndHighestConfidence() {
        val raw = """{"merge":[{"ids":[4,1],"text":"Studying feels heavy at night","type":"pattern","confidence":"guess"}]}"""
        val plan = Gardening.plan(Gardening.parse(raw), listOf(note(1, "guess", 2, src = "3"), note(4, "likely", 3, src = "8")), emptyList())
        val kept = plan.notes.first { it.id == 1L }
        assertEquals("Studying feels heavy at night", kept.text)
        assertEquals("likely", kept.confidence)
        assertEquals(5, kept.timesSeen)
        assertEquals(10L, kept.firstSeen)
        assertEquals(400L, kept.lastSeen)
        assertEquals("3,8", kept.sourceSessionIds)
        assertEquals(Note.RETIRED, plan.notes.first { it.id == 4L }.status)
    }

    @Test
    fun neverTouchesWhatHeEdited() {
        val raw = """{"merge":[{"ids":[1,2],"text":"x"}],"rewrite":[{"id":2,"text":"y"}],"retire":[{"id":2,"reason":"old"}]}"""
        val plan = Gardening.plan(Gardening.parse(raw), listOf(note(1), note(2, edited = true)), emptyList())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun neverTouchesAConfirmedNote() {
        // CLAUDE.md "Corrections stick": gardening may not change a confirmed entry either. The merge group here
        // contains one, so the whole group is skipped rather than partly applied.
        val raw = """{"merge":[{"ids":[1,2],"text":"x"}],"rewrite":[{"id":2,"text":"y"}],"retire":[{"id":2,"reason":"old"}]}"""
        val plan = Gardening.plan(Gardening.parse(raw), listOf(note(1), note(2, "confirmed")), emptyList())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun rewritesAndRetiresOncePerNote() {
        val raw = """{"rewrite":[{"id":1,"text":"clearer"}],"retire":[{"id":1,"reason":"x"},{"id":2,"reason":"gone"},{"id":99}]}"""
        val plan = Gardening.plan(Gardening.parse(raw), listOf(note(1), note(2)), emptyList())
        assertEquals("clearer", plan.notes.first { it.id == 1L }.text)
        assertEquals(Note.ACTIVE, plan.notes.first { it.id == 1L }.status)
        assertEquals(Note.RETIRED, plan.notes.first { it.id == 2L }.status)
        assertEquals(2, plan.notes.size)
    }

    @Test
    fun mergesStrengthsButNotHisOwn() {
        val s = listOf(Strength(1, 1, "honest", 0), Strength(2, 1, "truthful", 0), Strength(3, 1, "kind", 0, editedByUser = true))
        val plan = Gardening.plan(
            Gardening.parse("""{"merge_strengths":[{"ids":[1,2],"text":"Honest, even when it costs him"},{"ids":[1,3],"text":"z"}]}"""),
            emptyList(), s,
        )
        assertEquals("Honest, even when it costs him", plan.strengths.single().text)
        assertEquals(listOf(2L), plan.deleteStrengthIds)
    }
    @Test fun `unknown provenance survives merges and cannot be forgotten as a single known source`() {
        val sources = Provenance.merge(listOf("", "3"))
        assertEquals(setOf(0L, 3L), Provenance.parse(sources))
        assertTrue(!Provenance.onlyFrom(sources, 3))
        assertEquals("0", Provenance.remove(sources, 3))
    }

    @Test fun `strengths from different conversations are preserved rather than losing their sources`() {
        val result = GardeningResult(mergeStrengths = listOf(GardeningResult.MergeStrengths(listOf(1, 2), "Combined")))
        val plan = Gardening.plan(result, emptyList(), listOf(Strength(1, 10, "Honesty", 0), Strength(2, 20, "Courage", 0)))
        assertTrue(plan.isEmpty)
    }

    @Test fun `reinforcing legacy memory cannot turn its unknown history into a single removable source`() {
        assertEquals("0,3", Provenance.extend("", 3))
        assertTrue(!Provenance.onlyFrom(Provenance.extend("", 3), 3))
        assertEquals("3,7", Provenance.extend("7", 3))
    }

}

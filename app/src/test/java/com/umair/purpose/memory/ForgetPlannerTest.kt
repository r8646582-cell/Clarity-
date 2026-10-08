package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgetPlannerTest {
    private fun note(id: Long, src: String, edited: Boolean = false, status: String = Note.ACTIVE) =
        Note(id, "pattern", "n$id", "guess", status, 1, 0, 0, editedByUser = edited, sourceSessionIds = src)

    @Test
    fun deletesWhatCameOnlyFromThatConversation() {
        val plan = ForgetPlanner.plan(
            5,
            notes = listOf(note(1, "5"), note(2, "3,5"), note(3, "3"), note(4, ""), note(5, "5", edited = true)),
            profile = listOf(ProfileEntry("job", "x", 0, sourceSessionIds = "5"), ProfileEntry("city", "y", 0, sourceSessionIds = "5,9")),
            people = listOf(Person(1, "Ali", updatedAt = 0, sourceSessionIds = "5"), Person(2, "Sara", updatedAt = 0)),
        )
        assertEquals(listOf(1L), plan.deleteNoteIds)
        // Shared and edited notes stay, without the link.
        assertEquals(listOf(2L to "3", 5L to ""), plan.updateNotes.map { it.id to it.sourceSessionIds })
        assertEquals(listOf("job"), plan.deleteProfileKeys)
        assertEquals("9", plan.updateProfile.single().sourceSessionIds)
        assertEquals(listOf(1L), plan.deletePeopleIds)
        assertTrue(plan.updatePeople.isEmpty())
    }

    @Test
    fun keepsHisDeletionTombstones() {
        val plan = ForgetPlanner.plan(5, listOf(note(1, "5", status = Note.DELETED_BY_USER)), emptyList(), emptyList())
        assertTrue(plan.deleteNoteIds.isEmpty())
        assertEquals(1L, plan.updateNotes.single().id)
    }

    @Test
    fun provenanceRoundTrips() {
        assertEquals("3,5,12", Provenance.add("12,3", 5))
        assertEquals("3", Provenance.remove("3,5", 5))
        assertTrue(Provenance.onlyFrom("5", 5))
        assertEquals(emptySet<Long>(), Provenance.parse(""))
    }
}

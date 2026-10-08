package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry

/** What "Forget this conversation" removes from memory, and what it only unlinks. */
data class ForgetPlan(
    val deleteNoteIds: List<Long> = emptyList(),
    val updateNotes: List<Note> = emptyList(),
    val deleteProfileKeys: List<String> = emptyList(),
    val updateProfile: List<ProfileEntry> = emptyList(),
    val deletePeopleIds: List<Long> = emptyList(),
    val updatePeople: List<Person> = emptyList(),
)

/**
 * Anything learned only from the forgotten conversation goes. Anything that also came from other conversations
 * just loses this one. What he edited or deleted himself is his and stays (minus the link). Rows with no known
 * source (from before provenance existed) are kept.
 */
object ForgetPlanner {
    fun plan(sessionId: Long, notes: List<Note>, profile: List<ProfileEntry>, people: List<Person>): ForgetPlan {
        fun linked(ids: String) = sessionId in Provenance.parse(ids)
        fun goes(ids: String, his: Boolean) = !his && Provenance.onlyFrom(ids, sessionId)

        val n = notes.filter { linked(it.sourceSessionIds) }
        val (noteGone, noteKept) = n.partition { goes(it.sourceSessionIds, it.editedByUser || it.status == Note.DELETED_BY_USER) }
        val p = profile.filter { linked(it.sourceSessionIds) }
        val (profileGone, profileKept) = p.partition { goes(it.sourceSessionIds, it.editedByUser || it.deletedByUser) }
        val pe = people.filter { linked(it.sourceSessionIds) }
        val (peopleGone, peopleKept) = pe.partition { goes(it.sourceSessionIds, it.editedByUser || it.deletedByUser) }

        return ForgetPlan(
            deleteNoteIds = noteGone.map { it.id },
            updateNotes = noteKept.map { it.copy(sourceSessionIds = Provenance.remove(it.sourceSessionIds, sessionId)) },
            deleteProfileKeys = profileGone.map { it.key },
            updateProfile = profileKept.map { it.copy(sourceSessionIds = Provenance.remove(it.sourceSessionIds, sessionId)) },
            deletePeopleIds = peopleGone.map { it.id },
            updatePeople = peopleKept.map { it.copy(sourceSessionIds = Provenance.remove(it.sourceSessionIds, sessionId)) },
        )
    }
}

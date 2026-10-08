package com.umair.purpose.memory

import com.umair.purpose.data.db.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitemporalNoteTest {
    private fun note(validTo: Long? = null) = Note(
        id = 1, type = "pattern", text = "Walks at dawn", confidence = "likely", status = Note.ACTIVE,
        timesSeen = 2, firstSeen = 100, lastSeen = 500, validTo = validTo,
    )

    @Test
    fun `retiring closes validity and keeps the note`() {
        val n = note().retiredAt(900)
        assertEquals(Note.RETIRED, n.status)
        assertEquals(900L, n.validTo)
        assertEquals("Walks at dawn", n.text)
    }

    @Test
    fun `the first close wins`() {
        assertEquals(700L, note(validTo = 700).retiredAt(900).validTo)
    }

    @Test
    fun `cap overflow closes validity for archived notes and profile lines`() {
        val notes = (1..40).map { note().copy(id = it.toLong(), lastSeen = it.toLong()) }
        val out = MemoryCaps.overflow(notes, emptyList(), emptyList(), now = 1234)
        assertEquals(true, out.notes.isNotEmpty())
        out.notes.forEach { assertEquals(1234L, it.validTo) }
        assertNull(notes.first().validTo)
    }
}

package com.umair.purpose.ui.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPDATE-11: an even stream of whole words, never far behind, finished quickly at the end. */
class RevealPacerTest {
    private val frame = 1.0 / 60

    private fun words(n: Int) = (1..n).joinToString(" ") { "word$it" }

    @Test
    fun `stops only at whole words`() {
        val p = RevealPacer()
        val text = "Hello there, frie"
        repeat(600) {
            val shown = p.step(text, done = false, dtSeconds = frame)
            assertTrue(shown == 0 || shown == text.length || text[shown].isWhitespace())
        }
        // The last word is still arriving: it waits for the rest.
        assertEquals("Hello there,", text.take(p.shown))
    }

    @Test
    fun `small backlog moves at the base rate`() {
        val p = RevealPacer()
        val text = "a b c d e f g h i j k l " // 23 characters to show: under 0.4 s at the base rate
        // A fifth of a second at about 60 characters a second.
        repeat(12) { p.step(text, done = false, dtSeconds = frame) }
        assertTrue("shown ${p.shown}", p.shown in 10..16)
    }

    @Test
    fun `a burst is caught up within about 0_4 seconds, smoothly`() {
        val p = RevealPacer()
        val text = words(200) + " " // ~1400 characters arrive at once
        var last = 0
        var biggest = 0
        repeat(30) { // half a second
            val now = p.step(text, done = false, dtSeconds = frame)
            biggest = maxOf(biggest, now - last)
            last = now
        }
        val avail = p.available(text, false)
        // After half a second it's mostly there, and no single frame jumped by a large block.
        assertTrue("shown ${p.shown} of $avail", p.shown > avail * 0.6)
        assertTrue("biggest step $biggest", biggest < avail / 5)
    }

    @Test
    fun `when the stream ends the rest finishes within half a second`() {
        val p = RevealPacer()
        val text = words(60)
        var frames = 0
        while (p.shown < text.length && frames < 1000) {
            p.step(text, done = true, dtSeconds = frame)
            frames++
        }
        assertEquals(text.length, p.shown)
        assertTrue("took $frames frames", frames <= 33)
    }

    @Test
    fun `never goes backwards while text grows`() {
        val p = RevealPacer()
        val full = words(80)
        var last = 0
        for (cut in 5..full.length step 7) {
            repeat(3) {
                val now = p.step(full.take(cut), done = false, dtSeconds = frame)
                assertTrue(now >= last)
                last = now
            }
        }
    }

    @Test
    fun `starts over when the text shrinks (silent fallback)`() {
        val p = RevealPacer()
        repeat(60) { p.step("Half an answer that ", done = false, dtSeconds = frame) }
        assertTrue(p.shown > 0)
        assertTrue(p.hasWork("", false))
        assertEquals(0, p.step("", done = false, dtSeconds = frame))
    }

    @Test
    fun `nothing to do once caught up`() {
        val p = RevealPacer(initialShown = 11)
        assertFalse(p.hasWork("Done reply.", done = true))
        assertTrue(p.hasWork("Done reply. More", done = true))
    }

    @Test
    fun `paragraph ranges match the old split`() {
        val text = "  First line.\n\n\nSecond one\nstill second.\n \nThird"
        val ranges = RevealPacer.paragraphs(text)
        assertEquals(listOf("First line.", "Second one\nstill second.", "Third"), ranges.map { text.substring(it) })
        assertEquals(emptyList<IntRange>(), RevealPacer.paragraphs("  \n\n "))
    }
}

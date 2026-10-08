package com.umair.purpose.ledger

import com.umair.purpose.data.db.Contradiction
import com.umair.purpose.data.db.Disagreement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRulesTest {
    private val his = listOf(
        HisMessage(sessionId = 1, createdAt = 1000, text = "I really do want to be home by six every night."),
        HisMessage(sessionId = 2, createdAt = 5000, text = "Honestly I stayed at the office until nine again, it's fine."),
        HisMessage(sessionId = 2, createdAt = 6000, text = "No, I don't think I'm avoiding the exam."),
    )

    @Test
    fun `a disagreement needs his position word for word`() {
        val ok = LedgerRules.disagreement(
            LedgerRules.ProposedDisagreement("You are avoiding the exam.", "I don't think I'm avoiding the exam"), his, 2, 9000, emptyList(),
        )
        assertNotNull(ok)
        assertEquals(6000L, ok!!.raisedAt)
        assertEquals("You are avoiding the exam.", ok.claim)
    }

    @Test
    fun `a paraphrased position is dropped`() {
        val p = LedgerRules.ProposedDisagreement("You are avoiding the exam.", "He denies he is avoiding it")
        assertNull(LedgerRules.disagreement(p, his, 2, 9000, emptyList()))
    }

    @Test
    fun `his position cannot be quoted from something only the coach said`() {
        val p = LedgerRules.ProposedDisagreement("You work too late.", "You work too late.")
        assertNull(LedgerRules.disagreement(p, his, 2, 9000, emptyList()))
    }

    @Test
    fun `the same open claim is not added twice`() {
        val first = LedgerRules.disagreement(
            LedgerRules.ProposedDisagreement("You are avoiding the exam.", "I don't think I'm avoiding the exam"), his, 2, 9000, emptyList(),
        )!!
        val again = LedgerRules.disagreement(
            LedgerRules.ProposedDisagreement("you are avoiding the EXAM.", "I don't think I'm avoiding the exam"), his, 2, 9000, listOf(first),
        )
        assertNull(again)
    }

    @Test
    fun `a contradiction pairs two different messages of his, oldest first`() {
        val c = LedgerRules.contradiction(
            LedgerRules.ProposedContradiction("I stayed at the office until nine again", "I really do want to be home by six every night"), his, 9000, emptyList(),
        )
        assertNotNull(c)
        assertEquals("I really do want to be home by six every night", c!!.quoteA)
        assertEquals(1000L, c.statedAtA)
        assertEquals(5000L, c.statedAtB)
        assertEquals(2L, c.sessionId)
        assertEquals(1L, c.sessionIdA)
        assertEquals(Contradiction.OPEN, c.status)
    }

    @Test
    fun `a contradiction with a quote he did not write is dropped`() {
        val c = LedgerRules.contradiction(
            LedgerRules.ProposedContradiction("I want to be home by six every night", "I never want to go home"), his, 9000, emptyList(),
        )
        assertNull(c)
    }

    @Test
    fun `two quotes from one message are not a contradiction`() {
        val c = LedgerRules.contradiction(
            LedgerRules.ProposedContradiction("I stayed at the office until nine", "it's fine"), his, 9000, emptyList(),
        )
        assertNull(c)
    }

    @Test
    fun `the same pair is registered once`() {
        val p = LedgerRules.ProposedContradiction("I stayed at the office until nine again", "I really do want to be home by six every night")
        val first = LedgerRules.contradiction(p, his, 9000, emptyList())!!
        assertNull(LedgerRules.contradiction(p, his, 9100, listOf(first)))
    }

    @Test
    fun `lines show open items only with both sides quoted`() {
        val d = Disagreement(1, 2, "You are avoiding the exam.", "I'm not avoiding it", 6000)
        val done = d.copy(id = 2, claim = "resolved one", resolved = true)
        val c = Contradiction(1, 2, 1, "home by six", "office until nine", 1000, 5000, createdAt = 9000)
        val explained = c.copy(id = 2, status = Contradiction.EXPLAINED, quoteA = "old", quoteB = "older")
        val lines = LedgerRules.lines(listOf(d, done), listOf(c, explained)) { "day$it" }
        val text = lines.joinToString("\n")
        assertTrue(text.contains("You are avoiding the exam."))
        assertTrue(text.contains("I'm not avoiding it"))
        assertTrue(text.contains("\"home by six\" versus day5000 \"office until nine\""))
        assertTrue(!text.contains("resolved one"))
        assertTrue(!text.contains("older"))
    }

    @Test
    fun `no open items means no lines`() {
        assertTrue(LedgerRules.lines(emptyList(), emptyList()) { "" }.isEmpty())
    }
}

package com.umair.purpose.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteCheckTest {
    private val his = listOf(
        "honestly i think i\u2019m just not built for this",
        "I opened the book and   closed it again",
    )

    @Test fun `an exact phrase is accepted, ignoring case, spacing and curly quotes`() {
        assertTrue(QuoteCheck.isHis("I'm just not built for this", his))
        assertTrue(QuoteCheck.isHis("\"opened the book and closed it\"", his))
    }

    @Test fun `a paraphrase is rejected`() {
        assertFalse(QuoteCheck.isHis("I don't think I'm cut out for this", his))
    }

    @Test fun `very short fragments are rejected`() {
        assertFalse(QuoteCheck.isHis("no", listOf("no")))
    }

    @Test fun `verified keeps only his words in order`() {
        val out = QuoteCheck.verified(listOf("not built for this", "made up line about fear", "closed it again"), his)
        assertEquals(listOf("not built for this", "closed it again"), out)
    }
}

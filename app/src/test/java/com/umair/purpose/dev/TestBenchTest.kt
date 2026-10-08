package com.umair.purpose.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestBenchTest {
    private val sample = """
        # Test bench — Purpose
        Intro text. Format: "## name", then "Mode:", then "User:", then "Expect:".

        ## Landing an action
        Mode: normal
        User: i keep scrolling
        User: ok what should i do
        Expect: Proposes ONE small action.
        No [[promise]] line until he agrees.

        ## Untangle
        Mode: untangle
        User: just tell me
        Expect: Does NOT decide.

        ## Odd mode
        Mode: sideways
        User: hi
        Expect: Short.
    """.trimIndent()

    @Test
    fun parsesScenarios() {
        val s = TestBench.parse(sample)
        assertEquals(listOf("Landing an action", "Untangle", "Odd mode"), s.map { it.name })
        assertEquals(listOf("i keep scrolling", "ok what should i do"), s[0].userLines)
        assertEquals("Proposes ONE small action. No [[promise]] line until he agrees.", s[0].expect)
        assertEquals("untangle", s[1].mode)
        assertEquals("normal", s[2].mode)
    }

    @Test
    fun parsesTheRealFile() {
        val file = listOf("src/main/assets/prompts/testbench.md", "app/src/main/assets/prompts/testbench.md").map(::File).first { it.exists() }
        val s = TestBench.parse(file.readText())
        assertTrue(s.size >= 10)
        assertTrue(s.all { it.userLines.isNotEmpty() && it.expect.isNotEmpty() && it.mode in TestBench.MODES })
        // UPDATE-19: the time-awareness scenario carries its setup, and it never leaks into what he says.
        val time = s.first { it.name == "Time awareness" }
        assertTrue(time.setup!!.contains("Study 1 hour"))
        assertTrue(time.userLines == listOf("what's coming up for me?"))
    }

    @Test
    fun reportHasVerdicts() {
        val sc = TestBench.parse(sample).first()
        val text = TestBench.report(listOf(ScenarioRun(sc, listOf("a", "b"), null, true)))
        assertTrue(text.contains("Purpose: b"))
        assertTrue(text.contains("Verdict: PASS"))
    }
}

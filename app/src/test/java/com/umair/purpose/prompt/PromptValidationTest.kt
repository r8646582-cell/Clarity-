package com.umair.purpose.prompt

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PromptValidationTest {
    @Test fun `legacy markers remain forbidden with whitespace and mixed case`() {
        for (name in listOf("persona.md", "mode_decision.md", "mode_journey.md", "mode_practice.md", "mode_untangle.md", "mode_onboarding.md", "reflection.md")) {
            for (marker in listOf("[[promise: x]]", "[[Promise : x]]", "[[ action : {}]]")) {
                assertTrue("$name: $marker", PromptValidation.errors(name, marker, "voice").isNotEmpty())
            }
            assertTrue(PromptValidation.errors(name, "Use due_epoch_ms", "voice").isNotEmpty())
            assertTrue(PromptValidation.errors(name, "<<<TOOL_CALL {\"action\":\"edit_promise\",\"new_due\":\"today 02:30\"} >>>", "voice").isEmpty())
        }
    }
    @Test fun `bundled prompts satisfy the edit contract`() {
        val root = File("src/main/assets/prompts").takeIf { it.exists() } ?: File("app/src/main/assets/prompts")
        val files = root.listFiles()!!.filter { it.extension == "md" }
        assertTrue(files.size >= 18)
        files.forEach { f -> assertEquals(f.name, emptyList<String>(), PromptValidation.errors(f.name, f.readText(), f.readText())) }
    }

    @Test fun `blank edit is rejected`() { assertTrue(PromptValidation.errors("persona.md", "  ", "voice").isNotEmpty()) }
    @Test fun `removed input and unknown field are rejected`() {
        val errors = PromptValidation.errors("reflection.md", "{{CONTEX}}", "{{CONTEXT}}")
        assertTrue(errors.any { "Keep" in it }); assertTrue(errors.any { "Unknown" in it })
    }
    @Test fun `unclosed field is rejected`() { assertTrue(PromptValidation.errors("persona.md", "{{DATE", "voice").isNotEmpty()) }
    @Test fun `new prose and repeated known fields are allowed`() {
        assertTrue(PromptValidation.errors("reflection.md", "Better instructions {{CONTEXT}} {{CONTEXT}}", "{{CONTEXT}}").isEmpty())
    }
    @Test fun `missing and duplicate journey days are rejected`() {
        for (days in listOf("Day 2 | t | e | a", "Day 1 | t | e | a\nDay 1 | t | e | a")) {
            assertTrue(PromptValidation.errors("journeys.md", "## Name\nDescription\n$days", "").isNotEmpty())
        }
    }
    @Test fun `malformed journey cannot disappear silently`() {
        assertTrue(PromptValidation.errors("journeys.md", "## Name\nDay 1 | t | e", "").isNotEmpty())
        assertTrue(PromptValidation.errors("journeys.md", "## Name\nDay 1 | t | e | a\nDay 2 malformed", "").isNotEmpty())
        assertTrue(PromptValidation.errors("journeys.md", "## \nDay 1 | t | e | a", "").isNotEmpty())
    }
    @Test fun `overflow journey day gives validation error`() {
        assertTrue(PromptValidation.errors("journeys.md", "## Name\nDay 999999999999 | t | e | a", "").isNotEmpty())
    }
    @Test fun `bench requires expectations and valid modes`() {
        for (text in listOf("## Scenario\nUser: hi", "## Scenario\nMode: typo\nUser: hi\nExpect: greeting")) {
            assertTrue(PromptValidation.errors("testbench.md", text, "").isNotEmpty())
        }
    }
}

package com.umair.purpose.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression guard for contradictions that structural template validation cannot detect. */
class PromptContractTest {
    private fun promptRoot(): File =
        File("src/main/assets/prompts").takeIf { it.exists() } ?: File("app/src/main/assets/prompts")

    @Test fun `active coaching prompts never teach legacy promise markers`() {
        val forbidden = Regex("\\[\\[promise\\s*:", RegexOption.IGNORE_CASE)
        val active = listOf("persona.md", "mode_decision.md", "mode_journey.md", "mode_practice.md", "mode_untangle.md", "mode_onboarding.md")
        active.forEach { name ->
            assertFalse("$name must use the canonical TOOL_CALL promise protocol", forbidden.containsMatchIn(File(promptRoot(), name).readText()))
        }
    }

    @Test fun `active prompts never instruct the model to generate epoch milliseconds`() {
        val active = listOf("persona.md", "mode_decision.md", "mode_journey.md", "mode_practice.md", "reflection.md")
        active.forEach { name ->
            val text = File(promptRoot(), name).readText()
            assertFalse("$name contains deprecated epoch field", "new_due_epoch_ms" in text)
            assertFalse("$name contains deprecated epoch field", "due_epoch_ms" in text)
        }
    }

    @Test fun `persona establishes receipt authority and uncertainty discipline`() {
        val text = File(promptRoot(), "persona.md").readText().lowercase()
        assertTrue("persona must make receipts authoritative", "receipt" in text && "success" in text)
        assertTrue("persona must distinguish hypotheses", "hypoth" in text)
        assertTrue("persona must not force action", "do not force" in text)
    }
}

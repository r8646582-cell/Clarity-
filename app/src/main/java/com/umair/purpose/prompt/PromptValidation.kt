package com.umair.purpose.prompt

import com.umair.purpose.dev.TestBench
import com.umair.purpose.journey.JourneyCatalog

/** Checks an edited prompt before replacing a working version. Does not rewrite its wording. */
object PromptValidation {
    private val field = Regex("\\{\\{([^{}]*)}}")

    fun errors(name: String, text: String, builtIn: String): List<String> = buildList {
        if (text.isBlank()) add("The prompt cannot be empty.")
        val required = field.findAll(builtIn).map { it.groupValues[1] }.toSet()
        val actual = field.findAll(text).map { it.groupValues[1] }.toSet()
        val missing = required - actual
        val unknown = actual - required
        if (missing.isNotEmpty()) add("Keep these fields: " + missing.sorted().joinToString { "{{$it}}" })
        if (unknown.isNotEmpty()) add("Unknown fields: " + unknown.sorted().joinToString { "{{$it}}" })
        val withoutFields = field.replace(text, "")
        if ("{{" in withoutFields || "}}" in withoutFields) add("A template field has unmatched braces.")
        // Promise mutations have one canonical protocol. Reject prompt edits that reintroduce legacy syntax
        // or model-side epoch arithmetic and silently conflict with ToolSchemas.
        if (name in setOf("persona.md", "mode_decision.md", "mode_journey.md", "mode_practice.md", "mode_untangle.md", "mode_onboarding.md", "reflection.md")) {
            if (Regex("\\[\\[\\s*promise\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(text)) add("Legacy [[promise: ...]] syntax is not allowed; use canonical TOOL_CALL actions.")
            if (Regex("\\[\\[\\s*action\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(text)) add("Legacy [[action: ...]] syntax is not allowed; use canonical TOOL_CALL actions.")
            if (listOf("new_due_epoch_ms", "due_epoch_ms", "reminder_epoch").any { text.contains(it, ignoreCase = true) }) add("Do not ask the model to calculate epoch milliseconds; use local date/time strings.")
        }
        val headings = text.lineSequence().count { it.trim().startsWith("## ") }
        if (name == "journeys.md") {
            val plans = runCatching { JourneyCatalog.parse(text) }.getOrElse {
                add("Journey days must use valid whole numbers.")
                emptyList()
            }
            val dayLines = text.lineSequence().count { Regex("^Day\\s+").containsMatchIn(it.trim()) }
            if (plans.isEmpty() || plans.size != headings || plans.any { it.name.isBlank() } || plans.sumOf { it.days } != dayLines) {
                add("Every journey needs a name and Day N | theme | explore | action lines.")
            }
            if (plans.map { it.name.lowercase() }.distinct().size != plans.size) add("Journey names must be unique.")
            if (plans.any { p -> p.steps.map { it.day } != (1..p.days).toList() || p.steps.any { it.theme.isBlank() || it.explore.isBlank() || it.action.isBlank() } }) {
                add("Journey days must start at 1, be consecutive, and have a theme, exploration and action.")
            }
        }
        if (name == "testbench.md") {
            val scenarios = TestBench.parse(text)
            if (scenarios.isEmpty() || scenarios.size != headings || scenarios.any { it.name.isBlank() || it.expect.isBlank() || it.userLines.any(String::isBlank) }) {
                add("Every scenario needs a name, a nonempty User: line and Expect: line.")
            }
            val modes = text.lineSequence().map { it.trim() }.filter { it.startsWith("Mode:", ignoreCase = true) }
            if (modes.any { it.substringAfter(':').trim().substringBefore(' ').substringBefore('(').lowercase() !in TestBench.MODES }) {
                add("Use a supported test bench mode.")
            }
        }
    }
}

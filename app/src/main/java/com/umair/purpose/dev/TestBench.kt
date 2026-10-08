package com.umair.purpose.dev

/**
 * One scenario from prompts/testbench.md. [step]: the onboarding step ("values"), from "Mode: onboarding (values
 * step)"; [sampleValues]: from "with sample values: a, b, c", used instead of his own.
 */
data class Scenario(
    val name: String,
    val mode: String,
    val userLines: List<String>,
    val expect: String,
    val step: String? = null,
    val sampleValues: List<String> = emptyList(),
    /** UPDATE-19: a "(Test setup: …)" line, the facts this scenario assumes (a time, a promise, a profile line). */
    val setup: String? = null,
)

/** Tokens one bench request used, for the cost per run (UPDATE-17 "Compare models"). */
data class BenchUsage(val model: String, val cacheHit: Long, val cacheMiss: Long, val output: Long)

/** A verdict line for "Copy report". */
data class ScenarioRun(val scenario: Scenario, val replies: List<String>, val error: String?, val verdict: Boolean?)

object TestBench {
    /** The modes testbench.md uses. "normal" and "listen" are plain chats (listen = listen_only on). */
    val MODES = setOf("normal", "listen", "untangle", "decision", "practice", "journey", "onboarding")

    /** UPDATE-14: replies should ask at most one question; the bench shows the count next to each reply. */
    fun questions(reply: String): Int = reply.count { it == '?' || it == '؟' }

    /** "onboarding (values step) with sample values: family, knowledge" → (onboarding, values, [family, knowledge]). */
    internal fun parseMode(raw: String): Triple<String, String?, List<String>> {
        val t = raw.trim().lowercase()
        val mode = t.substringBefore('(').substringBefore(' ').trim().takeIf { it in MODES } ?: "normal"
        val step = Regex("""\(\s*([a-z_ ]+?)\s+step\s*\)""").find(t)?.groupValues?.get(1)?.trim()?.replace(' ', '_')
        val values = Regex("""values?\s*:\s*(.+)$""").find(raw.trim().lowercase())?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trimEnd('.') }?.filter { it.isNotEmpty() }.orEmpty()
        return Triple(mode, step, values)
    }

    /**
     * Parses "## name", then "Mode:", one or more "User:" lines, then "Expect:" (which may continue on the
     * following lines). Anything before the first "##" is the file's introduction and is skipped.
     */
    fun parse(markdown: String): List<Scenario> {
        val out = mutableListOf<Scenario>()
        var name: String? = null
        var mode = "normal"
        var step: String? = null
        var values = emptyList<String>()
        var setup: String? = null
        val users = mutableListOf<String>()
        val expect = StringBuilder()
        var inExpect = false

        fun flush() {
            val n = name ?: return
            if (users.isNotEmpty()) out += Scenario(n, mode, users.toList(), expect.toString().trim(), step, values, setup)
        }

        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    flush()
                    name = line.removePrefix("## ").trim()
                    mode = "normal"; step = null; values = emptyList(); setup = null; users.clear(); expect.clear(); inExpect = false
                }
                name == null -> Unit
                line.startsWith("Mode:", ignoreCase = true) -> {
                    val (m, st, v) = parseMode(line.substringAfter(':'))
                    mode = m; step = st; values = v
                    inExpect = false
                }
                line.startsWith("(Test setup:", ignoreCase = true) -> {
                    setup = line.substringAfter(':').trim().removeSuffix(")").trim()
                    inExpect = false
                }
                line.startsWith("User:", ignoreCase = true) -> {
                    users += line.substringAfter(':').trim()
                    inExpect = false
                }
                line.startsWith("Expect:", ignoreCase = true) -> {
                    expect.clear().append(line.substringAfter(':').trim())
                    inExpect = true
                }
                inExpect && line.isNotEmpty() -> expect.append(' ').append(line)
            }
        }
        flush()
        return out
    }

    /**
     * USD for one whole run: each request priced by [priceOf] its model (null = unknown price, counted as 0
     * and the result marked incomplete). Off-peak is ignored, so two runs compare fairly.
     */
    fun runCost(usage: List<BenchUsage>, priceOf: (String) -> com.umair.purpose.cost.Prices?): com.umair.purpose.cost.CostEstimate {
        var usd = 0.0
        var complete = true
        usage.forEach { u ->
            val p = priceOf(u.model)?.takeIf { it.known }
            if (p == null) complete = false
            else usd += (u.cacheHit * p.cacheHit + u.cacheMiss * p.cacheMiss + u.output * p.output) / 1_000_000.0
        }
        return com.umair.purpose.cost.CostEstimate(usd, complete)
    }

    /** Scenario, each message and reply, the expectation and his verdict, as plain text. */
    fun report(runs: List<ScenarioRun>): String = buildString {
        append("Purpose test bench\n\n")
        runs.forEach { r ->
            append("## ").append(r.scenario.name).append(" (").append(r.scenario.mode).append(")\n")
            r.scenario.userLines.forEachIndexed { i, u ->
                append("User: ").append(u).append('\n')
                r.replies.getOrNull(i)?.let { append("Purpose: ").append(it).append('\n').append("(questions: ").append(questions(it)).append(")\n") }
            }
            r.error?.let { append("Error: ").append(it).append('\n') }
            append("Expect: ").append(r.scenario.expect).append('\n')
            append("Verdict: ").append(
                when (r.verdict) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "not judged"
                }
            ).append("\n\n")
        }
    }.trimEnd() + "\n"
}

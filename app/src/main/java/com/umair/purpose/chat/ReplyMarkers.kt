package com.umair.purpose.chat

import com.umair.purpose.memory.OnboardingTopic
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * The coach ends a reply with hidden lines when he agrees to something:
 * `[[promise: text | due: YYYY-MM-DDTHH:MM or none | remind: YYYY-MM-DDTHH:MM or none | why: text]]`
 * `[[mode: practice | with: Abbu]]`, `[[mode: decision]]`, `[[mode: journey | name: Break the avoidance loop]]`
 * `[[practice_with: Abbu]]`, `[[step_done: story]]`
 *
 * While streaming nothing from `[[` onward is shown; when the reply completes the lines are parsed and removed.
 * Kinds and keys are read in any case, with extra spaces or line breaks inside; several lines in one reply are
 * all handled. A line that can't be read is still hidden, and counted in [Parsed.failures] for the log.
 */
object ReplyMarkers {

    data class PromiseLine(val text: String, val due: String?, val remindAt: LocalDateTime?, val why: String?)

    data class ModeLine(val mode: Mode?, val with: String?, val journeyName: String?)

    data class Parsed(
        /** The reply as he sees it and as stored. */
        val visible: String,
        val promises: List<PromiseLine>,
        val mode: ModeLine?,
        /** A `[[promise` or `[[mode` line was there but couldn't be read. Nothing is saved for it. */
        val failures: Int,
        /** `[[practice_with: Abbu]]`: who he's practicing with, for the header. Already a clean short name. */
        val practiceWith: String? = null,
        /** `[[step_done: story]]`: the onboarding steps the coach closed in this reply, as stored step names. */
        val stepsDone: List<String> = emptyList(),
        /** UPDATE-20: `[[action: {json}]]`: autonomous actions to execute after the reply completes. */
        val actions: List<ToolCall> = emptyList(),
    ) {
        /**
         * Something to act on even when there is no prose. A reply can be nothing but hidden lines (`[[step_done:
         * people]]`, `[[mode: decision]]`, a bare promise, a bare action), and then it is not an empty answer — the
         * markers still have to be applied. See ReplyRunner.
         */
        val hasActions: Boolean
            get() = promises.isNotEmpty() || mode != null || practiceWith != null || stepsDone.isNotEmpty() || actions.isNotEmpty()
    }

    private val BLOCK = Regex("""\[\[(.*?)]]""", RegexOption.DOT_MATCHES_ALL)

    /**
     * What to show while the reply is still arriving: everything before the first hidden marker. Both `[[` and a
     * `<<<TOOL_CALL …>>>` block start hiding the instant their prefix appears, so raw JSON action payloads never
     * flash on screen even for a single frame.
     */
    fun visibleWhileStreaming(text: String): String {
        val shown = text.substring(0, hiddenStart(text)).removeSuffix("[")
        return shown.trimEnd()
    }

    /** Where hidden content begins: the earliest of `[[`, `<<<`, or a dangling `<`/`<<` at the very end. */
    private fun hiddenStart(text: String, streaming: Boolean = true): Int {
        var cut = text.length
        val brackets = text.indexOf("[[")
        if (brackets in 0 until cut) cut = brackets
        val tool = text.indexOf("<<<")
        if (tool in 0 until cut) cut = tool
        // A lone trailing `<` only hides while streaming (it might become `<<<`); a finished reply keeps it.
        if (streaming && cut == text.length) {
            var i = text.length
            while (i > 0 && text[i - 1] == '<') i--
            if (text.length - i in 1..2) cut = i
        }
        return cut
    }

    /** [today]: what "today", "tonight" and "tomorrow" in a due field mean (the reply's local calendar day). */
    fun parse(reply: String, today: LocalDate = LocalDate.now()): Parsed {
        val promises = mutableListOf<PromiseLine>()
        var mode: ModeLine? = null
        var failures = 0
        var practiceWith: String? = null
        val stepsDone = mutableListOf<String>()
        // Actions are read first and cut out of the text the generic parser scans, because their JSON contains
        // colons and pipes that the promise/mode key-value reader would otherwise choke on. Both the legacy
        // `[[action: …]]` line and the canonical `<<<TOOL_CALL …>>>` block are read.
        val actions = mutableListOf<ToolCall>()
        val actionRanges = mutableListOf<IntRange>()
        for (m in ToolCalls.ACTION.findAll(reply)) {
            ToolCalls.parse(m.groupValues[1])?.let { actions += it } ?: failures++
            actionRanges += m.range
        }
        for (m in ToolCalls.TOOL_CALL.findAll(reply)) {
            ToolCalls.parse(m.groupValues[1])?.let { actions += it } ?: failures++
            actionRanges += m.range
        }
        val scanned = removeRanges(reply, actionRanges)
        for (m in BLOCK.findAll(scanned)) {
            // A line break inside a line is just a space.
            val body = m.groupValues[1].replace(Regex("""\s+"""), " ").trim()
            if (!body.contains(':')) {
                failures++
                continue
            }
            when (kind(body)) {
                "promise" -> parsePromise(body, today)?.let { promises += it } ?: failures++
                "mode" -> parseMode(body)?.let { mode = it } ?: failures++
                "practice_with" -> PracticeName.clean(body.substringAfter(':'))?.let { practiceWith = it } ?: failures++
                "step_done" -> stepName(body.substringAfter(':'))?.let { if (it !in stepsDone) stepsDone += it } ?: failures++
                else -> failures++
            }
        }
        // Anything unclosed from a hidden marker on is hidden too, as it was while streaming. Action ranges were
        // already cut out of [scanned], so this only removes the generic `[[…]]` lines and any dangling marker.
        val withoutBlocks = BLOCK.replace(scanned, "")
        val visible = withoutBlocks.substring(0, hiddenStart(withoutBlocks, streaming = false)).lines().joinToString("\n") { it.trimEnd() }.trim()
        return Parsed(visible, promises, mode, failures, practiceWith, stepsDone, actions)
    }

    /** Cuts the matched action markers out, so the generic parser never sees their JSON. */
    private fun removeRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sb = StringBuilder(text)
        ranges.sortedByDescending { it.first }.forEach { sb.delete(it.first, it.last + 1) }
        return sb.toString()
    }

    /** "Step Done :" / "step-done" / "STEP DONE" all read as "step_done". */
    private fun kind(body: String) = body.substringBefore(':').trim().lowercase().replace(Regex("""[\s-]+"""), "_")

    /** "story", "Future self", "how-you-work" → the stored step name; anything else is null. */
    internal fun stepName(raw: String): String? {
        val t = raw.trim().trim('"', '\'', '.').lowercase().replace(Regex("""[\s-]+"""), "_")
        return OnboardingTopic.fromStep(t)?.step
    }

    internal fun parsePromise(body: String, today: LocalDate = LocalDate.now()): PromiseLine? {
        val fields = fields(body) ?: return null
        val text = fields["promise"]?.takeIf { it.isNotBlank() } ?: return null
        val dueRaw = fields["due"].orNone()
        val due = dueRaw?.let { normalizeDue(it) ?: relativeDue(it, today) ?: return null }
        val remindRaw = fields["remind"].orNone()
        val remind = remindRaw?.let { parseDateTime(it) ?: return null }
        return PromiseLine(text = text, due = due, remindAt = remind, why = fields["why"].orNone())
    }

    internal fun parseMode(body: String): ModeLine? {
        val fields = fields(body) ?: return null
        val requested = fields["mode"]?.trim()?.lowercase()
        if (requested == "normal" || requested == "none") return ModeLine(null, null, null)
        val mode = Mode.fromWire(requested) ?: return null
        if (mode == Mode.ONBOARDING) return null
        // Never a sentence in the header: anything that isn't a short name is dropped (the coach asks instead).
        val with = PracticeName.clean(fields["with"].orNone())
        val name = fields["name"].orNone()
        if (mode == Mode.JOURNEY && name == null) return null
        return ModeLine(mode, with, name)
    }

    /** "promise: x | due: y" → {promise=x, due=y}. Keys are lowercased; values trimmed. */
    private fun fields(body: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (part in body.split('|')) {
            val i = part.indexOf(':')
            if (i <= 0) {
                // A stray "|" inside the text: glue it back to the previous value.
                val last = out.keys.lastOrNull() ?: return null
                out[last] = out.getValue(last) + " | " + part.trim()
                continue
            }
            val key = part.substring(0, i).trim().lowercase().replace(Regex("""[\s-]+"""), "_").let { if (it in KNOWN) it else part.substring(0, i).trim().lowercase() }
            if (key.isEmpty() || key.contains(' ') && key !in KNOWN) {
                val last = out.keys.lastOrNull() ?: return null
                out[last] = out.getValue(last) + " | " + part.trim()
                continue
            }
            out[key] = part.substring(i + 1).trim()
        }
        return out
    }

    private val KNOWN = setOf("promise", "due", "remind", "why", "mode", "with", "name", "step_done", "practice_with")

    private fun String?.orNone(): String? = this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", true) && !it.equals("null", true) }

    /** "today" / "tonight" / "tomorrow", against the day the reply was written. Anything vaguer stays unreadable. */
    internal fun relativeDue(s: String, today: LocalDate): String? = when (s.trim().lowercase().trimEnd('.')) {
        "today", "tonight", "aaj", "aaj raat" -> today.toString()
        "tomorrow", "kal" -> today.plusDays(1).toString()
        else -> null
    }

    /** Keeps "yyyy-MM-dd" or "yyyy-MM-ddTHH:mm" (a space instead of T is fine). */
    internal fun normalizeDue(s: String): String? {
        val t = s.trim().replace(' ', 'T')
        parseDateTime(t)?.let { return it.toString().take(16) }
        return try {
            LocalDate.parse(t).toString()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    internal fun parseDateTime(s: String): LocalDateTime? = try {
        LocalDateTime.parse(s.trim().replace(' ', 'T'))
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Who he's practicing with, as shown in the header: a short name or never anything (QA 5.1). */
object PracticeName {
    const val MAX = 30

    fun clean(raw: String?): String? {
        val t = raw?.trim()?.trim('"', '\'', '“', '”', '.', ',', ':')?.trim() ?: return null
        if (t.isEmpty() || t.equals("none", true) || t.equals("null", true)) return null
        if (t.length > MAX) return null
        // A name, not his message: at most four words, no sentence punctuation.
        if (t.split(Regex("""\s+""")).size > 4) return null
        if (t.any { it in ".?!…;\n" }) return null
        return t
    }
}

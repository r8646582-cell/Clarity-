package com.umair.purpose.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One autonomous action the coach asked for in a hidden tool marker. Two wire shapes are understood:
 *
 *  1. the original `[[action: {json}]]` line (UPDATE-20), and
 *  2. the canonical `<<<TOOL_CALL {json} >>>` block, which is what the schema now teaches the model.
 *
 * The JSON is a flat, compact object; unknown keys are ignored so the schema can grow without breaking older
 * replies. Both the `type` and `action` key name the action, so either spelling reads. Fields a given [kind]
 * doesn't use stay null/empty.
 */
@Serializable
data class ToolCall(
    /** Original key name. */
    val type: String = "",
    /** Canonical key name in `<<<TOOL_CALL …>>>` blocks. */
    val action: String? = null,
    // create_journey / replace_journey
    val title: String? = null,
    val description: String? = null,
    val stages: List<String> = emptyList(),
    // archive_journey
    @SerialName("journey_id") val journeyId: Long? = null,
    // record_promise
    @SerialName("reminder_epoch") val reminderEpoch: Long? = null,
    @SerialName("due_epoch_ms") val dueEpochMs: Long? = null,
    val due: String? = null,
    val remind: String? = null,
    val tags: List<String> = emptyList(),
    // resolve_promise
    @SerialName("promise_id") val promiseId: Long? = null,
    val status: String? = null,
    // reschedule_promise / edit_promise
    @SerialName("new_due_epoch_ms") val newDueEpochMs: Long? = null,
    @SerialName("new_due") val newDue: String? = null,
    @SerialName("new_title") val newTitle: String? = null,
    val reason: String? = null,
    // synthesize_memory / store_memory
    val insight: String? = null,
    val category: String? = null,
    @SerialName("replaces_ids") val replacesIds: List<Long> = emptyList(),
    @SerialName("emotional_valence") val emotionalValence: Int? = null,
    // trigger_milestone / award_milestone
    @SerialName("milestone_key") val milestoneKey: String? = null,
    @SerialName("action_key") val actionKey: String? = null,
    @SerialName("evidence_summary") val evidenceSummary: String? = null,
    val evidence: String? = null,
    // set_pacing / set_cadence
    @SerialName("pace_multiplier") val paceMultiplier: Double? = null,
    @SerialName("haptic_level") val hapticLevel: String? = null,
    @SerialName("haptic_pulse") val hapticPulse: String? = null,
) {
    /** The action name, however it was written. */
    val kind: String get() = (type.ifBlank { action ?: "" }).trim().lowercase()

    val known: Boolean get() = kind in TYPES

    companion object {
        val TYPES = setOf(
            "create_journey", "replace_journey", "archive_journey", "advance_journey",
            "record_promise",
            "resolve_promise",
            "reschedule_promise", "edit_promise",
            "synthesize_memory", "store_memory",
            "trigger_milestone", "award_milestone",
            "set_pacing", "set_cadence",
        )
    }
}

/** Reads the hidden action markers out of a reply. Pure, so it's tested directly. */
object ToolCalls {
    /**
     * `[[action: …]]`, however it's cased or spaced, with anything inside (including line breaks). Non-greedy, so
     * several markers in one reply each match on their own; the JSON is then read from its first `{` to its last `}`.
     */
    val ACTION = Regex("""\[\[\s*action\s*:(.*?)]]""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** The canonical block: `<<<TOOL_CALL {json} >>>`, any casing or spacing, across line breaks. */
    val TOOL_CALL = Regex("""<<<\s*TOOL_CALL\s*(.*?)\s*>>>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Null when there is no readable JSON object or no action name. */
    fun parse(body: String): ToolCall? {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(ToolCall.serializer(), body.substring(start, end + 1)) }
            .getOrNull()?.takeIf { it.kind.isNotBlank() }
    }
}

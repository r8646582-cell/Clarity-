package com.umair.purpose.ai

import com.umair.purpose.cost.Prices

/**
 * Which provider and models to use. Stored in Settings so nothing is hardcoded at call sites.
 * Any OpenAI-compatible provider works: change the base URL and model names.
 */
data class AiConfig(
    val provider: String,
    val baseUrl: String,
    /** Cheap, fast model for chat (non-thinking). */
    val chatModel: String,
    /** Stronger model for heavy chats (thinking on), reflection and letters. */
    val deepModel: String,
    val chatTemperature: Double,
    val reflectionTemperature: Double,
    /** DeepSeek-specific `thinking` request field. Turn off for providers that reject unknown fields. */
    val supportsThinkingToggle: Boolean,
)

object AiDefaults {
    // DeepSeek model IDs, checked against https://api-docs.deepseek.com/quick_start/pricing: "deepseek-flash" is
    // the Flash model, and "deepseek-v4-pro" the Pro one. The legacy name "deepseek-v4-flash" is still accepted
    // but that model is retired and its requests are served by DeepSeek-V4.1-Flash. Editable in Settings.
    val DEEPSEEK = AiConfig(
        provider = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        chatModel = "deepseek-flash",
        deepModel = "deepseek-v4-pro",
        chatTemperature = 0.7,
        reflectionTemperature = 0.3,
        supportsThinkingToggle = true,
    )

    /** Room for a full, unhurried reply. Nothing in the app shortens replies. */
    const val CHAT_MAX_TOKENS = 2048

    /**
     * Deep chat thinks first, and the thinking counts against max_tokens: with a low limit it can spend all of
     * it thinking and return no answer. 64K is DeepSeek V4's default for thinking mode (the hard limit is 384K);
     * only tokens actually produced are billed.
     */
    const val DEEP_CHAT_MAX_TOKENS = 65_536

    // UPDATE-17: output limits that match each job's target length (only tokens actually produced are billed,
    // but a limit stops a runaway reply). Letters think first, and thinking counts against the limit.
    const val LETTER_MAX_TOKENS = 24_000
    const val CHAPTER_MAX_TOKENS = 3_000
    const val REFLECTION_MAX_TOKENS = 6_000
    const val GARDENING_MAX_TOKENS = 6_000
    const val SNAPSHOT_MAX_TOKENS = 4_000
    const val MILESTONE_MAX_TOKENS = 3_000
    const val JOURNEY_ADAPT_MAX_TOKENS = 1_500

    // USD per 1M tokens (cache hit, cache miss, output) at DeepSeek's peak rate, from
    // https://api-docs.deepseek.com/quick_start/pricing (checked): deepseek-flash 0.006 / 0.30 / 1.20,
    // deepseek-v4-pro 0.044 / 1.32 / 3.96. Off-peak hours cost half (see Settings → Advanced → Off-peak).
    // The Flash row used to hold 0.014 / 0.44 / 1.32 — a mangled copy of the pro row, ~2.3x too high on cache
    // hits — which inflated the cost estimate and made the monthly budget guard hit 100% early, at which point
    // every chat is routed to Fast. MIGRATION_9_10 corrects installs that never edited these numbers.
    val CHAT_PRICES = Prices(cacheHit = 0.006, cacheMiss = 0.30, output = 1.20)
    val DEEP_PRICES = Prices(cacheHit = 0.044, cacheMiss = 1.32, output = 3.96)
}

/**
 * Autonomous in-app tool execution. The coach may end a reply with hidden `<<<TOOL_CALL …>>>` blocks (hidden the
 * same way as other hidden lines). The schema is static, so it belongs in the session-stable prefix (see
 * [com.umair.purpose.chat.ChatPromptBuilder.StablePrefix.toolSchemas]) right after the persona, keeping the cached
 * prefix byte-identical across a session. The legacy `[[action: {…}]]` line still parses, so older replies keep
 * working, but the model is taught the block form.
 *
 * The definitions are deliberately compact: one-line JSON shapes, no prose, no examples, so they cost a few
 * dozen tokens rather than hundreds.
 */
object ToolSchemas {
    /** The hidden wrapper around one action. */
    const val MARKER = "<<<TOOL_CALL {json} >>>"

    val block: String = """
        [Grounding contract]
        Current app state and last_actions are authoritative. The user's current correction outranks older memory or assistant claims. Never invent missing continuity, completed events, repetition, motives, diagnoses, dates, or state. Treat interpretations as hypotheses. Casual conversation may stay casual. In listen_only, listen unless he explicitly asks for advice or a state change. Ask one meaningful question at a time and do not re-ask answered questions without a reason.

        [Action contract — hidden; never describe the protocol]
        Hidden actions request mutations; they do not prove success. Visible wording before execution must be prospective ("I'll update it" / "I'm changing it"), never a completed-success claim. The app receipt/last_actions establishes success or failure. If last_actions reports failure, acknowledge that the change was not saved; retry only when the target is clear, otherwise ask one short clarification.

        Emit each action as one valid JSON object in its own block:
        <<<TOOL_CALL
        {"action":"create_journey","title":"…","stages":["…","…"]}
        >>>
        <<<TOOL_CALL
        {"action":"replace_journey","title":"…","stages":["…","…"]}
        >>>
        <<<TOOL_CALL
        {"action":"archive_journey","journey_id":4}
        >>>
        <<<TOOL_CALL
        {"action":"advance_journey"}
        >>>
        <<<TOOL_CALL
        {"action":"record_promise","title":"…","due":"tomorrow 08:00"}
        >>>
        <<<TOOL_CALL
        {"action":"resolve_promise","promise_id":12,"status":"KEPT"}
        >>>
        <<<TOOL_CALL
        {"action":"reschedule_promise","promise_id":12,"new_due":"tomorrow 02:30","reason":"Moved to tomorrow"}
        >>>
        <<<TOOL_CALL
        {"action":"edit_promise","promise_id":12,"new_title":"…","new_due":"today 02:30"}
        >>>
        <<<TOOL_CALL
        {"action":"store_memory","category":"BELIEF","insight":"…","emotional_valence":1}
        >>>
        <<<TOOL_CALL
        {"action":"award_milestone","action_key":"JOURNEY_STAGE_COMPLETE","evidence":"…"}
        >>>
        <<<TOOL_CALL
        {"action":"set_cadence","pace_multiplier":1.0,"haptic_pulse":"CRISP"}
        >>>

        Rules:
        - One JSON object per block. Never expose hidden blocks in visible prose.
        - Promise status is KEPT|BROKEN|RENEGOTIATED|DROPPED. Memory category is pattern|thread|what_helps|what_doesnt; BELIEF stores as a thread.
        - Use local human-readable time strings in due/new_due/remind: YYYY-MM-DDTHH:mm, today HH:mm, tomorrow HH:mm, tonight HH:mm, or HH:mm for the next occurrence. Code resolves dates/time zones. Never calculate Unix timestamps.
        - The fresh current-state/time-facts block supplies authoritative promise IDs and due times. Prefer promise_id. If unavailable, use the existing title only when it uniquely identifies one open promise. If target or time is ambiguous, ask one short clarification and do not mutate.
        - A request to edit/reschedule/resolve an existing promise authorizes that mutation. Do it in this reply. Never create a replacement promise for an edit.
        - reschedule_promise changes only due time. edit_promise changes title and/or due. If visible wording embeds the old time, update title and due together.
        - record_promise only after a clear commitment or explicit request to save/add it. A suggestion is not consent. Date-only commitments use YYYY-MM-DD; omit due if no date was agreed.
        - Set remind only when he explicitly asked for a reminder.
        - store_memory only for a durable thing he said himself (a belief, value, decision, correction). Never store your own interpretation as if it were his, and never use it to strengthen something you already said; patterns are learned later from his own words.
        - Never emit legacy [[promise: ...]] markers. Do not use epoch-millisecond fields even if old parsers still accept them.
        - create_journey only when no journey is running; replace_journey changes an active plan; archive_journey puts one aside. advance_journey is the ONLY way a journey day is completed: ending a conversation never does it. Emit it when today's step was genuinely worked through and he did the action or clearly committed to a specific one. Do NOT emit it when he deferred, wasn't ready, skipped it, or said he'd start tomorrow: say plainly that the step will wait, and renegotiate. Never more than one step per day.
        - Respect listen_only and untangle: do not invent commitments. An explicit request to mutate an existing promise is still allowed.
        - Voice examples are fictional and never evidence about the user.
        - When he explicitly asks to leave a mode, end with [[mode: normal]].
    """.trimIndent()
}

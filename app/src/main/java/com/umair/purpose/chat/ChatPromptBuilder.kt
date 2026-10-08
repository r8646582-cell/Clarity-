package com.umair.purpose.chat

import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role

enum class ToughLove(val wire: String) {
    GENTLE("gentle"), BALANCED("balanced"), FIRM("firm");

    companion object {
        fun fromWire(value: String?): ToughLove = entries.firstOrNull { it.wire == value } ?: BALANCED
    }
}

/** The tools and structured conversations. Each has its own `prompts/mode_<wire>.md`. */
enum class Mode(val wire: String) {
    ONBOARDING("onboarding"), JOURNEY("journey"), PRACTICE("practice"), DECISION("decision"), UNTANGLE("untangle");

    val promptFile: String get() = "mode_$wire.md"

    companion object {
        fun fromWire(value: String?): Mode? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

data class RuntimeFlags(
    /** UPDATE-19: a finished sentence, e.g. "It's Sunday 4 October 2026, 12:46am (late night; …)." See TimeFacts. */
    val now: String,
    val listenOnly: Boolean,
    val toughLove: ToughLove,
    val mode: Mode? = null,
    val onboardingStep: String? = null,
    val journeyName: String? = null,
    val journeyDay: Int? = null,
    val practiceWith: String? = null,
    /** Nothing in this conversation will be remembered. */
    val offTheRecord: Boolean = false,
    /** UPDATE-18: the latest journey adaptation, one sentence to him ("adjusted: …"). */
    val journeyAdjusted: String? = null,
    /** UPDATE-20: the outcome of the actions the coach took last turn, fed back so it can confirm them. Volatile. */
    val lastActions: String? = null,
)

/** One message of the current session, as stored. */
data class Turn(val role: Role, val content: String)

/**
 * Builds the message list for a chat request. The order is chosen so the longest possible prefix is
 * byte-identical from one request to the next, which is what prompt caching pays off:
 *
 * 1. persona, 2. context block, 3. recent session summaries — built once per session ([StablePrefix]);
 * 4. mode instructions — constant for as long as the session's mode is; they sit here rather than after the
 *    volatile blocks so the provider can reuse them too.
 *
 * Everything that genuinely changes per request follows: the due promise (at most one), the recomputed time
 * facts, "possibly relevant from the past", the runtime flags (which carry `now`), and finally the messages.
 * CLAUDE.md fixes persona/context/summaries as the cache prefix; putting the other per-session constant here
 * extends the reused prefix instead of re-billing it at the miss price on every message.
 */
object ChatPromptBuilder {
    /**
     * The sliding window: only the last 16 messages (roughly 8 user + 8 assistant turns) go with each request.
     * Older turns are left out of the payload, which keeps the request lean, cuts streaming latency and avoids
     * rate-limit blowouts. Nothing is lost: every turn stays in the database and what matters is in What I Know.
     */
    const val SLIDING_WINDOW_MESSAGES = 16

    /** Callers that reason about how long a conversation is (see MemoryRepository) compare against the window. */
    const val MAX_SESSION_MESSAGES = 16

    /** Added to the system anchor when older turns were left out of the window. */
    const val ARCHIVED_HISTORY_NOTE =
        "[Note: Older messages remain in the conversation archive. They may not yet be reflected in What I Know; do not invent missing details.]"

    /** The label that opens the system-state block just before the windowed conversation. */
    const val SYSTEM_ANCHOR = "[SYSTEM STATE & TEMPORAL ANCHOR]"

    data class StablePrefix(
        val persona: String,
        /**
         * UPDATE-20: the hidden tool/action schema. Static and session-stable, so it sits right after the persona
         * (the exact top of the request) and travels inside the cached prefix, never re-billed per message.
         */
        val toolSchemas: String? = null,
        /** Everything known about him. */
        val contextBlock: String? = null,
        /** Dated summaries of the last 5 sessions. */
        val recentSummaries: String? = null,
    )

    fun build(
        prefix: StablePrefix,
        flags: RuntimeFlags,
        turns: List<Turn>,
        duePromise: String? = null,
        modeInstructions: String? = null,
        maxMessages: Int = SLIDING_WINDOW_MESSAGES,
        /** UPDATE-15: "Possibly relevant from the past", found for this message. Volatile: after the stable prefix. */
        relevantPast: String? = null,
        /** UPDATE-21: tokenized matches from his durable notes for this message. Volatile. */
        relevantInsights: String? = null,
        /** UPDATE-19: code-computed due phrases for open promises, recomputed per request. Volatile. */
        timeFacts: String? = null,
        currentState: String? = null,
        continuity: String? = null,
    ): List<AiMessage> = frozenPrefix(prefix, modeInstructions) + volatileTail(
        flags = flags,
        turns = turns,
        duePromise = duePromise,
        timeFacts = timeFacts,
        currentState = currentState,
        relevantPast = relevantPast,
        relevantInsights = relevantInsights,
        maxMessages = maxMessages,
        continuity = continuity,
    )

    /**
     * TIER 1 — the frozen static prefix. Built once per session and byte-identical on every turn in it, in a fixed
     * order: persona (which already carries examples.md, the core identity and the anti-sycophancy rules), then the
     * static tool-action schema, then everything known about him, then the dated summaries, then the mode
     * instructions. Nothing here may ever contain a timestamp, a random id or anything that changes mid-session,
     * or DeepSeek's prefix cache would miss on every message.
     */
    internal fun frozenPrefix(prefix: StablePrefix, modeInstructions: String?): List<AiMessage> = buildList {
        add(AiMessage(Role.SYSTEM, prefix.persona))
        // The tool schema is static and lives at the very top, inside the cached prefix.
        prefix.toolSchemas?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it.trim())) }
        prefix.contextBlock?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it)) }
        prefix.recentSummaries?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it)) }
        // Session-stable: part of the reusable prefix, so it goes before anything that changes per request.
        modeInstructions?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it.trim())) }
    }

    /**
     * TIER 2 — the dynamic volatile tail, recomputed every request and always appended last: the due promise, the
     * injected time facts, "possibly relevant from the past", the runtime flags (which carry `now`), then the
     * active conversation history. Nothing here is ever allowed above [frozenPrefix], so the cached prefix stays
     * intact and only this small tail is billed at the miss price.
     */
    internal fun volatileTail(
        flags: RuntimeFlags,
        turns: List<Turn>,
        duePromise: String? = null,
        timeFacts: String? = null,
        currentState: String? = null,
        relevantPast: String? = null,
        relevantInsights: String? = null,
        maxMessages: Int = SLIDING_WINDOW_MESSAGES,
        continuity: String? = null,
    ): List<AiMessage> = buildList {
        if (!duePromise.isNullOrBlank()) {
            add(AiMessage(Role.SYSTEM, "Due promise (raise once, naturally, unless he arrives hurting):\n" + duePromise.trim()))
        }
        timeFacts?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it.trim())) }
        currentState?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it.trim())) }
        continuity?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it)) }
        relevantPast?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it.trim())) }
        relevantInsights?.takeIf { it.isNotBlank() }?.let { add(AiMessage(Role.SYSTEM, it.trim())) }
        // The system anchor carries the runtime state and the time; it also tells the model when older turns
        // were trimmed, so it knows they're archived (and where to look) rather than forgotten.
        val windowed = trim(turns, maxMessages)
        add(AiMessage(Role.SYSTEM, flagsBlock(flags, historyArchived = windowed.size < turns.size)))
        windowed.forEach { add(AiMessage(it.role, it.content)) }
    }

    /**
     * How many leading messages [build] produces from the session-stable parts, for providers that need an
     * explicit cache breakpoint (Anthropic). Derived from [frozenPrefix], so it can never drift from the order.
     */
    fun stableCount(prefix: StablePrefix, modeInstructions: String?): Int = frozenPrefix(prefix, modeInstructions).size

    internal fun flagsBlock(flags: RuntimeFlags, historyArchived: Boolean = false): String = buildString {
        // The fixed anchor the conversation is read against: runtime state, then the temporal facts.
        append(SYSTEM_ANCHOR).append('\n')
        append("Runtime flags:\n")
        append("now: ").append(flags.now).append('\n')
        append("listen_only: ").append(flags.listenOnly).append('\n')
        append("tough_love_level: ").append(flags.toughLove.wire)
        flags.mode?.let { append("\nmode: ").append(it.wire) }
        flags.onboardingStep?.let { append("\nonboarding_step: ").append(it) }
        flags.journeyName?.let { append("\njourney_name: ").append(it) }
        flags.journeyDay?.let { append("\njourney_day: ").append(it) }
        flags.practiceWith?.let { append("\npractice_with: ").append(it) }
        flags.journeyAdjusted?.let { append("\nadjusted: ").append(it) }
        // The outcome of last turn's hidden actions, fed back so the coach can confirm them. Volatile: tail only.
        flags.lastActions?.takeIf { it.isNotBlank() }?.let { append("\nlast_actions: ").append(it) }
        if (flags.offTheRecord) append("\noff_the_record: true")
        if (historyArchived) append('\n').append(ARCHIVED_HISTORY_NOTE)
    }

    /** Keeps the newest [max] messages, never starting on an assistant message unless that's all there is. */
    internal fun trim(turns: List<Turn>, max: Int): List<Turn> {
        if (turns.size <= max) return turns
        val tail = turns.takeLast(max)
        val firstUser = tail.indexOfFirst { it.role == Role.USER }
        return if (firstUser <= 0) tail else tail.drop(firstUser)
    }
}

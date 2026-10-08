package com.umair.purpose.chat

/** Which model answers a chat message. Chosen in code, per message, with no extra AI call. */
enum class Tier(val wire: String, val label: String) {
    /** Flash model, thinking off. */
    FAST("fast", "Fast"),
    /** Pro model, thinking on. */
    DEEP("deep", "Deep");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v }
    }
}

/**
 * Which model answers a chat message. Chosen in code, per message, with no extra AI call. Deep when the message
 * is long, it has heavy words, a mode is active, or a recent message in this conversation
 * was heavy (stay Deep for the next [STAY_DEEP] messages). Otherwise Fast.
 *
 * "Still on the same topic" can't be judged without an AI call, so staying Deep is limited to the same
 * conversation and to messages that were heavy in their own right. A first message alone doesn't make the
 * rest of the conversation Deep.
 *
 * The first message of a session is NOT automatically Deep: CLAUDE.md's "Cost efficiency → Right-size routing"
 * says it "goes Deep only if it's also long or heavy (not automatically)", and that section is the more specific
 * of the two — the "Model routing" section reads the other way. A long or heavy opener still goes
 * Deep (the LONG/HEAVY branches below), and any opener in a mode goes Deep (the MODE branch), so the
 * conversations that deserve care still get it.
 */
object ModelRouter {
    /** "Over ~250 characters". */
    const val LONG_MESSAGE = 250
    const val STAY_DEEP = 3

    /** Emotional or heavy words (English). Whole words, any case. Extend here. */
    val HEAVY_WORDS: List<String> = listOf(
        // English
        "alone", "lonely", "loneliness", "worthless", "useless", "not enough", "not good enough", "hate myself",
        "scared", "afraid", "fear", "anxious", "anxiety", "panic", "panicking", "stressed", "overwhelmed",
        "fail", "failed", "failing", "failure", "depressed", "depression", "hopeless", "empty", "numb",
        "cry", "crying", "cried", "tears", "fight", "fought", "fighting", "argument", "family", "father", "mother",
        "hurt", "ashamed", "shame", "guilt", "guilty", "regret", "stuck", "lost", "give up", "giving up",
        "breakup", "broke up", "heartbroken", "grief", "died", "death", "angry", "rage", "jealous", "insecure",
        "wasting my time", "wasted", "what's the point", "whats the point", "disappear", "can't keep going",
        "cant keep going",
        // How he names his parents.
        "abbu", "ammi", "abu", "ami",
    )

    private val patterns: List<Regex> = HEAVY_WORDS.map { phrase ->
        val words = phrase.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString("""\s+""") { Regex.escape(it) }
        Regex("""(?<![\p{L}\p{N}])$words(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    }

    fun isHeavy(message: String): Boolean {
        val text = message.replace('’', '\'')
        return patterns.any { it.containsMatchIn(text) }
    }

    /** Heavy in its own right: long, or with heavy words. */
    fun weighty(message: String): Boolean =
        message.trim().length > LONG_MESSAGE || isHeavy(message)

    data class Input(
        val message: String,
        /** His earlier messages in this conversation, oldest first (not including [message]). */
        val earlierUserMessages: List<String> = emptyList(),
        val modeActive: Boolean = false,
        /** Settings > Advanced: "Always use the deep model". */
        val alwaysDeep: Boolean = false,
        /** Cost guard: the monthly budget is used up, so everything goes Fast until the month ends. */
        val overBudget: Boolean = false,
    )

    enum class Reason { OVER_BUDGET, ALWAYS_DEEP, LONG, HEAVY, MODE, STAYING_DEEP, CASUAL }

    data class Route(val tier: Tier, val reason: Reason)

    fun route(input: Input): Route {
        val m = input.message
        return when {
            input.overBudget -> Route(Tier.FAST, Reason.OVER_BUDGET)
            input.alwaysDeep -> Route(Tier.DEEP, Reason.ALWAYS_DEEP)
            m.trim().length > LONG_MESSAGE -> Route(Tier.DEEP, Reason.LONG)
            isHeavy(m) -> Route(Tier.DEEP, Reason.HEAVY)
            input.modeActive -> Route(Tier.DEEP, Reason.MODE)
            // No "first message of the session" branch on purpose: a long or heavy opener is
            // already Deep from the branches above, and a plain "hi" does not need the Pro model with thinking.
            input.earlierUserMessages.takeLast(STAY_DEEP).any(::weighty) -> Route(Tier.DEEP, Reason.STAYING_DEEP)
            else -> Route(Tier.FAST, Reason.CASUAL)
        }
    }
}

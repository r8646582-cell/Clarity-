package com.umair.purpose.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The five "getting to know you" conversations, in order, with their Talk card subtitles. */
enum class OnboardingTopic(val step: String, val title: String) {
    STORY("story", "Your story"),
    PEOPLE("people", "Your people"),
    VALUES("values", "What you value"),
    FUTURE_SELF("future_self", "Your future self"),
    HOW_YOU_WORK("how_you_work", "How you work");

    companion object {
        fun fromStep(step: String?) = entries.firstOrNull { it.step == step }
    }
}

object OnboardingSteps {
    /** The in-app steps; their answers are kept in OnboardingStep.data. */
    const val VALUES_SORT = "values_sort"
    const val BIG_FIVE = "big_five"
    /** Marker row: he saw the welcome screen (or skipped it). */
    const val WELCOME = "welcome"
    /** Work in progress, so leaving mid-way (or the app being killed) loses nothing. Never "done". */
    const val VALUES_DRAFT = "values_sort_draft"
    const val BIG_FIVE_DRAFT = "big_five_draft"
}

/** The values card sort, part way through. */
@Serializable
data class ValuesDraft(val picked: List<String> = emptyList(), val ordering: Boolean = false)

/** The questionnaire, part way through: 0 = not answered yet. */
@Serializable
data class BigFiveDraft(val answers: List<Int> = emptyList(), val page: Int = 0, val itemSet: String = BigFive.LEGACY_SET)

/** Values for the card sort, in plain language. */
val VALUE_CHOICES = listOf(
    "family", "faith", "growth", "freedom", "respect", "security", "adventure", "honesty", "achievement", "service",
    "health", "knowledge", "creativity", "loyalty", "independence", "peace", "discipline", "fairness", "love",
    "recognition", "contribution", "wealth", "courage", "humility", "friendship", "nature", "tradition", "fun",
    "purpose", "kindness",
)

/** One questionnaire item. [domain] is O, C, E, A or N; [plus] = keyed toward that trait (for N: more neurotic). */
data class BigFiveItem(val text: String, val domain: Char, val plus: Boolean)

/**
 * The IPIP Big-Five Factor Markers, 50 items (Goldberg, 1992), public domain. 10 items per factor:
 * Extraversion, Agreeableness, Conscientiousness, Emotional Stability (scored here as its opposite,
 * Neuroticism, so "Get stressed out easily" is +N) and Intellect/Imagination (shown as Openness).
 * Items and keying follow the standard 50-item Factor Markers order (EXT1-10, AGR1-10, CSN1-10, EST1-10,
 * OPN1-10) as used in the openpsychometrics.org IPIP-FFM data. ipip.ori.org itself couldn't be reached from
 * the build environment, so check the list against ipip.ori.org/newBigFive5broadKey.htm once.
 * No item is about politics or religion (QA 9.0: the longer IPIP-NEO set used before had voting items).
 */
object BigFive {
    val DOMAINS = listOf('O', 'C', 'E', 'A', 'N')
    val NAMES = mapOf(
        'O' to "Openness", 'C' to "Conscientiousness", 'E' to "Extraversion", 'A' to "Agreeableness", 'N' to "Neuroticism",
    )

    /** Which item list saved answers belong to. */
    const val ITEM_SET = "ipip-bffm-50"
    /** Answers saved before update 10 (the IPIP-NEO 50, with two voting items). */
    const val LEGACY_SET = "ipip-neo-50"

    /** Interleaved so neighbouring statements measure different traits. */
    val ITEMS: List<BigFiveItem> by lazy { interleave(FACTOR_MARKERS) }

    /** The old list, only to rescore answers saved with it. */
    val LEGACY_ITEMS: List<BigFiveItem> by lazy { interleave(LEGACY) }

    private fun interleave(raw: List<BigFiveItem>): List<BigFiveItem> {
        val byDomain = raw.groupBy { it.domain }
        return (0 until 10).flatMap { i -> listOf('E', 'A', 'C', 'N', 'O').map { byDomain.getValue(it)[i] } }
    }

    /** Never asked or scored: anything about politics or religion. */
    private val OFF_LIMITS = Regex("""\b(vote|voting|political|politics|liberal|conservative|religio\w*|god|church|mosque|pray\w*)\b""", RegexOption.IGNORE_CASE)

    fun offLimits(item: BigFiveItem) = OFF_LIMITS.containsMatchIn(item.text)

    private val FACTOR_MARKERS = listOf(
        // Extraversion
        BigFiveItem("Am the life of the party", 'E', true),
        BigFiveItem("Don't talk a lot", 'E', false),
        BigFiveItem("Feel comfortable around people", 'E', true),
        BigFiveItem("Keep in the background", 'E', false),
        BigFiveItem("Start conversations", 'E', true),
        BigFiveItem("Have little to say", 'E', false),
        BigFiveItem("Talk to a lot of different people at parties", 'E', true),
        BigFiveItem("Don't like to draw attention to myself", 'E', false),
        BigFiveItem("Don't mind being the center of attention", 'E', true),
        BigFiveItem("Am quiet around strangers", 'E', false),
        // Agreeableness
        BigFiveItem("Feel little concern for others", 'A', false),
        BigFiveItem("Am interested in people", 'A', true),
        BigFiveItem("Insult people", 'A', false),
        BigFiveItem("Sympathize with others' feelings", 'A', true),
        BigFiveItem("Am not interested in other people's problems", 'A', false),
        BigFiveItem("Have a soft heart", 'A', true),
        BigFiveItem("Am not really interested in others", 'A', false),
        BigFiveItem("Take time out for others", 'A', true),
        BigFiveItem("Feel others' emotions", 'A', true),
        BigFiveItem("Make people feel at ease", 'A', true),
        // Conscientiousness
        BigFiveItem("Am always prepared", 'C', true),
        BigFiveItem("Leave my belongings around", 'C', false),
        BigFiveItem("Pay attention to details", 'C', true),
        BigFiveItem("Make a mess of things", 'C', false),
        BigFiveItem("Get chores done right away", 'C', true),
        BigFiveItem("Often forget to put things back in their proper place", 'C', false),
        BigFiveItem("Like order", 'C', true),
        BigFiveItem("Shirk my duties", 'C', false),
        BigFiveItem("Follow a schedule", 'C', true),
        BigFiveItem("Am exacting in my work", 'C', true),
        // Emotional Stability, keyed here toward Neuroticism
        BigFiveItem("Get stressed out easily", 'N', true),
        BigFiveItem("Am relaxed most of the time", 'N', false),
        BigFiveItem("Worry about things", 'N', true),
        BigFiveItem("Seldom feel blue", 'N', false),
        BigFiveItem("Am easily disturbed", 'N', true),
        BigFiveItem("Get upset easily", 'N', true),
        BigFiveItem("Change my mood a lot", 'N', true),
        BigFiveItem("Have frequent mood swings", 'N', true),
        BigFiveItem("Get irritated easily", 'N', true),
        BigFiveItem("Often feel blue", 'N', true),
        // Intellect/Imagination
        BigFiveItem("Have a rich vocabulary", 'O', true),
        BigFiveItem("Have difficulty understanding abstract ideas", 'O', false),
        BigFiveItem("Have a vivid imagination", 'O', true),
        BigFiveItem("Am not interested in abstract ideas", 'O', false),
        BigFiveItem("Have excellent ideas", 'O', true),
        BigFiveItem("Do not have a good imagination", 'O', false),
        BigFiveItem("Am quick to understand things", 'O', true),
        BigFiveItem("Use difficult words", 'O', true),
        BigFiveItem("Spend time reflecting on things", 'O', true),
        BigFiveItem("Am full of ideas", 'O', true),
    )

    private val LEGACY = listOf(
        BigFiveItem("Believe in the importance of art", 'O', true),
        BigFiveItem("Have a vivid imagination", 'O', true),
        BigFiveItem("Tend to vote for liberal political candidates", 'O', true),
        BigFiveItem("Carry the conversation to a higher level", 'O', true),
        BigFiveItem("Enjoy hearing new ideas", 'O', true),
        BigFiveItem("Am not interested in abstract ideas", 'O', false),
        BigFiveItem("Do not like art", 'O', false),
        BigFiveItem("Avoid philosophical discussions", 'O', false),
        BigFiveItem("Do not enjoy going to art museums", 'O', false),
        BigFiveItem("Tend to vote for conservative political candidates", 'O', false),
        BigFiveItem("Often feel blue", 'N', true),
        BigFiveItem("Dislike myself", 'N', true),
        BigFiveItem("Am often down in the dumps", 'N', true),
        BigFiveItem("Have frequent mood swings", 'N', true),
        BigFiveItem("Panic easily", 'N', true),
        BigFiveItem("Rarely get irritated", 'N', false),
        BigFiveItem("Seldom feel blue", 'N', false),
        BigFiveItem("Feel comfortable with myself", 'N', false),
        BigFiveItem("Am not easily bothered by things", 'N', false),
        BigFiveItem("Am very pleased with myself", 'N', false),
        BigFiveItem("Feel comfortable around people", 'E', true),
        BigFiveItem("Make friends easily", 'E', true),
        BigFiveItem("Am skilled in handling social situations", 'E', true),
        BigFiveItem("Am the life of the party", 'E', true),
        BigFiveItem("Know how to captivate people", 'E', true),
        BigFiveItem("Have little to say", 'E', false),
        BigFiveItem("Keep in the background", 'E', false),
        BigFiveItem("Would describe my experiences as somewhat dull", 'E', false),
        BigFiveItem("Don't like to draw attention to myself", 'E', false),
        BigFiveItem("Don't talk a lot", 'E', false),
        BigFiveItem("Am always prepared", 'C', true),
        BigFiveItem("Pay attention to details", 'C', true),
        BigFiveItem("Get chores done right away", 'C', true),
        BigFiveItem("Carry out my plans", 'C', true),
        BigFiveItem("Make plans and stick to them", 'C', true),
        BigFiveItem("Waste my time", 'C', false),
        BigFiveItem("Find it difficult to get down to work", 'C', false),
        BigFiveItem("Do just enough work to get by", 'C', false),
        BigFiveItem("Don't see things through", 'C', false),
        BigFiveItem("Shirk my duties", 'C', false),
        BigFiveItem("Have a good word for everyone", 'A', true),
        BigFiveItem("Believe that others have good intentions", 'A', true),
        BigFiveItem("Respect others", 'A', true),
        BigFiveItem("Accept people as they are", 'A', true),
        BigFiveItem("Make people feel at ease", 'A', true),
        BigFiveItem("Have a sharp tongue", 'A', false),
        BigFiveItem("Cut others to pieces", 'A', false),
        BigFiveItem("Suspect hidden motives in others", 'A', false),
        BigFiveItem("Get back at others", 'A', false),
        BigFiveItem("Insult people", 'A', false),
    )

    /**
     * Answers are 1 (disagree) to 5 (agree), indexed like [items]; 0 = skipped.
     * Each trait's percent is where its average keyed score sits between 1 and 5 (0% to 100%).
     * Traits with fewer than half their items answered are left out. Off-limits items never count.
     */
    fun score(answers: List<Int>, items: List<BigFiveItem> = ITEMS): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (d in DOMAINS) {
            val keyed = items.indices.filter { items[it].domain == d && !offLimits(items[it]) }
                .mapNotNull { i -> answers.getOrNull(i)?.takeIf { it in 1..5 }?.let { a -> if (items[i].plus) a else 6 - a } }
            if (keyed.size * 2 < 10) continue
            out[d.toString()] = Math.round((keyed.average() - 1.0) / 4.0 * 100.0).toInt()
        }
        return out
    }

    /**
     * Answers saved with the old list: the voting items are dropped and the rest rescored. Returns null when
     * the data is already on the current list.
     */
    fun rescoreLegacy(d: BigFiveData): BigFiveData? {
        if (d.itemSet == ITEM_SET) return null
        val kept = d.answers.mapIndexed { i, a -> if (LEGACY_ITEMS.getOrNull(i)?.let(::offLimits) == true) 0 else a }
        return BigFiveData(kept, score(kept, LEGACY_ITEMS), itemSet = "$LEGACY_SET-rescored")
    }
}

@Serializable
data class BigFiveData(
    val answers: List<Int>,
    val percents: Map<String, Int>,
    /** Which list [answers] are indexed by; missing in data saved before update 10. */
    val itemSet: String = BigFive.LEGACY_SET,
)

/** JSON in and out of OnboardingStep.data and Snapshot, and how it reads in prompts. */
object OnboardingFormat {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeValues(values: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), values)

    fun decodeValues(s: String?): List<String> =
        s?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() } ?: emptyList()

    fun encodeBigFive(d: BigFiveData): String = json.encodeToString(BigFiveData.serializer(), d)

    fun decodeBigFive(s: String?): BigFiveData? =
        s?.let { runCatching { json.decodeFromString(BigFiveData.serializer(), it) }.getOrNull() }

    /** "Openness 62%, Conscientiousness 35%, ..." */
    fun bigFiveLine(s: String?): String? = decodeBigFive(s)?.percents?.takeIf { it.isNotEmpty() }?.let { p ->
        BigFive.DOMAINS.mapNotNull { d -> p[d.toString()]?.let { "${BigFive.NAMES.getValue(d)} $it%" } }.joinToString(", ")
    }

    fun encodeValuesDraft(d: ValuesDraft): String = json.encodeToString(ValuesDraft.serializer(), d)

    fun decodeValuesDraft(s: String?): ValuesDraft =
        s?.let { runCatching { json.decodeFromString(ValuesDraft.serializer(), it) }.getOrNull() } ?: ValuesDraft()

    fun encodeBigFiveDraft(d: BigFiveDraft): String = json.encodeToString(BigFiveDraft.serializer(), d)

    /** A draft answered on the old item list doesn't line up with the new one: start fresh. */
    fun decodeBigFiveDraft(s: String?): BigFiveDraft =
        s?.let { runCatching { json.decodeFromString(BigFiveDraft.serializer(), it) }.getOrNull() }
            ?.takeIf { it.itemSet == BigFive.ITEM_SET || it.answers.all { a -> a == 0 } }
            ?.copy(itemSet = BigFive.ITEM_SET)
            ?: BigFiveDraft(itemSet = BigFive.ITEM_SET)

    fun valuesLine(s: String?): String? = decodeValues(s).takeIf { it.isNotEmpty() }?.joinToString(", ")

    /**
     * UPDATE-13: the values conversation gets his values right next to its instructions, numbered in his order, so
     * the coach can name every one back and cover each (it used to go deep on the first and never reach the rest).
     */
    fun valuesStepBlock(values: List<String>): String =
        if (values.isEmpty()) "His values: he hasn't done the values sort in the app yet."
        else "His top values from the values sort, in the order he ranked them:\n" +
            values.mapIndexed { i, v -> "${i + 1}. $v" }.joinToString("\n")
}

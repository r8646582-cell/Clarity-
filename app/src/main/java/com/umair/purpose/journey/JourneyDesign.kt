package com.umair.purpose.journey

import com.umair.purpose.data.db.CustomJourney
import com.umair.purpose.data.db.Journey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A journey made for him (journey_custom.md), before he starts it: the preview. */
data class CustomDraft(val name: String, val description: String, val why: String, val steps: List<JourneyStep>)

/** "Suggested for you": a catalog journey and one line on why. */
@Serializable
data class Suggestion(val name: String, val why: String = "")

/** What the journey list shows, in order (CLAUDE.md "Journeys, upgraded"). */
data class JourneyGroups(
    val suggested: List<Pair<JourneyPlan, String>>,
    val yours: List<JourneyPlan>,
    val all: List<JourneyPlan>,
    /** Finished journeys, newest first, each once. */
    val completed: List<Pair<Journey, JourneyPlan?>>,
)

/** Parsing and choosing for custom and suggested journeys. Pure, so it's tested. */
object JourneyDesign {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private data class DayJson(val day: Int = 0, val theme: String = "", val explore: String = "", val action: String = "")

    @Serializable
    private data class CustomJson(val name: String = "", val description: String = "", val why: String = "", val days: List<DayJson> = emptyList())

    @Serializable
    private data class SuggestJson(val suggested: List<Suggestion> = emptyList())

    class DesignException(message: String) : Exception(message)

    private fun objectIn(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw DesignException("No JSON object in reply")
        return raw.substring(start, end + 1)
    }

    /** journey_custom.md output → a preview. Needs a name and 5 to 14 usable days (7 asked for). */
    fun parseCustom(raw: String): CustomDraft {
        val c = try {
            json.decodeFromString(CustomJson.serializer(), objectIn(raw))
        } catch (e: DesignException) {
            throw e
        } catch (e: Exception) {
            throw DesignException("Reply was not valid journey JSON")
        }
        val steps = c.days.filter { it.theme.isNotBlank() }
            .mapIndexed { i, d -> JourneyStep(i + 1, d.theme.trim(), d.explore.trim(), d.action.trim()) }
        val name = c.name.trim().take(60)
        if (name.isEmpty() || steps.size !in 5..14) throw DesignException("The journey came back incomplete")
        return CustomDraft(name, c.description.trim(), c.why.trim(), steps)
    }

    fun encodeSteps(steps: List<JourneyStep>): String = json.encodeToString(
        ListSerializer(DayJson.serializer()), steps.map { DayJson(it.day, it.theme, it.explore, it.action) },
    )

    fun plan(c: CustomJourney): JourneyPlan? {
        val days = runCatching { json.decodeFromString(ListSerializer(DayJson.serializer()), c.daysJson) }.getOrNull() ?: return null
        val steps = days.mapIndexed { i, d -> JourneyStep(i + 1, d.theme, d.explore, d.action) }
        if (steps.isEmpty()) return null
        return JourneyPlan(c.name, c.description, steps, custom = true, why = c.why)
    }

    /** A custom name never shadows a built-in one or an earlier custom one. */
    fun uniqueName(name: String, taken: Collection<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        return generateSequence(2) { it + 1 }.map { "$name ($it)" }.first { it.lowercase() !in lower }
    }

    /** The Fast model's pick → at most 2 catalog journeys that exist, aren't active, and aren't repeats. */
    fun parseSuggestions(raw: String, catalog: List<JourneyPlan>, exclude: Set<String> = emptySet()): List<Suggestion> {
        val s = runCatching { json.decodeFromString(SuggestJson.serializer(), objectIn(raw)) }.getOrNull() ?: return emptyList()
        return s.suggested.mapNotNull { sug ->
            JourneyCatalog.find(catalog, sug.name)?.let { Suggestion(it.name, sug.why.trim()) }
        }.filter { it.name !in exclude }.distinctBy { it.name }.take(2)
    }

    fun encodeSuggestions(list: List<Suggestion>): String = json.encodeToString(ListSerializer(Suggestion.serializer()), list)

    fun decodeSuggestions(s: String?): List<Suggestion> =
        s?.let { runCatching { json.decodeFromString(ListSerializer(Suggestion.serializer()), it) }.getOrNull() } ?: emptyList()

    /** The instruction for "Suggested for you" (Fast model, JSON). Technical glue, not a coaching prompt. */
    fun suggestionPrompt(catalog: List<JourneyPlan>, context: String, completed: List<String>, justCompleted: String?): String = buildString {
        append("You pick journeys for Umair from a fixed list. Match his active patterns and stuck life areas to the journey descriptions.\n")
        append("Return ONLY valid JSON: {\"suggested\": [{\"name\": \"exact journey name\", \"why\": \"one short sentence, to him, as you\"}]} with at most 2 items.\n")
        if (justCompleted != null) append("He just finished \"$justCompleted\": the first suggestion should be the natural next one.\n")
        if (completed.isNotEmpty()) append("Already done (avoid unless it clearly fits again): ${completed.joinToString("; ")}\n")
        append("\nJourneys:\n")
        catalog.filter { !it.custom }.forEach { append("- ").append(it.name).append(": ").append(it.blurb).append('\n') }
        append("\nWhat you know about him:\n").append(context.ifBlank { "(little so far)" })
    }

    /** One line from the last day's reflection summary: its first sentence, kept short. */
    fun takeaway(summary: String?): String? {
        val t = summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val first = Regex("""^(.+?[.!?])(\s|$)""").find(t)?.groupValues?.get(1) ?: t
        return if (first.length <= 180) first else first.take(177).trimEnd() + "…"
    }

    fun groups(plans: List<JourneyPlan>, journeys: List<Journey>, suggestions: List<Suggestion>): JourneyGroups {
        val active = journeys.firstOrNull { it.status == Journey.ACTIVE }?.name
        val suggested = suggestions.mapNotNull { s -> plans.firstOrNull { it.name == s.name && it.name != active }?.let { it to s.why } }.take(2)
        val completed = journeys.filter { it.status == Journey.DONE }
            .sortedByDescending { it.completedAt ?: it.startedAt }
            .distinctBy { it.name }
            .map { j -> j to plans.firstOrNull { it.name == j.name } }
        return JourneyGroups(
            suggested = suggested,
            yours = plans.filter { it.custom },
            all = plans.filter { !it.custom },
            completed = completed,
        )
    }
}

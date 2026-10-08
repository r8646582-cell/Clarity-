package com.umair.purpose.journey

import com.umair.purpose.data.db.Journey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** prompts/journey_adapt.md output. */
@Serializable
data class AdaptResult(
    val decision: String = "continue",
    val reason: String = "",
    val changes: List<AdaptChange> = emptyList(),
)

@Serializable
data class AdaptChange(val day: Int = 0, val theme: String = "", val explore: String = "", val action: String = "")

/**
 * UPDATE-18: adapting the rest of a journey run to how he's really doing. The goal stays; only the plan changes:
 * at most 3 days longer than written, the last day stays the review, past days never change, and nothing but
 * the run's own copy is touched (journeys.md and custom journeys stay as written). Pure, so it's tested.
 */
object JourneyAdaptation {
    const val MAX_EXTRA_DAYS = 3
    val DECISIONS = setOf("continue", "repeat_day", "make_smaller", "make_bigger", "swap_step", "rest_day", "pause")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(raw: String): AdaptResult? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(AdaptResult.serializer(), raw.substring(start, end + 1)) }.getOrNull()
            ?.let { it.copy(decision = it.decision.trim().lowercase().replace(' ', '_')) }
            ?.takeIf { it.decision in DECISIONS }
    }

    /** The run's steps: its adapted copy if it has one, else the plan as written. */
    fun steps(j: Journey, plan: JourneyPlan?): List<JourneyStep> =
        j.stepsJson?.let { decode(it) }?.takeIf { it.isNotEmpty() } ?: plan?.steps.orEmpty()

    fun encode(steps: List<JourneyStep>): String = JourneyDesign.encodeSteps(steps)

    private fun decode(s: String): List<JourneyStep>? =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AdaptChange.serializer()), s) }.getOrNull()
            ?.mapIndexed { i, d -> JourneyStep(i + 1, d.theme, d.explore, d.action) }

    data class Applied(val journey: Journey, val changed: Boolean)

    /**
     * Applies [r] to [j] (whose next day is [Journey.currentDay]). [sessionDay]: the day the conversation was about.
     * [writtenDays]: the plan's length as written, for the 3-day limit. Days before the next one never change, nor
     * does the last (the review).
     */
    fun apply(j: Journey, steps: List<JourneyStep>, writtenDays: Int, r: AdaptResult, now: Long, sessionDay: Int = j.currentDay - 1): Applied {
        if (j.status != Journey.ACTIVE) return Applied(j, false)
        val next = j.currentDay
        if (next > steps.size) return Applied(j, false)
        val last = steps.size
        fun step(c: AdaptChange, day: Int, fallback: JourneyStep?) = JourneyStep(
            day,
            c.theme.trim().ifEmpty { fallback?.theme.orEmpty() },
            c.explore.trim().ifEmpty { fallback?.explore.orEmpty() },
            c.action.trim().ifEmpty { fallback?.action.orEmpty() },
        )
        val canGrow = steps.size < writtenDays + MAX_EXTRA_DAYS
        val out: MutableList<JourneyStep> = steps.toMutableList()
        when (r.decision) {
            "continue" -> return Applied(j, false)
            "pause" -> return Applied(j.copy(status = Journey.PAUSED, pausedAt = now), true)
            "repeat_day", "rest_day" -> {
                // A day goes in before the next one: today's step again (adjusted), or a gentle rest day.
                val base = if (r.decision == "repeat_day") steps.getOrNull(sessionDay - 1) ?: steps[next - 1] else null
                val change = r.changes.firstOrNull { it.day == next } ?: r.changes.firstOrNull()
                val inserted = when {
                    change != null -> step(change, next, base ?: REST)
                    r.decision == "rest_day" -> REST
                    else -> base!!
                }
                when {
                    // The step didn't count (too little talk): the next day already is that step; it's just adjusted.
                    r.decision == "repeat_day" && sessionDay == next -> if (next < last) out[next - 1] = inserted else return Applied(j, false)
                    canGrow -> out.add(next - 1, inserted)
                    // No room to add a day: it takes the next one's place (never the review).
                    next < last -> out[next - 1] = inserted
                    else -> return Applied(j, false)
                }
            }
            else -> {
                // make_smaller, make_bigger, swap_step: only upcoming days, never the last one.
                var any = false
                for (c in r.changes) {
                    if (c.day < next || c.day >= last || c.day > out.size) continue
                    out[c.day - 1] = step(c, c.day, out[c.day - 1])
                    any = true
                }
                if (!any) return Applied(j, false)
            }
        }
        val renumbered = out.mapIndexed { i, s -> s.copy(day = i + 1) }
        return Applied(j.copy(stepsJson = encode(renumbered), totalDays = renumbered.size), true)
    }

    /** Paused: back to active, from the same day. */
    fun resume(j: Journey): Journey = if (j.status == Journey.PAUSED) j.copy(status = Journey.ACTIVE, pausedAt = null) else j

    private val REST = JourneyStep(0, "Rest", "How you are, without pushing", "Rest today. Nothing to do but notice how you feel.")
}

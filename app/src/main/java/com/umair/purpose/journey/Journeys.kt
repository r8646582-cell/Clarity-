package com.umair.purpose.journey

import com.umair.purpose.data.db.Journey
import java.time.LocalDate

data class JourneyStep(val day: Int, val theme: String, val explore: String, val action: String) {
    /** The line the coach gets: "Day 3 | Shrink the start | ... | ...". */
    fun line() = "Day $day | $theme | $explore | $action"
}

data class JourneyPlan(
    val name: String,
    val description: String,
    val steps: List<JourneyStep>,
    /** Made for him with journey_custom.md ("Yours"). */
    val custom: Boolean = false,
    /** Custom journeys: why it fits him now. */
    val why: String? = null,
) {
    val days: Int get() = steps.size

    /** The description without its trailing "7 days." */
    val blurb: String get() = description.replace(Regex("""\s*\d+\s+days\.?\s*$"""), "").trim()

    fun step(day: Int): JourneyStep? = steps.firstOrNull { it.day == day }
}

/**
 * Parses prompts/journeys.md: each journey starts with "## Name", then a one-line description,
 * then "Day N | theme | what to explore | today's action" lines.
 */
object JourneyCatalog {
    private val DAY = Regex("""^Day\s+(\d+)\s*\|(.*)$""")

    fun parse(markdown: String): List<JourneyPlan> {
        val plans = mutableListOf<JourneyPlan>()
        var name: String? = null
        var description: String? = null
        var steps = mutableListOf<JourneyStep>()

        fun flush() {
            val n = name ?: return
            if (steps.isNotEmpty()) plans += JourneyPlan(n, description.orEmpty(), steps.sortedBy { it.day })
        }

        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    flush()
                    name = line.removePrefix("## ").trim()
                    description = null
                    steps = mutableListOf()
                }
                name == null || line.isEmpty() -> Unit
                DAY.matches(line) -> {
                    val m = DAY.find(line)!!
                    val parts = m.groupValues[2].split('|').map { it.trim() }
                    if (parts.size >= 3) {
                        steps += JourneyStep(
                            day = m.groupValues[1].toInt(),
                            theme = parts[0],
                            explore = parts[1],
                            action = parts.drop(2).joinToString(" | "),
                        )
                    }
                }
                description == null -> description = line
            }
        }
        flush()
        return plans
    }

    /**
     * UPDATE-14: a journey the coach names in `[[mode: journey | name: …]]` must really exist: same name, ignoring
     * case, spacing, quotes and a trailing full stop. Nothing close-enough, so an invented one starts nothing.
     */
    fun exact(plans: List<JourneyPlan>, name: String?): JourneyPlan? {
        val n = normalize(name ?: return null).takeIf { it.isNotEmpty() } ?: return null
        return plans.firstOrNull { normalize(it.name) == n }
    }

    private fun normalize(s: String) =
        s.trim().trim('"', '\'', '“', '”', '‘', '’').trimEnd('.').trim().lowercase().replace(Regex("""\s+"""), " ")

    fun find(plans: List<JourneyPlan>, name: String?): JourneyPlan? {
        val n = name?.trim()?.lowercase() ?: return null
        return plans.firstOrNull { it.name.lowercase() == n } ?: plans.firstOrNull { it.name.lowercase().contains(n) || n.contains(it.name.lowercase()) }
    }
}

/** One step per calendar day; one active journey at a time. */
object JourneyRules {
    /** His messages in a journey session before that day's step counts as done. */
    const val STEP_MESSAGES = 3

    /** Today's step is available if the journey is active and no step was completed today. */
    fun stepAvailableToday(j: Journey, today: LocalDate): Boolean =
        j.status == Journey.ACTIVE && j.currentDay <= j.totalDays && j.lastStepDate != today.toString()

    /**
     * After a journey session with real talk in it. Never more than one step per day. [forDay]: the day the session
     * was about; a conversation about day 3 never completes day 4 (say, when it carries on past midnight).
     */
    fun advance(j: Journey, today: LocalDate, now: Long = System.currentTimeMillis(), forDay: Int? = null): Journey {
        if (!stepAvailableToday(j, today)) return j
        if (forDay != null && forDay != j.currentDay) return j
        val next = j.currentDay + 1
        val done = next > j.totalDays
        return j.copy(
            currentDay = next,
            lastStepDate = today.toString(),
            status = if (done) Journey.DONE else Journey.ACTIVE,
            completedAt = if (done) now else j.completedAt,
        )
    }

    /** Days already done, for the row of dots. */
    fun daysDone(j: Journey): Int = (j.currentDay - 1).coerceIn(0, j.totalDays)
}

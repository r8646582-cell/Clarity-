package com.umair.purpose.ledger

import com.umair.purpose.data.db.Promise
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Phase 3 values-versus-behavior ledger. Code counts; the model only describes the gap.
 *
 * Evidence is limited to promises he made and then resolved as kept or broken (resolved by his own word or by the
 * coach recording what he said, never by a conversation merely happening). Promises from off-the-record
 * conversations are left out. A value is tied to behavior by the life area the promise was filed under or by the
 * value's own words appearing in the promise, so a value with no such promises is reported as untracked rather
 * than guessed at.
 */
object ValuesLedger {
    const val WEEKS = 4
    /** A value shows numbers only once this many promises behind it have been resolved in the window. */
    const val MIN = 2

    /** Plain keywords in a value's name → the life areas ([com.umair.purpose.data.db.AreaStatus.AREAS]) it lives in. */
    private val AREA_WORDS: Map<String, List<String>> = mapOf(
        "health" to listOf("health"), "fitness" to listOf("health"), "body" to listOf("health"), "sleep" to listOf("health"),
        "family" to listOf("relationships"), "friend" to listOf("relationships"), "love" to listOf("relationships"),
        "relationship" to listOf("relationships"), "loyal" to listOf("relationships"), "community" to listOf("relationships"),
        "learn" to listOf("studies_career"), "knowledge" to listOf("studies_career"), "study" to listOf("studies_career"),
        "career" to listOf("studies_career"), "achievement" to listOf("studies_career"), "ambition" to listOf("studies_career"),
        "excellence" to listOf("studies_career"), "work" to listOf("studies_career"), "success" to listOf("studies_career"),
        "money" to listOf("money"), "wealth" to listOf("money"), "financial" to listOf("money"), "security" to listOf("money"),
        "faith" to listOf("meaning"), "spirit" to listOf("meaning"), "purpose" to listOf("meaning"), "meaning" to listOf("meaning"),
        "service" to listOf("meaning"),
        "discipline" to listOf("habits"), "habit" to listOf("habits"), "consisten" to listOf("habits"), "routine" to listOf("habits"),
        "integrity" to listOf("character"), "honest" to listOf("character"), "character" to listOf("character"),
        "courage" to listOf("character"), "respons" to listOf("character"), "reliab" to listOf("character"),
        "growth" to listOf("mindset"), "calm" to listOf("eq"), "patience" to listOf("eq"), "empathy" to listOf("eq"),
        "balance" to listOf("rest_joy"), "joy" to listOf("rest_joy"), "fun" to listOf("rest_joy"), "rest" to listOf("rest_joy"),
        "creativ" to listOf("rest_joy"), "freedom" to listOf("rest_joy"),
    )

    /** One calendar week (Monday to Sunday) of resolved promises. */
    data class Week(val start: LocalDate, val kept: Int, val broken: Int) {
        val resolved: Int get() = kept + broken
    }

    data class Row(val value: String, val weeks: List<Week>) {
        val kept: Int get() = weeks.sumOf { it.kept }
        val resolved: Int get() = weeks.sumOf { it.resolved }
        /** Whole-window kept rate, or null when too few promises stand behind it. */
        val rate: Int? get() = if (resolved >= MIN) kept * 100 / resolved else null
        val tracked: Boolean get() = resolved >= MIN
    }

    /** Life areas a value's own words point to. */
    fun areasFor(value: String): Set<String> {
        val v = value.lowercase()
        return AREA_WORDS.filterKeys { it in v }.values.flatten().toSet()
    }

    /** True when this resolved promise counts toward [value]. */
    internal fun belongs(p: Promise, value: String, areas: Set<String>): Boolean {
        if (p.area != null && p.area in areas) return true
        val words = value.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 4 }
        if (words.isEmpty()) return false
        val hay = (p.text + " " + (p.actionKey ?: "").replace('_', ' ')).lowercase()
        return words.any { it in hay }
    }

    /** Monday of the week [d] falls in. */
    internal fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * One row per value, newest week last. Only promises resolved on or before [today] count, so a letter written
     * for a past period never sees later behavior.
     */
    fun compute(values: List<String>, promises: List<Promise>, today: LocalDate, zone: ZoneId): List<Row> {
        val firstWeek = weekStart(today).minusWeeks((WEEKS - 1).toLong())
        val resolved = promises.filter { !it.offTheRecord && (it.status == Promise.KEPT || it.status == Promise.BROKEN) }
            .mapNotNull { p -> p.resolvedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.let { d -> p to d } }
            .filter { (_, d) -> !d.isAfter(today) && !d.isBefore(firstWeek) }
        return values.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.map { value ->
            val areas = areasFor(value)
            val mine = resolved.filter { (p, _) -> belongs(p, value, areas) }
            Row(value, (0 until WEEKS).map { i ->
                val start = firstWeek.plusWeeks(i.toLong())
                val inWeek = mine.filter { (_, d) -> weekStart(d) == start }.map { it.first }
                Week(start, inWeek.count { it.status == Promise.KEPT }, inWeek.count { it.status == Promise.BROKEN })
            })
        }
    }

    /**
     * Compact plain lines for the context block and the letters. Numbers only; whatever it implies is for the
     * model to word carefully ("describe the gap", not diagnose it).
     */
    fun lines(rows: List<Row>): List<String> {
        if (rows.isEmpty()) return emptyList()
        val tracked = rows.filter { it.tracked }
        val untracked = rows.filterNot { it.tracked }
        val out = mutableListOf<String>()
        if (tracked.isNotEmpty()) {
            out += "Values versus kept promises, last $WEEKS weeks (counted from promises he resolved; oldest week first):"
            tracked.forEach { r ->
                out += "- ${r.value}: kept ${r.kept} of ${r.resolved} (${r.rate}%); by week " +
                    r.weeks.joinToString(", ") { if (it.resolved == 0) "none" else "${it.kept}/${it.resolved}" } + "."
            }
        }
        if (untracked.isNotEmpty())
            out += "No promise record yet for these values (say so, don't guess): " + untracked.joinToString(", ") { it.value } + "."
        return out
    }
}

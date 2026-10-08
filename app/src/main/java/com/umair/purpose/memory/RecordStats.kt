package com.umair.purpose.memory

import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Pulse
import com.umair.purpose.data.db.Session
import com.umair.purpose.promise.due
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * "What the record shows": short plain lines computed from the database, never by the AI.
 * A line only appears when at least [MIN] items stand behind each number in it.
 * Links between things are described as they happened together, never as causes.
 */
object RecordStats {
    const val MIN = 3

    fun lines(
        promises: List<Promise>,
        sessions: List<Session>,
        pulses: List<Pulse>,
        today: LocalDate,
        zone: ZoneId,
    ): List<String> = listOfNotNull(
        promiseLine(promises, today, zone),
        areaLine(promises),
        dueTimeLine(promises),
        whenHeTalks(sessions, zone),
        toneLine(sessions, today, zone),
        moodWeekLine(pulses, today),
        moodPromiseLine(pulses, promises, zone),
        moodLateNightLine(pulses, sessions, zone),
    )

    private fun resolved(promises: List<Promise>) =
        promises.filter { !it.offTheRecord && (it.status == Promise.KEPT || it.status == Promise.BROKEN) }

    internal fun promiseLine(promises: List<Promise>, today: LocalDate, zone: ZoneId): String? {
        val done = resolved(promises)
        if (done.size < MIN) return null
        val parts = mutableListOf("Promises: kept ${done.count { it.status == Promise.KEPT }} of ${done.size} overall")
        val since = today.minusDays(30)
        val recent = done.filter { p -> p.resolvedAt?.let { date(it, zone) }?.isAfter(since) == true }
        if (recent.size >= MIN) parts += "${recent.count { it.status == Promise.KEPT }} of ${recent.size} in the last 30 days"
        var line = parts.joinToString(", ") + "."
        val run = done.sortedByDescending { it.resolvedAt ?: it.createdAt }.takeWhile { it.status == Promise.KEPT }.size
        if (run >= MIN) line += " Current run: $run kept in a row."
        return line
    }

    internal fun areaLine(promises: List<Promise>): String? {
        val byArea = resolved(promises).filter { !it.area.isNullOrBlank() }.groupBy { it.area!! }
            .filterValues { it.size >= MIN }
        if (byArea.isEmpty()) return null
        return "Kept rate by area: " + byArea.entries.sortedByDescending { it.value.size }
            .joinToString(", ") { (area, ps) -> "${area.replace('_', ' ')} ${ps.count { it.status == Promise.KEPT }}/${ps.size}" } + "."
    }

    internal fun dueTimeLine(promises: List<Promise>): String? {
        val buckets = resolved(promises).mapNotNull { p -> p.due?.time?.let { bucket(it.hour) to p } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= MIN }
        if (buckets.isEmpty()) return null
        return "Kept rate by due time: " + BUCKETS.filter { it in buckets }
            .joinToString(", ") { b -> buckets.getValue(b).let { "$b ${it.count { p -> p.status == Promise.KEPT }}/${it.size}" } } + "."
    }

    private val BUCKETS = listOf("morning", "afternoon", "evening", "after 11pm")

    internal fun bucket(hour: Int) = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..22 -> "evening"
        else -> "after 11pm"
    }

    /** The 3-hour window where most of his conversations start, if it holds at least half of them. */
    internal fun whenHeTalks(sessions: List<Session>, zone: ZoneId): String? {
        val hours = sessions.filter { it.userMessageCount > 0 }
            .map { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour }
        if (hours.size < 5) return null
        val best = (0..23).maxBy { start -> hours.count { h -> (h - start + 24) % 24 < 3 } }
        val inWindow = hours.count { h -> (h - best + 24) % 24 < 3 }
        if (inWindow * 2 < hours.size || inWindow < MIN) return null
        return "When he talks: mostly ${hourLabel(best)} to ${hourLabel((best + 3) % 24)}."
    }

    internal fun hourLabel(h: Int) = when {
        h == 0 -> "midnight"
        h == 12 -> "noon"
        h < 12 -> "${h}am"
        else -> "${h - 12}pm"
    }

    internal fun toneLine(sessions: List<Session>, today: LocalDate, zone: ZoneId): String? {
        val since = today.minusDays(14)
        val tones = sessions.filter { !it.tone.isNullOrBlank() && date(it.startedAt, zone).isAfter(since) }
            .sortedBy { it.startedAt }.map { it.tone!!.trim() }
        if (tones.size < MIN) return null
        return "Tone over the last 2 weeks, oldest to newest: " + tones.joinToString(" → ") + "."
    }

    internal fun moodWeekLine(pulses: List<Pulse>, today: LocalDate): String? {
        val byDate = pulses.mapNotNull { p -> parse(p.date)?.let { it to p } }
        val thisWeek = byDate.filter { (d, _) -> !d.isBefore(today.minusDays(6)) && !d.isAfter(today) }.map { it.second }
        val lastWeek = byDate.filter { (d, _) -> !d.isBefore(today.minusDays(13)) && d.isBefore(today.minusDays(6)) }.map { it.second }
        if (thisWeek.size < MIN || lastWeek.size < MIN) return null
        return "Mood and energy (out of 5): this week ${avg(thisWeek.map { it.mood })} and ${avg(thisWeek.map { it.energy })}, " +
            "last week ${avg(lastWeek.map { it.mood })} and ${avg(lastWeek.map { it.energy })}."
    }

    internal fun moodPromiseLine(pulses: List<Pulse>, promises: List<Promise>, zone: ZoneId): String? {
        val keptDays = promises.filter { it.status == Promise.KEPT }.mapNotNull { it.resolvedAt?.let { t -> date(t, zone) } }.toSet()
        val brokenDays = promises.filter { it.status == Promise.BROKEN }.mapNotNull { it.resolvedAt?.let { t -> date(t, zone) } }.toSet()
        val onKept = pulses.filter { it.on(keptDays) }
        val onOther = pulses.filter { !it.on(keptDays) && it.on(brokenDays) }
        if (onKept.size < MIN || onOther.size < MIN) return null
        return "Mood was ${avg(onKept.map { it.mood })} on days he kept a promise and ${avg(onOther.map { it.mood })} " +
            "on days he broke one."
    }

    /** Pulse on the day after a conversation that started at 11pm or later (or before 3am), vs other days. */
    internal fun moodLateNightLine(pulses: List<Pulse>, sessions: List<Session>, zone: ZoneId): String? {
        val afterLate = sessions.filter { it.userMessageCount > 0 }.mapNotNull { s ->
            val t = Instant.ofEpochMilli(s.startedAt).atZone(zone)
            when {
                t.hour >= 23 -> t.toLocalDate().plusDays(1)
                t.hour < 3 -> t.toLocalDate()
                else -> null
            }
        }.toSet()
        val late = pulses.filter { it.on(afterLate) }
        val other = pulses.filter { !it.on(afterLate) }
        if (late.size < MIN || other.size < MIN) return null
        return "Mood was ${avg(late.map { it.mood })} the day after a late-night conversation, and ${avg(other.map { it.mood })} on other days."
    }

    private fun avg(xs: List<Int>) = String.format(Locale.US, "%.1f", xs.average())

    private fun Pulse.on(days: Set<LocalDate>): Boolean = parse(date)?.let { it in days } == true

    private fun parse(d: String): LocalDate? = runCatching { LocalDate.parse(d) }.getOrNull()

    private fun date(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}

package com.umair.purpose.screen

import com.umair.purpose.data.db.ScreenUsage
import java.time.Instant
import java.time.ZoneId

/** One foreground change from the phone: an app (by an opaque key, never stored) came to the front or left it. */
data class UsageEv(val appKey: String, val category: String, val at: Long, val resumed: Boolean)

/**
 * Phase 4: turns the phone's foreground events into minutes per day, hour and app category. Pure, so it is tested
 * on a plain JVM. Only the category is kept ("social", "video", ...): never which app, never what was on screen.
 */
object ScreenTimeAggregator {
    private const val HOUR_MS = 3_600_000L

    /**
     * [events] in any order; [windowEnd] closes an app still in front when the window ends. An app resumed twice
     * without a pause restarts its clock rather than double counting. Minutes are rounded per day/hour/category.
     */
    fun aggregate(events: List<UsageEv>, windowEnd: Long, zone: ZoneId): List<ScreenUsage> {
        val ms = HashMap<Triple<String, Int, String>, Long>()
        val open = HashMap<String, Pair<Long, String>>()

        fun close(start: Long, end: Long, category: String) {
            if (end <= start) return
            var t = start
            while (t < end) {
                val z = Instant.ofEpochMilli(t).atZone(zone)
                val nextHour = z.toLocalDate().atTime(z.hour, 0).atZone(zone).toInstant().toEpochMilli() + HOUR_MS
                val stop = minOf(end, if (nextHour > t) nextHour else end)
                val key = Triple(z.toLocalDate().toString(), z.hour, category)
                ms[key] = (ms[key] ?: 0L) + (stop - t)
                t = stop
            }
        }

        for (e in events.sortedBy { it.at }) {
            if (e.resumed) {
                open[e.appKey]?.let { (start, cat) -> close(start, e.at, cat) }
                open[e.appKey] = e.at to e.category
            } else {
                open.remove(e.appKey)?.let { (start, cat) -> close(start, e.at, cat) }
            }
        }
        open.values.forEach { (start, cat) -> close(start, windowEnd, cat) }

        return ms.mapNotNull { (k, v) ->
            val minutes = ((v + 30_000L) / 60_000L).toInt()
            if (minutes > 0) ScreenUsage(date = k.first, hour = k.second, category = k.third, minutes = minutes) else null
        }.sortedWith(compareBy({ it.date }, { it.hour }, { it.category }))
    }
}

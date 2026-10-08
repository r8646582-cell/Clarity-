package com.umair.purpose.growth

import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.Branch
import com.umair.purpose.data.db.Milestone
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** prompts/milestone.md output. */
@Serializable
data class MilestoneResult(
    val proposals: List<Proposal> = emptyList(),
    @kotlinx.serialization.SerialName("considered_but_not_yet") val consideredButNotYet: List<NotYet> = emptyList(),
) {
    @Serializable
    data class Proposal(
        val type: String = "",
        val area: String = "",
        val branch: String? = null,
        val title: String = "",
        val description: String = "",
        val evidence: List<String> = emptyList(),
        val confidence: String = "",
    )

    @Serializable
    data class NotYet(val title: String = "", val missing: String = "")
}

class MilestoneParseException(message: String) : Exception(message)

/**
 * UPDATE-18: what a milestone proposal must pass before he ever sees it. The model proposes; code checks: high
 * confidence only, a real type and area, never something already on the tree, declined or removed, never a
 * second proposal while one is snoozed, at most 2 per run and 3 a month, and habit leaves only when the computed
 * evidence meets the minimum. Pure, so it's tested.
 */
object MilestoneRules {
    const val PER_RUN = 2
    const val PER_MONTH = 3
    const val SNOOZE_DAYS = 30L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parse(raw: String): MilestoneResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw MilestoneParseException("No JSON object in reply")
        return try {
            json.decodeFromString(MilestoneResult.serializer(), raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw MilestoneParseException("Reply was not valid milestone JSON")
        }
    }

    /** The new proposals to store, already checked. [existing]: every milestone ever (any status). */
    fun accept(
        result: MilestoneResult,
        existing: List<Milestone>,
        branches: List<Branch>,
        stats: EvidenceStats,
        now: Long,
        zone: ZoneId,
    ): List<Milestone> {
        val month = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        val thisMonth = existing.count {
            it.status != Milestone.CONSIDERED && YearMonth.from(Instant.ofEpochMilli(it.proposedAt).atZone(zone)) == month
        }
        var room = minOf(PER_RUN, PER_MONTH - thisMonth)
        if (room <= 0) return emptyList()
        val out = mutableListOf<Milestone>()
        for (p in result.proposals) {
            if (room <= 0) break
            val type = p.type.trim().lowercase()
            val area = p.area.trim().lowercase()
            val title = p.title.trim().trim('"').take(80)
            val description = p.description.trim()
            if (p.confidence.trim().lowercase() != "high") continue
            if (type !in Milestone.TYPES || area !in AreaStatus.AREAS || title.isEmpty() || description.isEmpty()) continue
            if (p.evidence.none { it.isNotBlank() }) continue
            // Absence of data is not evidence: habit leaves need the computed minimum to be met.
            if (type == "habit_built" && !stats.anyHabitBuilt) continue
            if (type == "habit_unlearned" && !stats.anyUnlearned) continue
            if (blocked(title, existing + out)) continue
            out += Milestone(
                type = type,
                area = area,
                branch = branchName(p.branch, area, branches),
                title = title,
                description = description,
                evidenceJson = encodeEvidence(p.evidence.map { it.trim() }.filter { it.isNotEmpty() }.take(6)),
                confidence = "high",
                proposedAt = now,
                status = Milestone.PROPOSED,
                quote = p.evidence.firstNotNullOfOrNull { quoteIn(it) },
            )
            room--
        }
        return out
    }

    /** "considered_but_not_yet": kept only as context for the next run. */
    fun considered(result: MilestoneResult, now: Long): List<Milestone> = result.consideredButNotYet
        .filter { it.title.isNotBlank() }.take(5)
        .map {
            Milestone(
                type = "considered", area = "", title = it.title.trim().take(80), description = it.missing.trim(),
                confidence = "", proposedAt = now, status = Milestone.CONSIDERED,
            )
        }

    /**
     * Never again: already a leaf, removed, or declined. Not yet: waiting, or snoozed (it comes back by itself
     * after 30 days). The same idea in other words counts as the same.
     */
    fun blocked(title: String, existing: List<Milestone>): Boolean = existing.any { m ->
        if (m.status == Milestone.CONSIDERED) return@any false
        // Snoozed ones come back by themselves when their 30 days are up, so a new copy is never needed.
        same(m.title, title) && m.status in setOf(Milestone.ACCEPTED, Milestone.REMOVED, Milestone.DECLINED, Milestone.PROPOSED, Milestone.SNOOZED)
    }

    fun snoozeUntil(now: Long) = now + SNOOZE_DAYS * DAY_MS

    /** Reuses an existing branch's exact name (any case); a new one is short and plain. Null: the area itself. */
    fun branchName(raw: String?, area: String, branches: List<Branch>): String? {
        val b = raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) } ?: return null
        if (b.equals(area, true) || b.replace(' ', '_').equals(area, true)) return null
        branches.firstOrNull { it.area == area && it.name.equals(b, true) }?.let { return it.name }
        return b.take(24)
    }

    fun same(a: String, b: String): Boolean {
        val ta = words(a)
        val tb = words(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta == tb) return true
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size >= 0.6
    }

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 }.toSet()

    private fun quoteIn(evidence: String): String? =
        Regex("""['"“‘](.{8,200}?)['"”’]""").find(evidence)?.groupValues?.get(1)?.trim()

    private val listJson = Json
    fun encodeEvidence(lines: List<String>): String = listJson.encodeToString(ListSerializer(String.serializer()), lines)
    fun decodeEvidence(s: String): List<String> =
        runCatching { listJson.decodeFromString(ListSerializer(String.serializer()), s) }.getOrDefault(emptyList())

    /** {{TREE}}: what's on the tree, and what was declined, snoozed, removed or not yet earned. */
    fun treeBlock(all: List<Milestone>, zone: ZoneId): String {
        fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: "?"
        val lines = buildList {
            all.filter { it.status == Milestone.ACCEPTED }.forEach {
                add("- leaf (${it.type}, ${it.area}${it.branch?.let { b -> " / $b" } ?: ""}), accepted ${day(it.decidedAt)}: ${it.title}")
            }
            all.filter { it.status == Milestone.PROPOSED }.forEach { add("- proposed ${day(it.proposedAt)}, waiting for his answer: ${it.title}") }
            all.filter { it.status == Milestone.DECLINED }.forEach { add("- declined ${day(it.decidedAt)} (never propose again): ${it.title}") }
            all.filter { it.status == Milestone.REMOVED }.forEach { add("- removed by him ${day(it.decidedAt)} (never propose again): ${it.title}") }
            all.filter { it.status == Milestone.SNOOZED }.forEach { add("- not yet (snoozed until ${day(it.snoozeUntil)}): ${it.title}") }
            all.filter { it.status == Milestone.CONSIDERED }.forEach { add("- considered last time, not yet: ${it.title}. Missing: ${it.description}") }
        }
        return lines.joinToString("\n").ifEmpty { "(the tree is empty: only its roots so far)" }
    }

    /** {{BRANCHES}} */
    fun branchesBlock(areas: List<AreaStatus>, branches: List<Branch>): String = AreaStatus.AREAS.joinToString("\n") { a ->
        val status = areas.firstOrNull { it.area == a && !it.deletedByUser }?.status
        val subs = branches.filter { it.area == a }.map { it.name }
        "- $a" + (status?.let { " ($it)" } ?: "") + (if (subs.isEmpty()) "" else ": branches ${subs.joinToString(", ")}")
    }
}

package com.umair.purpose.growth

import androidx.room.withTransaction
import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiDefaults
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.data.db.Branch
import com.umair.purpose.data.db.Milestone
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.prompt.Templates
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPDATE-18: the growth tree's proposals. Weekly (in the letter job, before the Sunday letter) and after a
 * journey's last day, prompts/milestone.md reads evidence counted in code and may propose a leaf; code checks
 * every proposal (MilestoneRules) before it reaches the Talk card. He decides: accept, not yet, or never.
 */
@Singleton
class GrowthEngine @Inject constructor(
    private val db: PurposeDatabase,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val ai: AiClient,
    private val uiPrefs: UiPrefs,
) {
    enum class Trigger { WEEKLY, JOURNEY_DONE }

    private val dao = db.milestoneDao()

    fun observeLeaves(): Flow<List<Milestone>> = dao.observeLeaves()
    fun observeWaiting(now: Long = System.currentTimeMillis()): Flow<List<Milestone>> = dao.observeWaiting(now)
    fun observeBranches(): Flow<List<Branch>> = db.branchDao().observeAll()

    /**
     * The weekly run, if a week has passed. Returns a closing line for the weekly letter when a proposal is
     * waiting for him, or null.
     */
    suspend fun runIfDue(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? = db.datasetWork.withWriter { runDueCurrentDataset(now, zone) }

    private suspend fun runDueCurrentDataset(now: Long, zone: ZoneId): String? {
        val last = uiPrefs.job("milestones").lastOkAt ?: 0L
        if (now - last >= WEEK_MS - HOUR_MS) {
            run(Trigger.WEEKLY, now, zone)
            uiPrefs.recordJob("milestones", ok = true, at = now)
        }
        return letterLine(now)
    }

    /** Proposals waiting for him that the last weekly letter didn't mention. */
    private suspend fun letterLine(now: Long): String? {
        val lastWeekly = db.letterDao().all().filter { it.kind == com.umair.purpose.data.db.Letter.WEEKLY }.maxOfOrNull { it.createdAt } ?: 0L
        val fresh = dao.all().filter { it.status == Milestone.PROPOSED && it.proposedAt > lastWeekly && it.proposedAt <= now }
        return when (fresh.size) {
            0 -> null
            1 -> "Something I've noticed: \"${fresh[0].title}\". It's waiting for you on Talk, if you'd like to add it to your tree."
            else -> "Something I've noticed, twice this week: " + fresh.joinToString(" and ") { "\"${it.title}\"" } +
                ". They're waiting for you on Talk, if you'd like to add them to your tree."
        }
    }

    /** Asks milestone.md once and stores what passes the rules. Returns how many proposals were added. */
    suspend fun run(trigger: Trigger, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int = db.datasetWork.withWriter { runCurrentDataset(trigger, now, zone) }

    private suspend fun runCurrentDataset(trigger: Trigger, now: Long, zone: ZoneId): Int {
        val sessions = db.sessionDao().all()
        // UPDATE-17: no wasted calls. Too little real conversation yet, and there can be no evidence.
        if (sessions.count { it.meaningful } < MIN_MEANINGFUL_SESSIONS) return 0
        val today = LocalDate.now(zone)
        val existing = dao.all()
        // Three proposals this month already: nothing more can be shown, so no call.
        if (monthFull(existing, now, zone)) return 0
        val stats = Evidence.compute(
            today = today, zone = zone,
            promises = db.promiseDao().all(),
            notes = db.noteDao().all(),
            sessions = sessions,
            userMessageTimes = db.messageDao().userMessageTimesSince(today.minusDays(120).atStartOfDay(zone).toInstant().toEpochMilli()),
            pulses = db.pulseDao().since(today.minusDays(27).toString()),
            journeys = db.journeyDao().all(),
            adjustments = db.journeyAdjustmentDao().all(),
        )
        val since = today.minusDays(90).atStartOfDay(zone).toInstant().toEpochMilli()
        val branches = db.branchDao().all()
        val values = mapOf(
            "TREE" to MilestoneRules.treeBlock(existing, zone),
            "STATS" to Evidence.format(stats),
            "NOTES" to db.noteDao().all().filter { it.status != Note.DELETED_BY_USER }.sortedBy { it.id }.joinToString("\n") { n ->
                "- [${n.type}, ${n.confidence}, ${n.status}, seen ${n.timesSeen}x, first ${ContextFormatter.date(n.firstSeen, zone)}, " +
                    "last ${ContextFormatter.date(n.lastSeen, zone)}] ${n.text}"
            }.ifEmpty { "(none)" },
            "SUMMARIES" to sessions.filter { it.startedAt >= since && !it.summary.isNullOrBlank() }.sortedBy { it.startedAt }
                .joinToString("\n") { "- ${ContextFormatter.date(it.startedAt, zone)}: ${it.summary!!.trim()}" }.ifEmpty { "(none)" },
            "QUOTES" to db.quoteDao().since(since).joinToString("\n") { "- ${ContextFormatter.date(it.createdAt, zone)}: \"${it.text}\"" }.ifEmpty { "(none)" },
            "BRANCHES" to MilestoneRules.branchesBlock(db.areaDao().all(), branches),
        )
        val cfg = settings.get().ai
        val ask = if (trigger == Trigger.JOURNEY_DONE) "He has just finished a journey. Consider the evidence now and return the JSON object."
        else "Consider the evidence now and return the JSON object."
        val request = AiRequest(
            model = cfg.deepModel,
            messages = listOf(AiMessage(Role.SYSTEM, Templates.fill(prompts.load("milestone.md"), values)), AiMessage(Role.USER, ask)),
            temperature = cfg.reflectionTemperature,
            jsonOutput = true,
            maxTokens = AiDefaults.MILESTONE_MAX_TOKENS,
        )
        val c = ai.complete(request)
        c.usage?.let { usage.log("milestone", request.model, it, System.currentTimeMillis()) }
        val result = MilestoneRules.parse(c.text)
        return db.withTransaction {
            // Checked against a fresh read, so a proposal can't slip past one he answered meanwhile.
            val fresh = MilestoneRules.accept(result, dao.all(), db.branchDao().all(), stats, now, zone)
            dao.clearConsidered()
            dao.insertAll(fresh + MilestoneRules.considered(result, now))
            fresh.size
        }
    }

    /**
     * UPDATE-20: a leaf the coach proposed autonomously with `[[action: {type: trigger_milestone}]]`. It is stored
     * as a normal proposal, so it still waits on Talk for his answer, and the monthly cap and never-again rules
     * apply exactly as they do to the weekly run.
     */
    suspend fun proposeFromChat(title: String, evidenceSummary: String, now: Long = System.currentTimeMillis()): Milestone? = db.withTransaction {
        val t = title.trim().trim('"').take(80)
        if (t.isEmpty()) return@withTransaction null
        val lines = evidenceSummary.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        if (lines.isEmpty()) return@withTransaction null
        val all = dao.all()
        if (MilestoneRules.blocked(t, all) || monthFull(all, now, ZoneId.systemDefault())) return@withTransaction null
        val m = Milestone(
            type = "accomplishment",
            area = "meaning",
            title = t,
            description = evidenceSummary.trim().take(300),
            evidenceJson = MilestoneRules.encodeEvidence(lines),
            confidence = "medium",
            proposedAt = now,
            status = Milestone.PROPOSED,
        )
        m.copy(id = dao.insert(m))
    }

    /**
     * The canonical `award_milestone` action: records the evidence the coach gathered and, if it earns a leaf,
     * stores a normal proposal on the tree. The same monthly cap, never-again and evidence rules apply as to the
     * weekly run, and he still accepts it himself before it becomes a leaf.
     */
    suspend fun recordEvidence(actionKey: String, evidence: String, now: Long = System.currentTimeMillis()): Milestone? {
        val key = actionKey.trim()
        val proof = evidence.trim()
        if (key.isEmpty() && proof.isEmpty()) return null
        val title = proof.ifBlank { key }.trim().trim('"').take(80)
        return proposeFromChat(title, proof.ifBlank { key }, now)
    }

    /**
     * A finished journey is evidence in itself: it earns a proposal deterministically, without an AI run and
     * without the [MIN_MEANINGFUL_SESSIONS] gate. He still accepts it on Talk, and the monthly cap and
     * never-again rules apply exactly as to every other proposal.
     */
    suspend fun recordJourneyCompletion(name: String, now: Long = System.currentTimeMillis()): Milestone? {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return null
        return proposeFromChat(
            title = "Completed Journey: $clean",
            evidenceSummary = "He finished the journey \"$clean\".",
            now = now,
        )
    }

    /** "Add to your tree". Its branch appears with it. Returns the leaf, for the growing animation. */
    suspend fun accept(id: Long, now: Long = System.currentTimeMillis()): Milestone? = db.withTransaction {
        val m = dao.get(id)?.takeIf { it.status == Milestone.PROPOSED || it.status == Milestone.SNOOZED } ?: return@withTransaction null
        m.branch?.let { db.branchDao().insert(Branch(area = m.area, name = it, createdAt = now)) }
        m.copy(status = Milestone.ACCEPTED, decidedAt = now, snoozeUntil = null).also { dao.upsert(it) }
    }

    /** "Not yet": back in 30 days. */
    suspend fun snooze(id: Long, now: Long = System.currentTimeMillis()) {
        val m = dao.get(id)?.takeIf { it.status == Milestone.PROPOSED || it.status == Milestone.SNOOZED } ?: return
        dao.upsert(m.copy(status = Milestone.SNOOZED, decidedAt = now, snoozeUntil = MilestoneRules.snoozeUntil(now)))
    }

    /** "This isn't right": never proposed again. */
    suspend fun decline(id: Long, now: Long = System.currentTimeMillis()) {
        val m = dao.get(id)?.takeIf { it.status == Milestone.PROPOSED || it.status == Milestone.SNOOZED } ?: return
        dao.upsert(m.copy(status = Milestone.DECLINED, decidedAt = now))
    }

    /** Long-press → Remove: off the tree, and never proposed again. */
    suspend fun remove(id: Long, now: Long = System.currentTimeMillis()) {
        val m = dao.get(id)?.takeIf { it.status == Milestone.ACCEPTED } ?: return
        dao.upsert(m.copy(status = Milestone.REMOVED, decidedAt = now))
    }

    private fun monthFull(existing: List<Milestone>, now: Long, zone: ZoneId): Boolean {
        val month = java.time.YearMonth.from(java.time.Instant.ofEpochMilli(now).atZone(zone))
        return existing.count {
            it.status != Milestone.CONSIDERED && java.time.YearMonth.from(java.time.Instant.ofEpochMilli(it.proposedAt).atZone(zone)) == month
        } >= MilestoneRules.PER_MONTH
    }

    companion object {
        private const val HOUR_MS = 60L * 60 * 1000
        private const val WEEK_MS = 7 * 24 * HOUR_MS
        /** Below this, nothing could meet any minimum: no call at all. */
        const val MIN_MEANINGFUL_SESSIONS = 3
    }
}

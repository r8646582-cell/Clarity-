package com.umair.purpose.letter

import androidx.room.withTransaction
import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiException
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.prompt.Templates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Writes Mirror letters with letter_weekly.md and letter_monthly.md, using the stronger model. */
@Singleton
class LetterWriter @Inject constructor(
    private val db: PurposeDatabase,
    private val memory: MemoryRepository,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val ai: AiClient,
    private val search: com.umair.purpose.memory.SearchIndex,
) {
    private val letters = db.letterDao()
    private val sessions = db.sessionDao()

    /** Letters owed by the schedule right now. */
    suspend fun due(now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): List<LetterPeriod> {
        val all = sessions.all()
        val first = all.minOfOrNull { it.startedAt }?.let { ContextFormatter.date(it, zone) }
        val existing = letters.all()
        return LetterPlanning.due(now, existing, first) { p -> hasMeaningful(all, p, zone, existing) }
    }

    /** "Write this week's letter now" is possible once there's a meaningful conversation since the last weekly letter. */
    suspend fun canWriteThisWeek(): Boolean {
        val lastWeekly = letters.all().filter { it.kind == Letter.WEEKLY }.maxOfOrNull { it.createdAt } ?: 0L
        return sessions.all().any { it.meaningful && it.startedAt > lastWeekly }
    }

    suspend fun thisWeekPeriod(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LetterPeriod {
        val first = sessions.firstStartedAt()?.let { ContextFormatter.date(it, zone) }
        return LetterPlanning.thisWeekNow(today, letters.all(), first)
    }

    /** Developer menu: any conversation he took part in this week (Monday to today). */
    suspend fun hadConversationThisWeek(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val p = testWeekPeriod(today)
        return sessions.all().any { it.userMessageCount > 0 && ContextFormatter.date(it.startedAt, zone) in p }
    }

    /** Developer menu: the 1st of this month to today, as a monthly letter. */
    fun testMonthPeriod(today: LocalDate) = LetterPeriod(Letter.MONTHLY, today.withDayOfMonth(1), today)

    suspend fun hadConversationIn(p: LetterPeriod, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        sessions.all().any { it.userMessageCount > 0 && ContextFormatter.date(it.startedAt, zone) in p }

    /** Monday of this week to today. */
    fun testWeekPeriod(today: LocalDate) = LetterPeriod(Letter.WEEKLY, today.with(java.time.DayOfWeek.MONDAY), today)

    /**
     * [test]: written even if a letter for this period exists (Developer menu). [appendix]: a closing line added
     * after the letter (UPDATE-18: a growth-tree proposal waiting on Talk).
     */
    suspend fun write(period: LetterPeriod, zone: ZoneId = ZoneId.systemDefault(), test: Boolean = false, appendix: String? = null): Long =
        db.datasetWork.withWriter { writeCurrentDataset(period, zone, test, appendix) }

    private suspend fun writeCurrentDataset(period: LetterPeriod, zone: ZoneId, test: Boolean, appendix: String?): Long {
        val all = sessions.all()
        val existing = letters.all()
        val inPeriod = all.filter { LetterPlanning.belongs(period, ContextFormatter.date(it.startedAt, zone), it.startedAt, existing) }
        // Nothing of his in the period: never send an empty payload to the model when write() is called outside the
        // scheduler (the scheduler only ever offers periods with a meaningful conversation). -1 means "not written".
        if (inPeriod.none { it.userMessageCount > 0 }) return -1L
        val inputs = LetterInputs(
            // Every moment and quote of the period (a yearly letter reads the whole year).
            memory = memory.memory(since = period.start.atStartOfDay(zone).toInstant().toEpochMilli()),
            sessions = inPeriod,
            messages = inPeriod.associate { it.id to db.messageDao().forSession(it.id) },
            record = memory.record(period.end, zone),
            previousLetter = if (period.kind == Letter.WEEKLY) {
                existing.filter { it.kind == Letter.WEEKLY && LocalDate.parse(it.periodEnd).isBefore(period.start) }
                    .maxByOrNull { it.periodEnd }
            } else null,
            subLetters = when (period.kind) {
                Letter.MONTHLY -> existing.filter { it.kind == Letter.WEEKLY && overlaps(it, period) }
                Letter.YEARLY -> existing.filter { it.kind == Letter.MONTHLY && overlaps(it, period) }
                else -> emptyList()
            },
        )
        val prompt = if (period.kind == Letter.WEEKLY) "letter_weekly.md" else "letter_monthly.md"
        val appSettings = settings.get()
        val cfg = appSettings.ai
        val jobModel = appSettings.jobModel(com.umair.purpose.ai.AiJob.LETTERS)
        val request = AiRequest(
            model = jobModel.model,
            useBackup = jobModel.useBackup,
            messages = listOf(
                AiMessage(Role.SYSTEM, Templates.fill(prompts.load(prompt), LetterFormatter.values(period, inputs, zone))),
                AiMessage(Role.USER, "Write the letter now."),
            ),
            temperature = cfg.chatTemperature,
            thinking = true,
            maxTokens = com.umair.purpose.ai.AiDefaults.LETTER_MAX_TOKENS,
        )
        val c = ai.complete(request)
        val now = System.currentTimeMillis()
        c.usage?.let { usage.log("letter", request.model, it, now) }
        val (title, content) = LetterPlanning.split(c.text.ifBlank { throw AiException("The letter came back empty") })
        // Another run may have written it meanwhile (the automatic one and "write it now", or a restart mid-job):
        // checked and saved in one transaction, so never two letters for the same days.
        val id = db.withTransaction {
            if (!test && LetterPlanning.alreadyWritten(period, letters.all())) return@withTransaction -1L
            letters.insert(
                Letter(
                    kind = period.kind,
                    periodStart = period.start.toString(),
                    periodEnd = period.end.toString(),
                    createdAt = now,
                    title = title,
                    content = content + appendix?.let { "\n\n$it" }.orEmpty(),
                )
            )
        }
        if (id > 0) letters.get(id)?.let { runCatching { search.indexLetter(it) } }
        return id
    }

    private fun hasMeaningful(all: List<Session>, p: LetterPeriod, zone: ZoneId, existing: List<Letter>) =
        all.any { it.meaningful && LetterPlanning.belongs(p, ContextFormatter.date(it.startedAt, zone), it.startedAt, existing) }

    private fun overlaps(l: Letter, p: LetterPeriod) =
        !LocalDate.parse(l.periodEnd).isBefore(p.start) && !LocalDate.parse(l.periodStart).isAfter(p.end)
}

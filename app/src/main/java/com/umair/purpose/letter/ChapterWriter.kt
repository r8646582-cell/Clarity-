package com.umair.purpose.letter

import androidx.room.withTransaction
import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiDefaults
import com.umair.purpose.ai.AiException
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.data.db.Chapter
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.memory.ChapterPeriod
import com.umair.purpose.memory.ChapterPlanning
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.memory.SearchIndex
import com.umair.purpose.prompt.Templates
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPDATE-15: life chapters with prompts/chapter.md (the stronger model). One per quarter, written after it ends;
 * chapters are what Purpose carries forward for years, while the detailed sessions stay in the archive.
 */
@Singleton
class ChapterWriter @Inject constructor(
    private val db: PurposeDatabase,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val usage: UsageRepository,
    private val ai: AiClient,
    private val search: SearchIndex,
) {
    suspend fun due(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<ChapterPeriod> {
        val sessions = db.sessionDao().all()
        val first = sessions.minOfOrNull { it.startedAt }?.let { ContextFormatter.date(it, zone) }
        return ChapterPlanning.due(today, first, db.chapterDao().all()) { p ->
            sessions.any { it.meaningful && ContextFormatter.date(it.startedAt, zone) in p }
        }
    }

    /** Writes the chapter for [period]; skips it if another run already did. */
    suspend fun write(period: ChapterPeriod, zone: ZoneId = ZoneId.systemDefault()): Long = db.datasetWork.withWriter { writeCurrentDataset(period, zone) }

    private suspend fun writeCurrentDataset(period: ChapterPeriod, zone: ZoneId): Long {
        val inPeriod = { ms: Long -> ContextFormatter.date(ms, zone) in period }
        val sessions = db.sessionDao().between(
            period.start.atStartOfDay(zone).toInstant().toEpochMilli(),
            period.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        ).filter { !it.summary.isNullOrBlank() }
        val previous = db.chapterDao().all().filter { LocalDate.parse(it.periodEnd).isBefore(period.start) }.maxByOrNull { it.periodEnd }
        val letters = db.letterDao().all().filter { it.kind == Letter.MONTHLY && LocalDate.parse(it.periodEnd) in period }
        val quotes = db.quoteDao().since(period.start.atStartOfDay(zone).toInstant().toEpochMilli()).filter { inPeriod(it.createdAt) }
        val promises = db.promiseDao().all().filter { p ->
            (p.status == Promise.KEPT || p.status == Promise.BROKEN) && p.resolvedAt?.let(inPeriod) == true
        }
        val notes = db.noteDao().all().filter { it.status == Note.ACTIVE }.sortedBy { it.id }
        val values = mapOf(
            "PERIOD" to "${period.label} (${period.start} to ${period.end})",
            "PREVIOUS_CHAPTER" to (previous?.let { "${it.title}\n\n${it.content}" } ?: "(none: this is the first chapter)"),
            "LETTERS" to letters.sortedBy { it.periodStart }.joinToString("\n\n---\n\n") { "${it.title} (${it.periodStart} to ${it.periodEnd})\n\n${it.content}" }
                .ifEmpty { "(none)" },
            "SUMMARIES" to sessions.sortedBy { it.startedAt }
                .joinToString("\n") { "- ${ContextFormatter.date(it.startedAt, zone)}: ${it.summary!!.trim()}" }.ifEmpty { "(none)" },
            "QUOTES" to LetterFormatter.quotesBlock(quotes, zone),
            "PROMISES" to ContextFormatter.resolvedLines(promises, limit = 60).joinToString("\n").ifEmpty { "(none)" },
            "NOTES" to notes.joinToString("\n") { "- [${it.type}, ${it.confidence}, seen ${it.timesSeen}x] ${it.text}" }.ifEmpty { "(none)" },
        )
        val cfg = settings.get().ai
        val request = AiRequest(
            model = cfg.deepModel,
            messages = listOf(
                AiMessage(Role.SYSTEM, Templates.fill(prompts.load("chapter.md"), values)),
                AiMessage(Role.USER, "Write the chapter now."),
            ),
            temperature = cfg.reflectionTemperature,
            // Plain writing, 150-250 words plus short lists: no thinking needed, and a limit to match.
            thinking = false,
            maxTokens = AiDefaults.CHAPTER_MAX_TOKENS,
        )
        val c = ai.complete(request)
        val now = System.currentTimeMillis()
        c.usage?.let { usage.log("chapter", request.model, it, now) }
        val (title, content) = LetterPlanning.split(c.text.ifBlank { throw AiException("The chapter came back empty") })
        val id = db.withTransaction {
            if (db.chapterDao().all().any { it.periodEnd == period.end.toString() }) return@withTransaction -1L
            db.chapterDao().insert(
                Chapter(periodStart = period.start.toString(), periodEnd = period.end.toString(), createdAt = now, title = title, content = content)
            )
        }
        if (id > 0) db.chapterDao().get(id)?.let { search.indexChapter(it) }
        return id
    }
}

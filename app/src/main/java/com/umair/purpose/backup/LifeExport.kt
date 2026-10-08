package com.umair.purpose.backup

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Milestone
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Promise
import com.umair.purpose.memory.OnboardingFormat
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * UPDATE-15 "Export my life": everything, unencrypted and human-readable, so his history outlives this app, this
 * phone and this AI provider. One Markdown file to read, plus the same data as JSON for any future program.
 * The API key is never in it (it isn't in BackupData).
 */
object LifeExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun json(data: BackupData): String = json.encodeToString(BackupData.serializer(), data)

    fun markdown(data: BackupData, zone: ZoneId): String = buildString {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(DAY)
        fun h(level: Int, t: String) = append("#".repeat(level)).append(' ').append(t).append("\n\n")
        fun line(t: String) = append(t).append('\n')

        h(1, "Purpose: my life, exported ${day(data.exportedAt)}")
        line("Everything Purpose holds, in plain words. This file is not encrypted: keep it somewhere private.")
        line("")

        val profile = data.profile.filter { !it.deletedByUser && it.value.isNotBlank() }.sortedBy { it.key }
        if (profile.isNotEmpty()) {
            h(2, "Who I am")
            profile.forEach { line("- **${it.key}**: ${it.value}" + if (it.retired) " (archived)" else "") }
            line("")
        }
        data.snapshots.maxByOrNull { it.createdAt }?.let { s ->
            h(2, "My snapshot: ${s.title}")
            OnboardingFormat.valuesLine(s.valuesJson)?.let { line("Top values: $it") }
            OnboardingFormat.bigFiveLine(s.bigFiveJson)?.let { line("Big Five (a reflection tool, not a diagnosis): $it") }
            line("")
            line(s.portrait.trim())
            line("")
        }
        val people = data.people.filter { !it.deletedByUser }.sortedBy { it.name.lowercase() }
        if (people.isNotEmpty()) {
            h(2, "People in my life")
            people.forEach { p ->
                line("- **${p.name}**" + (p.relation?.let { " ($it)" } ?: "") + (p.notes?.let { ": $it" } ?: ""))
            }
            line("")
        }
        val notes = data.notes.filter { it.status == Note.ACTIVE || it.status == Note.RETIRED || it.status == Note.RESOLVED }
        if (notes.isNotEmpty()) {
            h(2, "What Purpose learned about me")
            listOf("pattern" to "How I work", "what_helps" to "What works for me", "what_doesnt" to "What doesn't", "thread" to "Still on my mind")
                .forEach { (type, title) ->
                    val group = notes.filter { it.type == type }.sortedBy { it.firstSeen }
                    if (group.isEmpty()) return@forEach
                    h(3, title)
                    group.forEach { n ->
                        val status = when (n.status) { Note.RETIRED -> ", archived"; Note.RESOLVED -> ", resolved"; else -> "" }
                        line("- ${n.text} (${n.confidence}, seen ${n.timesSeen} times$status)")
                    }
                    line("")
                }
        }
        val strengths = data.strengths.filter { !it.deletedByUser }
        if (strengths.isNotEmpty()) {
            h(2, "My strengths")
            strengths.sortedBy { it.createdAt }.forEach { line("- ${it.text}") }
            line("")
        }
        val leaves = data.milestones.filter { it.status == Milestone.ACCEPTED }.sortedBy { it.decidedAt ?: it.proposedAt }
        if (leaves.isNotEmpty()) {
            h(2, "My growth tree")
            leaves.forEach { m ->
                line("- **${m.title}** (${day(m.decidedAt ?: m.proposedAt)}, ${m.area.replace('_', ' ')}${m.branch?.let { " / $it" } ?: ""}): ${m.description}")
            }
            line("")
        }
        if (data.chapters.isNotEmpty()) {
            h(2, "Chapters of my life")
            data.chapters.sortedBy { it.periodStart }.forEach { c ->
                h(3, "${c.title} (${c.periodStart} to ${c.periodEnd})")
                line(c.content.trim())
                line("")
            }
        }
        val promises = data.promises.sortedBy { it.createdAt }
        if (promises.isNotEmpty()) {
            h(2, "Promises")
            promises.forEach { p ->
                line("- ${day(p.createdAt)}: ${p.text} — ${p.status}" +
                    (p.whatHappened?.let { ". What happened: $it" } ?: "") + (p.lesson?.let { ". Lesson: $it" } ?: ""))
            }
            line("")
        }
        if (data.letters.isNotEmpty()) {
            h(2, "Letters")
            data.letters.sortedBy { it.periodStart }.forEach { l ->
                h(3, "${l.title} (${l.kind}, ${l.periodStart} to ${l.periodEnd})")
                line(l.content.trim())
                line("")
            }
        }
        val bySession = data.messages.groupBy { it.sessionId }
        val sessions = data.sessions.filter { bySession[it.id].orEmpty().any { m -> m.role == Message.ROLE_USER } }.sortedBy { it.startedAt }
        if (sessions.isNotEmpty()) {
            h(2, "Conversations")
            sessions.forEach { s ->
                h(3, "${day(s.startedAt)}: ${s.title ?: "Conversation"}")
                s.summary?.takeIf { it.isNotBlank() }?.let { line("_Summary: ${it.trim()}_"); line("") }
                bySession[s.id].orEmpty().sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { m ->
                    val who = if (m.role == Message.ROLE_USER) "Me" else "Purpose"
                    line("**$who** (${Instant.ofEpochMilli(m.createdAt).atZone(zone).format(TIME)}): ${m.content.trim()}")
                    line("")
                }
            }
        }
        if (promises.none { it.status == Promise.OPEN } && sessions.isEmpty() && profile.isEmpty()) line("(Nothing saved yet.)")
    }
}

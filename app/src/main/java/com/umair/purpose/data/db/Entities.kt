package com.umair.purpose.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
@Entity(tableName = "session", indices = [Index("startedAt"), Index("endedAt")])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val summary: String? = null,
    val reflected: Boolean = false,
    /** 1-5, from reflection: how much this conversation mattered. */
    val significance: Int? = null,
    /** One or two words from reflection, e.g. "low, self-critical". */
    val tone: String? = null,
    val userMessageCount: Int = 0,
    /** onboarding | journey | practice | decision | untangle, or null for a normal talk. */
    val mode: String? = null,
    /** practice: who he's practicing with. onboarding: the step. journey: the journey name. */
    val modeDetail: String? = null,
    val journeyDay: Int? = null,
    /** Set when the session started from "Talk about this letter". */
    val letterId: Long? = null,
    /** The chat-list title: reflection's `title`, or the first words of his first message until then. */
    val title: String? = null,
    /** He renamed it: reflection leaves the title alone. */
    val titleByUser: Boolean = false,
    /** The last message reflection has learned from, so a continued conversation is never learned twice. */
    val reflectedUpToMessageId: Long? = null,
) {
    companion object {
        /** A "meaningful conversation": reflected, and he sent at least this many messages. */
        const val MEANINGFUL_USER_MESSAGES = 4
    }

    val meaningful: Boolean get() = reflected && userMessageCount >= MEANINGFUL_USER_MESSAGES

    /** Off the record: lives in memory only, with a negative id (see OffRecord). Never stored. */
    val offTheRecord: Boolean get() = id < 0
}

@Serializable
@Entity(
    tableName = "message",
    foreignKeys = [ForeignKey(
        entity = Session::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId"), Index("createdAt"), Index("status")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** "user" or "assistant". */
    val role: String,
    val content: String,
    val createdAt: Long,
    // How a coach reply was made, for the "Details" sheet. Null for his messages and older replies.
    /** "fast" or "deep". */
    val tier: String? = null,
    val model: String? = null,
    val thinking: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    /** From sending to the first visible words. */
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null,
    /** A coach reply is written as it streams: streaming → complete, or interrupted if the app was killed. */
    val status: String = COMPLETE,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val STREAMING = "streaming"
        const val COMPLETE = "complete"
        const val INTERRUPTED = "interrupted"
        /** UPDATE-16: written with no internet; sent in order when the connection returns. */
        const val QUEUED = "queued"
    }

    val streaming: Boolean get() = status == STREAMING
    val interrupted: Boolean get() = status == INTERRUPTED
    val queued: Boolean get() = status == QUEUED
}

/** Single-row app settings. The API key is not here; it lives in [com.umair.purpose.security.SecretStore]. */
@Serializable
@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val toughLove: String,
    val provider: String,
    val baseUrl: String,
    val chatModel: String,
    val deepModel: String,
    val chatTemperature: Double,
    val reflectionTemperature: Double,
    val supportsThinkingToggle: Boolean,
    // USD per 1M tokens. Prefilled with defaults, editable under Advanced.
    @ColumnInfo(defaultValue = "0") val chatPriceCacheHit: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val chatPriceCacheMiss: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val chatPriceOutput: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val deepPriceCacheHit: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val deepPriceCacheMiss: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val deepPriceOutput: Double = 0.0,
    /** night | day | system */
    val theme: String = "night",
    val lockEnabled: Boolean = true,
    val hideInRecents: Boolean = true,
    /** default | en | ur */
    val voiceLanguage: String = "default",
    val pulseEnabled: Boolean = false,
    val readAloud: Boolean = false,
    /** No longer used (the help-numbers feature was removed). The column stays so the schema and migrations don't change. */
    val helpNumbers: String = "",
    /** Unused since UPDATE-19 (the AI-written opening line was removed); the column stays to avoid a migration. */
    val nextOpening: String? = null,
    /** Settings > Advanced: route every chat message to the deep model. */
    val alwaysDeep: Boolean = false,
    /** USD per month. At 100%, chat uses the fast model until the month ends. */
    val monthlyBudget: Double = 5.0,
    /** No longer used; see [helpNumbers]. */
    val helpNumbersEdited: Boolean = false,
    /** Weekly encrypted backup to [backupFolder] (a Storage Access Framework tree URI). */
    val autoBackup: Boolean = false,
    val backupFolder: String? = null,
    // UPDATE-16: a backup AI provider, used after the main one fails 3 times in a row. Key in SecretStore.
    /** "" (none), "openai" (any OpenAI-compatible API) or "anthropic". */
    val backupProvider: String = "",
    val backupBaseUrl: String = "",
    val backupChatModel: String = "",
    val backupDeepModel: String = "",
    // UPDATE-17: off-peak pricing. Peak windows in UTC ("01:00-04:00,06:00-10:00"); every other hour is off-peak.
    val offPeakEnabled: Boolean = true,
    val peakWindows: String = "01:00-04:00,06:00-10:00",
    /** Peak hours only apply Monday to Friday; weekends are all off-peak. */
    val peakWeekdaysOnly: Boolean = true,
    /** Off-peak price as a share of the normal one (0.5 = half price). */
    val offPeakFactor: Double = 0.5,
    // UPDATE-16: the yearly items on the Health check.
    val lastRestoreTestAt: Long? = null,
    val keystoreConfirmedAt: Long? = null,
    /** When the provider key expires, if he knows ("2027-03-01"). */
    val providerKeyExpiry: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/** Token counts per AI request. Never any content. */
@Serializable
@Entity(tableName = "usage_stat", indices = [Index("createdAt")])
data class UsageStat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** "chat", "reflection", "letter" or "snapshot". */
    val purpose: String,
    val model: String,
    val promptTokens: Int,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
    val completionTokens: Int,
    /** For chat: "fast" or "deep" (see [com.umair.purpose.chat.Tier]). */
    val tier: String? = null,
    /** Sent in the provider's off-peak window (cheaper). */
    val offPeak: Boolean = false,
)

/**
 * UPDATE-17: one month of usage for one feature and model, kept for 24 months after the single requests are
 * folded in (requests older than 3 months are kept only as these totals).
 */
@Serializable
@Entity(tableName = "usage_month", primaryKeys = ["month", "purpose", "model", "tier", "offPeak"])
data class UsageMonth(
    /** "2026-10" */
    val month: String,
    val purpose: String,
    val model: String,
    /** "fast", "deep" or "" */
    val tier: String,
    val offPeak: Boolean,
    val requests: Int,
    val promptTokens: Long,
    val cacheHitTokens: Long,
    val cacheMissTokens: Long,
    val completionTokens: Long,
)

/** "Who you are": values, future-self vision, life facts. */
@Serializable
@Entity(tableName = "profile_entry")
data class ProfileEntry(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
    /** He edited it in What I know: reflection leaves it alone from now on. */
    val editedByUser: Boolean = false,
    /** He deleted it: hidden, and reflection never brings it back. */
    val deletedByUser: Boolean = false,
    /** Sessions this was learned from, comma-separated. Empty = unknown (kept when a conversation is forgotten). */
    val sourceSessionIds: String = "",
    /** UPDATE-15: over the core-profile cap, tidied into the archive: kept and searchable, not sent or shown. */
    val retired: Boolean = false,
)

@Serializable
@Entity(tableName = "person")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val relation: String? = null,
    val notes: String? = null,
    val updatedAt: Long,
    val editedByUser: Boolean = false,
    val deletedByUser: Boolean = false,
    /** See [ProfileEntry.sourceSessionIds]. */
    val sourceSessionIds: String = "",
)

@Serializable
@Entity(tableName = "note", indices = [Index("status"), Index("type")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** pattern | thread | what_helps | what_doesnt */
    val type: String,
    val text: String,
    /** guess | likely | confirmed */
    val confidence: String,
    /** active | resolved | deleted_by_user */
    val status: String,
    val timesSeen: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val editedByUser: Boolean = false,
    /** See [ProfileEntry.sourceSessionIds]. */
    val sourceSessionIds: String = "",
) {
    companion object {
        val TYPES = listOf("pattern", "thread", "what_helps", "what_doesnt")
        val CONFIDENCES = listOf("guess", "likely", "confirmed")
        const val ACTIVE = "active"
        const val RESOLVED = "resolved"
        const val DELETED_BY_USER = "deleted_by_user"
        /** Tidied away by memory gardening: kept, but not shown and not sent. */
        const val RETIRED = "retired"
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@Entity(tableName = "promise", indices = [Index("status"), Index("sourceSessionId"), Index("createdAt")])
data class Promise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** What this tests or builds. */
    val why: String? = null,
    val createdAt: Long,
    /** Local "yyyy-MM-dd" or "yyyy-MM-ddTHH:mm", or null when he gave no date. See [com.umair.purpose.promise.Due]. */
    @JsonNames("dueDate") val dueAt: String? = null,
    /** Epoch millis of the reminder he asked for, or null. */
    val remindAt: Long? = null,
    /** open | kept | broken | renegotiated | dropped */
    val status: String,
    val sourceSessionId: Long?,
    /** The coach reply that saved it in chat, for the inline "Promise saved" line. */
    val sourceMessageId: Long? = null,
    val resolvedAt: Long? = null,
    val whatHappened: String? = null,
    val lesson: String? = null,
    /** One of [AreaStatus.AREAS], when known. */
    val area: String? = null,
    /** UPDATE-18: a short stable key for the kind of action ("study_5pm"), so consistency can be measured. */
    val actionKey: String? = null,
    /**
     * Saved during an off-the-record conversation. The reminder he explicitly asked for still fires, but the
     * words are not remembered: this promise is left out of the context block of every later request, so
     * "nothing here will be remembered" stays true.
     */
    val offTheRecord: Boolean = false,
) {
    companion object {
        const val OPEN = "open"
        const val KEPT = "kept"
        const val BROKEN = "broken"
        const val RENEGOTIATED = "renegotiated"
        const val DROPPED = "dropped"
    }
}

@Serializable
@Entity(tableName = "area_status")
data class AreaStatus(
    @PrimaryKey val area: String,
    /** growing | steady | stuck */
    val status: String,
    val note: String?,
    val updatedAt: Long,
    /**
     * Anything he edits or deletes in What I know is final: reflection may not overwrite an [editedByUser] area
     * and may not re-create a [deletedByUser] one. Areas are part of What I know, so they obey the same rule as
     * notes and profile entries.
     */
    val editedByUser: Boolean = false,
    val deletedByUser: Boolean = false,
) {
    companion object {
        val AREAS = listOf(
            "eq", "habits", "mindset", "character", "studies_career",
            "health", "relationships", "money", "meaning", "rest_joy",
        )
        val STATUSES = listOf("growing", "steady", "stuck")
    }
}

/** A Mirror letter. The title is the first line of what the model wrote; [content] is the rest. */
@Serializable
@Entity(tableName = "letter", indices = [Index("kind"), Index("periodEnd")])
data class Letter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** weekly | monthly | yearly */
    val kind: String,
    /** ISO dates, inclusive. */
    val periodStart: String,
    val periodEnd: String,
    val createdAt: Long,
    val title: String,
    val content: String,
    val readAt: Long? = null,
) {
    companion object {
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
        const val YEARLY = "yearly"
    }
}

/** His exact words, from reflection `his_words`. */
@Serializable
@Entity(tableName = "quote", indices = [Index("sessionId"), Index("createdAt")])
data class Quote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val createdAt: Long,
)

/** A tag for an idea the coach used, from reflection `ideas_used`, so it doesn't repeat itself. */
@Serializable
@Entity(tableName = "idea_used", indices = [Index("sessionId"), Index("createdAt")])
data class IdeaUsed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tag: String,
    val createdAt: Long,
)

/** One concrete instance of how he behaved, as a chain. Fields are empty when he didn't say. */
@Serializable
@Entity(tableName = "behavior_event", indices = [Index("sessionId"), Index("createdAt")])
data class BehaviorEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val createdAt: Long,
    val whenText: String? = null,
    val situation: String? = null,
    val feelingBefore: String? = null,
    val action: String? = null,
    val payoff: String? = null,
    val outcome: String? = null,
    val deletedByUser: Boolean = false,
)

@Serializable
@Entity(tableName = "strength", indices = [Index("sessionId")])
data class Strength(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val createdAt: Long,
    val editedByUser: Boolean = false,
    val deletedByUser: Boolean = false,
    /** UPDATE-15: over the cap of 20, tidied into the archive (kept, not sent or shown). */
    val retired: Boolean = false,
)

/** The portrait written after onboarding. The latest one is used in the chat context. */
@Serializable
@Entity(tableName = "snapshot")
data class Snapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val bigFiveJson: String,
    val valuesJson: String,
    val title: String,
    val portrait: String,
)

/**
 * Onboarding progress. Conversation steps (story, people, values, future_self, how_you_work) record their
 * session; the two in-app steps (values_sort, big_five) keep their answers as JSON in [data].
 */
@Serializable
@Entity(tableName = "onboarding_step")
data class OnboardingStep(
    @PrimaryKey val step: String,
    val sessionId: Long? = null,
    val completedAt: Long? = null,
    val data: String? = null,
)

@Serializable
@Entity(tableName = "journey", indices = [Index("status")])
data class Journey(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    /** The next day to do, 1-based. */
    val currentDay: Int,
    val totalDays: Int,
    /** active | done | stopped */
    val status: String,
    /** ISO date of the last day a step was completed. */
    val lastStepDate: String? = null,
    /** When the last step was done (status done). */
    val completedAt: Long? = null,
    /** One line from the last day's reflection summary: what he takes away. */
    val takeaway: String? = null,
    /**
     * UPDATE-18: this run's days after adaptation, as JSON (the same shape as custom journeys). Null = the plan as
     * written. Built-in journey files are never edited; adjustments live here, per run.
     */
    val stepsJson: String? = null,
    /** When it was paused (status paused). */
    val pausedAt: Long? = null,
) {
    /**
     * A run that is stopped or finished is archived: it is kept in history but never counts as active. Derived
     * from [status], not a stored column, so there is no schema change.
     */
    val isArchived: Boolean get() = status == STOPPED || status == DONE

    companion object {
        const val ACTIVE = "active"
        const val DONE = "done"
        const val STOPPED = "stopped"
        /** UPDATE-18: something bigger is happening; Path shows "Paused" with Resume. */
        const val PAUSED = "paused"
    }
}

/** UPDATE-18: one adaptation of a journey run after a journey session (prompts/journey_adapt.md). */
@Serializable
@Entity(tableName = "journey_adjustment", indices = [Index("journeyId")])
data class JourneyAdjustment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val journeyId: Long,
    /** The day the session was about. */
    val day: Int,
    /** continue | repeat_day | make_smaller | make_bigger | swap_step | rest_day | pause */
    val decision: String,
    /** One sentence to him, shown in Path and passed to the next journey session. */
    val reason: String,
    /** The days that changed, as JSON. */
    val changesJson: String = "[]",
    val createdAt: Long,
)

/** Optional daily check-in. One per day. */
@Serializable
@Entity(tableName = "pulse")
data class Pulse(
    /** ISO date. */
    @PrimaryKey val date: String,
    /** 1-5 */
    val mood: Int,
    /** 1-5 */
    val energy: Int,
    val word: String? = null,
)

/**
 * What went wrong, for Settings > Advanced > Developer. Never message content: the error body comes from the
 * provider, which doesn't echo his messages back.
 */
@Entity(tableName = "error_log", indices = [Index("createdAt")])
data class ErrorLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** The job or screen: "chat", "reflection", "letter", "snapshot", "gardening", "backup", "testbench". */
    val source: String,
    val model: String? = null,
    val httpCode: Int? = null,
    /** e.g. "SocketTimeoutException", "HTTP 400". */
    val errorType: String,
    val errorBody: String? = null,
    val estimatedInputTokens: Int? = null,
)

/** Developer > Prompt editor: his saved version of a prompt file, used instead of the built-in asset. */
@Serializable
@Entity(tableName = "prompt_override")
data class PromptOverride(
    /** The file name, e.g. "persona.md". */
    @PrimaryKey val name: String,
    val text: String,
    val updatedAt: Long,
    /**
     * UPDATE-13: a fingerprint of the built-in file when he saved his version, so the editor can say when an app
     * update changed the built-in one since. Null for versions saved before this was kept.
     */
    val builtInHash: String? = null,
)

/** A 7-day journey made just for him with prompts/journey_custom.md ("Make one for me"). Listed under "Yours". */
@Serializable
@Entity(tableName = "custom_journey")
data class CustomJourney(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "For when you…" */
    val description: String,
    /** Why it fits him now, addressed to him. */
    val why: String,
    /** The days as JSON: [{"day":1,"theme":"…","explore":"…","action":"…"}]. */
    val daysJson: String,
    val createdAt: Long,
)

/** UPDATE-15: a life chapter (prompts/chapter.md), every 3 months and on Jan 1. Replaces old summaries in context. */
@Serializable
@Entity(tableName = "chapter", indices = [Index("periodEnd")])
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO dates, inclusive. */
    val periodStart: String,
    val periodEnd: String,
    val createdAt: Long,
    val title: String,
    val content: String,
    val readAt: Long? = null,
)

/**
 * UPDATE-18: a leaf on the growth tree, or a proposal for one. Proposed by prompts/milestone.md from evidence
 * computed in code; it becomes a leaf only when he accepts it.
 */
@Serializable
@Entity(tableName = "milestone", indices = [Index("status"), Index("area")])
data class Milestone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** habit_built | habit_unlearned | accomplishment | inner_growth | relationship */
    val type: String,
    /** One of [AreaStatus.AREAS]. */
    val area: String,
    /** Sub-branch name ("FAR", "Abbu"), or null for the area branch itself. */
    val branch: String? = null,
    val title: String,
    val description: String,
    /** The evidence lines, as a JSON list of strings. */
    val evidenceJson: String = "[]",
    /** high | medium | low */
    val confidence: String,
    val proposedAt: Long,
    val decidedAt: Long? = null,
    /** proposed | accepted | declined | snoozed | removed | considered */
    val status: String,
    val snoozeUntil: Long? = null,
    /** His own words about it, if the evidence quoted him. */
    val quote: String? = null,
) {
    companion object {
        const val PROPOSED = "proposed"
        const val ACCEPTED = "accepted"
        const val DECLINED = "declined"
        const val SNOOZED = "snoozed"
        /** He removed the leaf: gone from the tree, never proposed again. */
        const val REMOVED = "removed"
        /** considered_but_not_yet: shown nowhere, only context for the next run. */
        const val CONSIDERED = "considered"
        val TYPES = listOf("habit_built", "habit_unlearned", "accomplishment", "inner_growth", "relationship")
    }
}

/** UPDATE-18: a sub-branch on the growth tree (studies_career / FAR), created by an accepted milestone. */
@Serializable
@Entity(tableName = "branch", indices = [Index(value = ["area", "name"], unique = true)])
data class Branch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val area: String,
    val name: String,
    val createdAt: Long,
)

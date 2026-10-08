package com.umair.purpose.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Serializable
data class ReflectionResult(
    @SerialName("session_summary") val sessionSummary: String = "",
    @SerialName("profile_updates") val profileUpdates: List<ProfileUpdate> = emptyList(),
    @SerialName("people_updates") val peopleUpdates: List<PersonUpdate> = emptyList(),
    val notes: NoteChanges = NoteChanges(),
    val promises: PromiseChanges = PromiseChanges(),
    @SerialName("area_status") val areaStatus: List<AreaUpdate> = emptyList(),
    @SerialName("behavior_events") val behaviorEvents: List<BehaviorUpdate> = emptyList(),
    val strengths: List<String> = emptyList(),
    /** A short chat-list title (3 to 6 words). */
    val title: String = "",
    val significance: Int? = null,
    val tone: String = "",
    @SerialName("his_words") val hisWords: List<String> = emptyList(),
    @SerialName("ideas_used") val ideasUsed: List<String> = emptyList(),
    /** Which "getting to know you" steps this conversation covered in real depth (UPDATE-12). */
    @SerialName("onboarding_covered") val onboardingCovered: List<String> = emptyList(),
    /** Phase 3: where Purpose and he see something differently. His side is checked word for word before storing. */
    val disagreements: List<DisagreementUpdate> = emptyList(),
    /** Phase 3: two of his own statements that do not fit. Both are checked word for word before storing. */
    val contradictions: List<ContradictionUpdate> = emptyList(),
) {
    @Serializable
    data class DisagreementUpdate(val claim: String = "", @SerialName("his_position") val hisPosition: String = "")

    @Serializable
    data class ContradictionUpdate(@SerialName("quote_a") val quoteA: String = "", @SerialName("quote_b") val quoteB: String = "")

    @Serializable
    data class ProfileUpdate(val key: String = "", val value: String = "")

    @Serializable
    data class PersonUpdate(val name: String = "", val relation: String? = null, val notes: String? = null)

    @Serializable
    data class NoteChanges(
        val add: List<NewNote> = emptyList(),
        val update: List<NoteUpdate> = emptyList(),
        val resolve: List<Long> = emptyList(),
    )

    @Serializable
    data class NewNote(val type: String = "", val text: String = "", val confidence: String = "guess")

    @Serializable
    data class NoteUpdate(
        val id: Long = 0,
        val text: String? = null,
        val confidence: String? = null,
        @SerialName("seen_again") val seenAgain: Boolean = false,
    )

    @Serializable
    data class PromiseChanges(
        val new: List<NewPromise> = emptyList(),
        @Serializable(with = OutcomeListSerializer::class) val kept: List<Outcome> = emptyList(),
        @Serializable(with = OutcomeListSerializer::class) val broken: List<Outcome> = emptyList(),
        val renegotiated: List<Renegotiation> = emptyList(),
        val dropped: List<Long> = emptyList(),
    )

    @Serializable
    data class NewPromise(
        val text: String = "",
        @SerialName("due_date") val dueDate: String? = null,
        /** UPDATE-18: a short stable key for the kind of action ("study_5pm"), reused across promises. */
        @SerialName("action_key") val actionKey: String? = null,
    )

    /** A kept or broken promise, with what happened and what it taught about how he works. */
    @Serializable
    data class Outcome(
        val id: Long = 0,
        @SerialName("what_happened") val whatHappened: String? = null,
        val lesson: String? = null,
    )

    @Serializable
    data class Renegotiation(val id: Long = 0, val text: String = "", @SerialName("due_date") val dueDate: String? = null)

    @Serializable
    data class AreaUpdate(val area: String = "", val status: String = "", val note: String? = null)

    @Serializable
    data class BehaviorUpdate(
        @SerialName("when") val whenText: String? = null,
        val situation: String? = null,
        @SerialName("feeling_before") val feelingBefore: String? = null,
        val action: String? = null,
        val payoff: String? = null,
        val outcome: String? = null,
    )
}

/** Accepts `[3, 4]` (the older shape) as well as `[{"id": 3, ...}]`. */
internal object OutcomeListSerializer :
    JsonTransformingSerializer<List<ReflectionResult.Outcome>>(ListSerializer(ReflectionResult.Outcome.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(array.map { if (it is JsonPrimitive) buildJsonObject { put("id", it) } else it })
    }
}

class ReflectionParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ReflectionParser {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** Parses the model's reply. Tolerates code fences or stray text around the object; nothing else. */
    fun parse(raw: String): ReflectionResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw ReflectionParseException("No JSON object in reply")
        val result = try {
            json.decodeFromString(ReflectionResult.serializer(), raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw ReflectionParseException("Reply was not valid reflection JSON", e)
        }
        if (result.sessionSummary.isBlank()) throw ReflectionParseException("Missing session_summary")
        return result
    }

    /** "2026-10-05" → that date; "null", "", "YYYY-MM-DD or null" or anything else → null. */
    fun parseDate(value: String?): LocalDate? = try {
        value?.trim()?.takeIf { it.length == 10 }?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }
}

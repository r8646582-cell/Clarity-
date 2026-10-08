package com.umair.purpose.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A "getting to know you" step the coach just closed, shown as "done / Next" under that reply. */
data class StepDoneCard(val sessionId: Long, val messageId: Long, val step: String) {
    fun encode() = "$sessionId|$messageId|$step"

    companion object {
        fun decode(s: String?): StepDoneCard? {
            val p = s?.split('|')?.takeIf { it.size == 3 } ?: return null
            return StepDoneCard(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2])
        }
    }
}

/** Small, non-sensitive interface state (dates and flags only, never content). */
@Singleton
class UiPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("purpose_ui", Context.MODE_PRIVATE)

    private val _dismissed = MutableStateFlow(readDismissed())
    /** Talk cards he swiped away, as "kind:date". */
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    fun dismiss(kind: String, date: String) {
        val next = (_dismissed.value.filter { it.substringAfter(':') == date } + "$kind:$date").toSet()
        prefs.edit().putStringSet(KEY_DISMISSED, next).apply()
        _dismissed.value = next
    }

    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIFY, false)
        set(v) = prefs.edit().putBoolean(KEY_ASKED_NOTIFY, v).apply()

    /** Phase 4: he turned on phone screen-time tracking. Off by default; a restore or erase turns it off again. */
    var screenTimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_TIME, false)
        set(v) = prefs.edit().putBoolean(KEY_SCREEN_TIME, v).apply()

    /** When the last automatic backup was written (epoch millis), or 0. */
    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_BACKUP, v).apply()

    /** A letter that couldn't be written after all retries: "weekly", "monthly" or "yearly". Mirror shows a quiet line. */
    private val _letterDelayed = MutableStateFlow(prefs.getString(KEY_LETTER_DELAYED, null))
    val letterDelayed: StateFlow<String?> = _letterDelayed.asStateFlow()

    fun setLetterDelayed(kind: String?) {
        prefs.edit().putString(KEY_LETTER_DELAYED, kind).apply()
        _letterDelayed.value = kind
    }

    /** The one-time "Keep Purpose reliable" card on Talk has been seen and handled (or swiped away). */
    private val _reliabilityCardDone = MutableStateFlow(prefs.getBoolean(KEY_RELIABILITY_CARD, false))
    val reliabilityCardDone: StateFlow<Boolean> = _reliabilityCardDone.asStateFlow()

    fun setReliabilityCardDone() {
        prefs.edit().putBoolean(KEY_RELIABILITY_CARD, true).apply()
        _reliabilityCardDone.value = true
    }

    /** Health check: how a background job last went ("reflection", "letters", "gardening", "snapshot", "backup"). */
    fun recordJob(name: String, ok: Boolean, at: Long = System.currentTimeMillis()) {
        val e = prefs.edit().putLong("job_${name}_at", at).putBoolean("job_${name}_ok", ok)
        if (ok) e.putLong("job_${name}_ok_at", at)
        e.apply()
    }

    fun job(name: String): com.umair.purpose.dev.JobRecord = com.umair.purpose.dev.JobRecord(
        lastRunAt = prefs.getLong("job_${name}_at", 0L).takeIf { it > 0 },
        lastOk = if (prefs.contains("job_${name}_ok")) prefs.getBoolean("job_${name}_ok", false) else null,
        lastOkAt = prefs.getLong("job_${name}_ok_at", 0L).takeIf { it > 0 },
    )

    /** Whether the one-time memory clean-up of update 10 (second person, merged people) has run. */
    var gardenedForUpdate10: Boolean
        get() = prefs.getBoolean(KEY_GARDEN_10, false)
        set(v) = prefs.edit().putBoolean(KEY_GARDEN_10, v).apply()

    /** Update 12, once: memory gardening also rewrites the profile to "you" ("his mother and father"). */
    var gardenedForUpdate12: Boolean
        get() = prefs.getBoolean(KEY_GARDEN_12, false)
        set(v) = prefs.edit().putBoolean(KEY_GARDEN_12, v).apply()

    /** Update 15, once: memory gardening with the new caps (and the "you" rewrite again). */
    var gardenedForUpdate15: Boolean
        get() = prefs.getBoolean(KEY_GARDEN_15, false)
        set(v) = prefs.edit().putBoolean(KEY_GARDEN_15, v).apply()

    /** Update 12, once: his past onboarding conversations were checked for the steps they really covered. */
    var onboardingRechecked: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_RECHECK, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING_RECHECK, v).apply()

    /** How many times in a row this conversation's reflection has failed. */
    fun reflectionFailures(sessionId: Long): Int = prefs.getInt("refl_fail_$sessionId", 0)

    /** Counts a failed reflection and returns the new count. */
    fun recordReflectionFailure(sessionId: Long): Int {
        val n = reflectionFailures(sessionId) + 1
        prefs.edit().putInt("refl_fail_$sessionId", n).apply()
        return n
    }

    /**
     * Stops retrying a conversation reflection can never read. Without this, one permanently unreflectable
     * session would cost a Pro call on every launch for ever (it is always first in the queue, oldest first).
     */
    fun giveUpOnReflection(sessionId: Long) {
        prefs.edit().remove("refl_fail_$sessionId")
            .putStringSet(KEY_REFL_GIVE_UP, gaveUpReflections() + sessionId.toString()).apply()
    }

    fun reflectionGaveUp(sessionId: Long): Boolean = gaveUpReflections().contains(sessionId.toString())

    fun clearReflectionFailure(sessionId: Long) {
        prefs.edit().remove("refl_fail_$sessionId")
            .putStringSet(KEY_REFL_GIVE_UP, gaveUpReflections() - sessionId.toString()).apply()
    }

    private fun gaveUpReflections(): Set<String> = prefs.getStringSet(KEY_REFL_GIVE_UP, emptySet()).orEmpty().toSet()

    private val _stepDone = MutableStateFlow(StepDoneCard.decode(prefs.getString(KEY_STEP_DONE, null)))
    /** The "done / Next" card under the reply that closed a step, until he picks Continue now or Later. */
    val stepDone: StateFlow<StepDoneCard?> = _stepDone.asStateFlow()

    fun setStepDone(card: StepDoneCard?) {
        prefs.edit().putString(KEY_STEP_DONE, card?.encode()).apply()
        _stepDone.value = card
    }

    /** Settings "Vibration" lives in the database; this mirrors it for code without a coroutine. */
    var vibration: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION, true)
        set(v) = prefs.edit().putBoolean(KEY_VIBRATION, v).apply()

    /**
     * UPDATE-20: pacing the coach set autonomously with `[[action: {type: set_pacing}]]`. The multiplier scales the
     * reply reveal speed (1 = normal); the haptic level is one of gentle|balanced|firm|off and also turns the
     * vibration helper on or off.
     */
    var paceMultiplier: Float
        get() = prefs.getFloat(KEY_PACE, 1f)
        set(v) = prefs.edit().putFloat(KEY_PACE, v.coerceIn(0.5f, 2f)).apply()

    var hapticLevel: String
        get() = prefs.getString(KEY_HAPTIC, "balanced") ?: "balanced"
        set(v) = prefs.edit().putString(KEY_HAPTIC, v.trim().lowercase()).apply()

    /**
     * The nightly gardened "morning perspective": a short code-computed line about his open promises (see
     * [com.umair.purpose.chat.HomeFacts.morningPerspective]). Null until the first daily garden runs.
     */
    var morningPerspective: String?
        get() = prefs.getString(KEY_MORNING, null)
        set(v) = prefs.edit().putString(KEY_MORNING, v).apply()

    /** Read aloud: the chosen TextToSpeech voice (engine name), or null for the language's default. */
    var voiceName: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(v) = prefs.edit().putString(KEY_VOICE, v).apply()

    /** 0.5 to 2.0; 1 is normal. */
    var voiceSpeed: Float
        get() = prefs.getFloat(KEY_VOICE_SPEED, 1f)
        set(v) = prefs.edit().putFloat(KEY_VOICE_SPEED, v).apply()

    var voicePitch: Float
        get() = prefs.getFloat(KEY_VOICE_PITCH, 1f)
        set(v) = prefs.edit().putFloat(KEY_VOICE_PITCH, v).apply()

    /** "Suggested for you", cached for a week: the JSON list, when it was made, and how many journeys were done then. */
    var suggestionsJson: String?
        get() = prefs.getString(KEY_SUGGEST, null)
        set(v) = prefs.edit().putString(KEY_SUGGEST, v).apply()
    var suggestionsAt: Long
        get() = prefs.getLong(KEY_SUGGEST_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_SUGGEST_AT, v).apply()
    var suggestionsDoneCount: Int
        get() = prefs.getInt(KEY_SUGGEST_DONE, -1)
        set(v) = prefs.edit().putInt(KEY_SUGGEST_DONE, v).apply()

    private fun readDismissed(): Set<String> = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet()

    /** IDs and summaries belong to the replaced dataset; device and voice preferences stay. */
    fun datasetReplaced() {
        val editor = prefs.edit()
        listOf(KEY_DISMISSED, KEY_LETTER_DELAYED, KEY_STEP_DONE, KEY_REFL_GIVE_UP, KEY_MORNING,
            KEY_SUGGEST, KEY_SUGGEST_AT, KEY_SUGGEST_DONE, KEY_GARDEN_10, KEY_GARDEN_12, KEY_GARDEN_15,
            KEY_ONBOARDING_RECHECK, KEY_SCREEN_TIME).forEach(editor::remove)
        prefs.all.keys.filter { it.startsWith("refl_fail_") || it.startsWith("job_") }.forEach(editor::remove)
        editor.apply()
        _dismissed.value = emptySet()
        _letterDelayed.value = null
        _stepDone.value = null
    }

    private companion object {
        const val KEY_SCREEN_TIME = "screen_time_enabled"
        const val KEY_DISMISSED = "dismissed_cards"
        const val KEY_ASKED_NOTIFY = "asked_notify"
        const val KEY_LAST_BACKUP = "last_backup"
        const val KEY_LETTER_DELAYED = "letter_delayed"
        const val KEY_RELIABILITY_CARD = "reliability_card"
        const val KEY_GARDEN_10 = "garden_update_10"
        const val KEY_GARDEN_12 = "garden_update_12"
        const val KEY_GARDEN_15 = "garden_update_15"
        const val KEY_ONBOARDING_RECHECK = "onboarding_recheck_12"
        const val KEY_REFL_GIVE_UP = "reflection_gave_up"
        const val KEY_STEP_DONE = "step_done_card"
        const val KEY_VIBRATION = "vibration"
        const val KEY_PACE = "pace_multiplier"
        const val KEY_HAPTIC = "haptic_level"
        const val KEY_MORNING = "morning_perspective"
        const val KEY_VOICE = "voice_name"
        const val KEY_SUGGEST = "journey_suggestions"
        const val KEY_SUGGEST_AT = "journey_suggestions_at"
        const val KEY_SUGGEST_DONE = "journey_suggestions_done"
        const val KEY_VOICE_SPEED = "voice_speed"
        const val KEY_VOICE_PITCH = "voice_pitch"
    }
}

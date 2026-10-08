package com.umair.purpose.chat

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import com.umair.purpose.memory.OnboardingTopic
import com.umair.purpose.time.TimeFacts
import java.time.LocalTime

/** What Purpose says first, and the mode header line. Interface copy, not prompts. */
object TalkCopy {

    /** Time-band greeting (UPDATE-19): "Still up, Umair?" from 22:00 to 4:59. */
    fun greeting(time: LocalTime): String = TimeFacts.greeting(time)

    /** The home line under the greeting, joined for places that show one line. */
    fun greetingLine(time: LocalTime): String = greeting(time).let { if (it.endsWith("?")) it else "$it." } + " What's on your mind?"

    /**
     * The opening line for a session that has no messages yet.
     * Never AI-written (UPDATE-19): it could be stale or wrong about time. [journeyTheme] comes from journeys.md.
     */
    fun opening(session: Session?, time: LocalTime, journeyTheme: String? = null): String {
        val mode = Mode.fromWire(session?.mode)
        return when {
            session?.letterId != null && mode == null -> "What stayed with you from this one?"
            mode == Mode.ONBOARDING -> when (OnboardingTopic.fromStep(session?.modeDetail)) {
                OnboardingTopic.STORY -> "What's the story of how you got to where you are right now?"
                OnboardingTopic.PEOPLE -> "Who are the people who matter most to you right now?"
                OnboardingTopic.VALUES -> "You've picked the values that matter most to you. Why these? Start with the first one."
                OnboardingTopic.FUTURE_SELF -> "Picture an ordinary day five years from now. Who are you, and how do you spend it?"
                OnboardingTopic.HOW_YOU_WORK -> "Walk me through a normal day. When do you have energy, and when do you slip?"
                null -> "Tell me about yourself."
            }
            mode == Mode.JOURNEY -> {
                val day = session?.journeyDay ?: 1
                val head = "Day $day of ${session?.modeDetail}" + (journeyTheme?.let { ": $it." } ?: ".")
                if (day <= 1) "$head Shall we begin?" else "$head How did the last step go?"
            }
            mode == Mode.PRACTICE -> PracticeName.clean(session?.modeDetail)?.let {
                "Let's get you ready to talk with $it. What do you want from that conversation, and what are you afraid will happen?"
            } ?: "Who's the conversation with, and what do you want from it?"
            mode == Mode.DECISION -> "What's the decision, in one sentence? And what makes it hard?"
            mode == Mode.UNTANGLE -> "Which thought keeps hurting? Tell me what happened that set it off."
            else -> greetingLine(time)
        }
    }

    fun modeHeader(session: Session?, letterTitle: String? = null): String? {
        val s = session ?: return null
        return when (Mode.fromWire(s.mode)) {
            Mode.PRACTICE -> PracticeName.clean(s.modeDetail)?.let { "Practicing: talking with $it" } ?: "Practicing a conversation"
            Mode.JOURNEY -> "Journey: ${s.modeDetail}, day ${s.journeyDay ?: 1}"
            Mode.DECISION -> "Thinking through a decision"
            Mode.UNTANGLE -> "Untangling a thought"
            Mode.ONBOARDING -> "Getting to know you: " + (OnboardingTopic.fromStep(s.modeDetail)?.title?.lowercase() ?: "")
            null -> if (s.letterId != null) "About your letter" + (letterTitle?.let { ": $it" } ?: "") else null
        }
    }

    /**
     * Practice mode: which coach messages are spoken in character. Best effort, from the shape mode_practice.md
     * asks for: after "I'm {name} now", the coach's replies are in character until he says "stop" (or the coach
     * steps out with a long debrief). Returns message ids.
     */
    fun inCharacter(messages: List<Message>, with: String?): Set<Long> {
        val name = with?.trim()?.takeIf { it.isNotEmpty() } ?: return emptySet()
        val enter = Regex("""\b(i'?m|i am|i'll be)\s+(now\s+)?${Regex.escape(name)}(\s+now|\s+again)?\b""", RegexOption.IGNORE_CASE)
        val stop = Regex("""\b(stop)\b""", RegexOption.IGNORE_CASE)
        val out = mutableSetOf<Long>()
        var armed = false
        var on = false
        for (m in messages) {
            if (m.role == Message.ROLE_USER) {
                if (stop.containsMatchIn(m.content)) {
                    on = false; armed = false
                } else if (armed) {
                    on = true; armed = false
                }
            } else {
                when {
                    enter.containsMatchIn(m.content.replace('’', '\'')) -> { armed = true; on = false }
                    on && m.content.length > DEBRIEF_LENGTH -> on = false
                    on -> out += m.id
                }
            }
        }
        return out
    }

    private const val DEBRIEF_LENGTH = 700
}

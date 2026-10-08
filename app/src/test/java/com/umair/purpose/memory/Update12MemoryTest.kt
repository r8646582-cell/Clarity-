package com.umair.purpose.memory

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.OnboardingStep
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.data.repo.OnboardingProgress
import com.umair.purpose.data.repo.OnboardingState
import com.umair.purpose.memory.ReflectionResult.NewPromise
import com.umair.purpose.memory.ReflectionResult.PromiseChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** UPDATE-12 audit: onboarding steps, duplicate promises, profile cleanup, reflection bookkeeping. */
class Update12MemoryTest {
    private fun state(vararg done: String, snapshot: Boolean = false) = OnboardingState(
        done.associateWith { OnboardingStep(it, sessionId = 1, completedAt = 1) },
        if (snapshot) Snapshot(id = 1, createdAt = 0, bigFiveJson = "", valuesJson = "", title = "t", portrait = "p") else null,
    )

    @Test
    fun `reflection reads onboarding_covered`() {
        val r = ReflectionParser.parse("""{"session_summary":"s","onboarding_covered":["story","people"]}""")
        assertEquals(listOf("story", "people"), r.onboardingCovered)
        assertTrue(ReflectionParser.parse("""{"session_summary":"s"}""").onboardingCovered.isEmpty())
    }

    @Test
    fun `steps count in any order and only once`() {
        val s = state("story")
        // Story again, people covered early, and one that isn't a step.
        assertEquals(listOf("people"), OnboardingProgress.newlyDone(s, listOf("story", "People", "people", "childhood")))
        assertEquals(2, state("story", "values").topicsDone)
        assertEquals(OnboardingTopic.PEOPLE, state("story", "values").nextTopic)
    }

    @Test
    fun `the step count never goes past five`() {
        val all = state("story", "people", "values", "future_self", "how_you_work", "values_sort", "big_five", "welcome")
        assertEquals(5, all.topicsDone)
        assertTrue(OnboardingProgress.newlyDone(all, listOf("story")).isEmpty())
    }

    @Test
    fun `the snapshot is due exactly when the fifth step is done`() {
        val four = state("story", "people", "values", "future_self")
        assertTrue(OnboardingProgress.justFinished(four, OnboardingProgress.newlyDone(four, listOf("how_you_work"))))
        assertFalse(OnboardingProgress.justFinished(four, OnboardingProgress.newlyDone(four, listOf("values"))))
        val five = state("story", "people", "values", "future_self", "how_you_work", snapshot = true)
        assertFalse(OnboardingProgress.justFinished(five, OnboardingProgress.newlyDone(five, listOf("story"))))
    }

    @Test
    fun `a promise from the hidden line isn't saved again by reflection in fewer words`() {
        val saved = Promise(id = 3, text = "Read one page of FAR at 9pm with phone in another room", createdAt = 0, status = Promise.OPEN, sourceSessionId = 9)
        val cur = MemorySnapshot(emptyList(), emptyList(), emptyList(), listOf(saved), emptyList())
        val r = ReflectionResult("s", promises = PromiseChanges(new = listOf(NewPromise("Read one page of FAR tonight", "2026-10-03"))))
        assertTrue(ReflectionPlanner.plan(r, cur, sessionId = 9, now = 1).promises.isEmpty())
    }

    @Test
    fun `different promises are not mistaken for duplicates`() {
        assertFalse(ReflectionPlanner.samePromise("Call Ami on Sunday", "Read one page of FAR at 9pm"))
        assertFalse(ReflectionPlanner.samePromise("Walk 20 minutes after dinner", "Sleep by midnight"))
        assertTrue(ReflectionPlanner.samePromise("Walk 20 minutes after dinner", "walk 20 minutes"))
    }

    @Test
    fun `only ended, unreflected, stored sessions are reflected`() {
        assertTrue(ReflectionPlanner.needsReflection(Session(id = 1, startedAt = 0, endedAt = 5)))
        assertFalse(ReflectionPlanner.needsReflection(Session(id = 1, startedAt = 0, endedAt = 5, reflected = true)))
        // Continued while a reflection was running: open again, so not yet.
        assertFalse(ReflectionPlanner.needsReflection(Session(id = 1, startedAt = 0, endedAt = null)))
        assertFalse(ReflectionPlanner.needsReflection(Session(id = -3, startedAt = 0, endedAt = 5)))
    }

    @Test
    fun `the session date names both days when a talk crosses midnight`() {
        val zone = ZoneId.of("Asia/Karachi")
        fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
        val s = Session(id = 1, startedAt = at(3, 23, 40))
        fun msg(t: Long) = Message(id = t, sessionId = 1, role = Message.ROLE_USER, content = "", createdAt = t)
        assertEquals("2026-10-03 (Saturday)", ReflectionPlanner.sessionDate(s, listOf(msg(at(3, 23, 50))), zone))
        assertEquals(
            "2026-10-03 (Saturday), continuing until 2026-10-04 (Sunday)",
            ReflectionPlanner.sessionDate(s, listOf(msg(at(3, 23, 50)), msg(at(4, 0, 20))), zone),
        )
    }

    @Test
    fun `gardening rewrites profile lines to you, never his own edits`() {
        val raw = """{"rewrite_profile":[{"key":"Family","value":"You live with your mother and father."},
            {"key":"studies","value":"x"},{"key":"missing","value":"y"},{"key":"health","value":""}]}"""
        val profile = listOf(
            ProfileEntry("family", "He lives with his mother and father.", 0),
            ProfileEntry("studies", "He studies CA.", 0, editedByUser = true),
            ProfileEntry("health", "He runs.", 0),
        )
        val plan = Gardening.plan(Gardening.parse(raw), emptyList(), emptyList(), emptyList(), profile, now = 7)
        assertEquals(listOf(ProfileEntry("family", "You live with your mother and father.", 7)), plan.profile)
        assertTrue(Gardening.profileBlock(profile).contains("key \"family\""))
    }
}

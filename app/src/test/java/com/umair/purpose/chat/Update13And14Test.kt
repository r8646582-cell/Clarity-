package com.umair.purpose.chat

import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.dev.TestBench
import com.umair.purpose.journey.JourneyCatalog
import com.umair.purpose.memory.ChatExtras
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.memory.Memory
import com.umair.purpose.memory.OnboardingFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** UPDATE-13 (values step) and UPDATE-14 (real journeys only, one question per reply). */
class Update13And14Test {
    private val zone = ZoneId.of("Asia/Karachi")
    private val empty = Memory(emptyList(), emptyList(), emptyList(), emptyList())
    private val journeys = JourneyCatalog.parse(File("src/main/assets/prompts/journeys.md").readText())

    @Test
    fun `values step names every value, numbered in his order`() {
        val block = OnboardingFormat.valuesStepBlock(listOf("family", "knowledge", "faith", "freedom", "health"))
        assertTrue(block.contains("1. family\n2. knowledge\n3. faith\n4. freedom\n5. health"))
        assertTrue(OnboardingFormat.valuesStepBlock(emptyList()).contains("hasn't done the values sort"))
    }

    @Test
    fun `values stay in the context when the snapshot was written without them`() {
        val snap = Snapshot(createdAt = 0, bigFiveJson = "{}", valuesJson = "[]", title = "T", portrait = "P")
        val soFar = listOf("- Top values he picked, in order: family, faith", "- Big Five (IPIP-50): Openness 60%")
        val ctx = ContextFormatter.chatContext(empty, ChatExtras(snapshot = snap, onboardingSoFar = soFar), zone)!!
        assertTrue(ctx.contains("Top values he picked, in order: family, faith"))
        assertFalse(ctx.contains("Openness 60%"))
        // With values in the snapshot, nothing is repeated.
        val full = snap.copy(valuesJson = OnboardingFormat.encodeValues(listOf("family", "faith")))
        val ctx2 = ContextFormatter.chatContext(empty, ChatExtras(snapshot = full, onboardingSoFar = soFar), zone)!!
        assertFalse(ctx2.contains("Top values he picked"))
        assertTrue(ctx2.contains("Top values, in order: family, faith"))
    }

    @Test
    fun `available journeys go in the context by exact name`() {
        val ctx = ContextFormatter.chatContext(empty, ChatExtras(availableJourneys = journeys.map { it.name }), zone)!!
        assertTrue(ctx.contains("Available journeys"))
        assertTrue(ctx.contains("- Break the avoidance loop"))
        // Nothing else known: still no context block at all.
        assertNull(ContextFormatter.chatContext(empty, ChatExtras(), zone))
    }

    @Test
    fun `only real journeys start from a mode line`() {
        assertEquals("Break the avoidance loop", JourneyCatalog.exact(journeys, "  break THE avoidance  loop. ")!!.name)
        assertEquals("Break the avoidance loop", JourneyCatalog.exact(journeys, "\"Break the avoidance loop\"")!!.name)
        assertNull(JourneyCatalog.exact(journeys, "Quieting Anxiety"))
        // A part of a real name is not a real name.
        assertNull(JourneyCatalog.exact(journeys, "Calm under exam"))
        assertNull(JourneyCatalog.exact(journeys, ""))
        assertNull(JourneyCatalog.exact(journeys, null))
    }

    @Test
    fun `test bench reads the onboarding values scenario`() {
        val md = """
            ## Values coverage
            Mode: onboarding (values step) with sample values: family, knowledge, faith, freedom, health
            User: ok let's start
            Expect: Names all five.
        """.trimIndent()
        val s = TestBench.parse(md).single()
        assertEquals("onboarding", s.mode)
        assertEquals("values", s.step)
        assertEquals(listOf("family", "knowledge", "faith", "freedom", "health"), s.sampleValues)
        assertEquals("onboarding", Mode.fromWire(s.mode)!!.wire)
    }

    @Test
    fun `the real testbench file has the values scenario`() {
        val s = TestBench.parse(File("src/main/assets/prompts/testbench.md").readText())
        val values = s.first { it.name == "Values coverage" }
        assertEquals(5, values.sampleValues.size)
        assertTrue(s.filter { it.name != "Values coverage" }.all { it.step == null && it.sampleValues.isEmpty() })
    }

    @Test
    fun `counts questions in a reply`() {
        assertEquals(0, TestBench.questions("That sounds heavy."))
        assertEquals(2, TestBench.questions("What happened? And why now?"))
        assertEquals(1, TestBench.questions("Kya hua؟"))
    }
}

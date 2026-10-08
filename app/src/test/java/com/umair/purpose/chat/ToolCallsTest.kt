package com.umair.purpose.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** UPDATE-20: hidden `[[action: {json}]]` lines are read, acted on, and never shown. */
class ToolCallsTest {
    private val today = LocalDate.of(2026, 10, 3)

    @Test
    fun `create_journey is parsed`() {
        val c = ToolCalls.parse("""{"type":"create_journey","title":"Quiet focus","description":"When home is loud","stages":["A","B","C","D","E","F","G"]}""")!!
        assertEquals("create_journey", c.kind)
        assertTrue(c.known)
        assertEquals("Quiet focus", c.title)
        assertEquals(7, c.stages.size)
    }

    @Test
    fun `record_promise is parsed with epoch and tags`() {
        val c = ToolCalls.parse("""{"type":"record_promise","title":"Walk at 7","reminder_epoch":1791234000000,"tags":["health","walk_daily"]}""")!!
        assertEquals("record_promise", c.kind)
        assertEquals(1791234000000L, c.reminderEpoch)
        assertEquals(listOf("health", "walk_daily"), c.tags)
    }

    @Test
    fun `synthesize_memory is parsed with replaces_ids`() {
        val c = ToolCalls.parse("""{"type":"synthesize_memory","insight":"You go quiet when overwhelmed","category":"pattern","replaces_ids":[3,7]}""")!!
        assertEquals("synthesize_memory", c.kind)
        assertEquals("pattern", c.category)
        assertEquals(listOf(3L, 7L), c.replacesIds)
    }

    @Test
    fun `trigger_milestone and set_pacing are parsed`() {
        val m = ToolCalls.parse("""{"type":"trigger_milestone","milestone_key":"Three months of FAR","evidence_summary":"kept 22 of 30"}""")!!
        assertEquals("trigger_milestone", m.kind)
        assertEquals("Three months of FAR", m.milestoneKey)
        val p = ToolCalls.parse("""{"type":"set_pacing","pace_multiplier":1.25,"haptic_level":"firm"}""")!!
        assertEquals("set_pacing", p.kind)
        assertEquals(1.25, p.paceMultiplier!!, 0.0001)
        assertEquals("firm", p.hapticLevel)
    }

    @Test
    fun `advance_journey is a known type`() {
        val c = ToolCalls.parse("""{"type":"advance_journey"}""")!!
        assertEquals("advance_journey", c.kind)
        assertTrue(c.known)
    }

    @Test
    fun `unknown keys are ignored and an unknown type is still readable but not known`() {
        val c = ToolCalls.parse("""{"type":"send_gift","title":"x","extra":{"nested":true}}""")!!
        assertEquals("send_gift", c.kind)
        assertFalse(c.known)
    }

    @Test
    fun `broken json and missing type read as nothing`() {
        assertNull(ToolCalls.parse("not json at all"))
        assertNull(ToolCalls.parse("""{"title":"no type"}"""))
    }

    @Test
    fun `a reply can carry prose and several actions, all hidden from what he sees`() {
        val reply = "Then let's make it real.\n" +
            """[[action: {"type":"create_journey","title":"Quiet focus","description":"d","stages":["A","B","C","D","E"]}]]""" + "\n" +
            """[[action: {"type":"record_promise","title":"Study 5pm","reminder_epoch":0,"tags":["studies_career"]}]]"""
        val p = ReplyMarkers.parse(reply, today)
        assertEquals("Then let's make it real.", p.visible)
        assertEquals(2, p.actions.size)
        assertEquals(0, p.failures)
        assertTrue(p.hasActions)
    }

    @Test
    fun `an action-only reply has something to act on and shows nothing`() {
        val p = ReplyMarkers.parse("""[[action: {"type":"set_pacing","pace_multiplier":0.75,"haptic_level":"gentle"}]]""", today)
        assertTrue(p.visible.isBlank())
        assertTrue(p.hasActions)
        assertEquals(1, p.actions.size)
    }

    @Test
    fun `an unreadable action is still hidden and counted once`() {
        val p = ReplyMarkers.parse("Fine.\n[[action: {not valid json]]", today)
        assertEquals("Fine.", p.visible)
        assertTrue(p.actions.isEmpty())
        assertEquals(1, p.failures)
    }

    @Test
    fun `nothing from an action line ever shows while streaming`() {
        val reply = "Here you go.\n[[action: {\"type\":\"record_promise\",\"title\":\"x\"}]]"
        for (i in reply.indices) {
            val shown = ReplyMarkers.visibleWhileStreaming(reply.substring(0, i + 1))
            assertFalse(shown, shown.contains("[[") || shown.contains("record_promise"))
        }
    }
}

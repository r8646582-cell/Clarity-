package com.umair.purpose.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The canonical `<<<TOOL_CALL …>>>` blocks are read, acted on, and never shown. */
class AutonomousToolCallTest {
    private val today = LocalDate.of(2026, 10, 5)

    @Test
    fun `a tool call block is parsed and stripped from what he sees`() {
        val reply = "Let's make it real.\n" +
            "<<<TOOL_CALL\n" +
            """{"action":"create_journey","title":"Quiet focus","stages":["A","B","C","D","E"]}""" + "\n" +
            ">>>"
        val p = ReplyMarkers.parse(reply, today)
        assertEquals("Let's make it real.", p.visible)
        assertEquals(1, p.actions.size)
        assertEquals(0, p.failures)
        assertEquals("create_journey", p.actions.single().kind)
        assertEquals("Quiet focus", p.actions.single().title)
        assertTrue(p.hasActions)
    }

    @Test
    fun `the new action names and fields all read`() {
        val resolve = ReplyMarkers.parse("""<<<TOOL_CALL {"action":"resolve_promise","promise_id":12,"status":"KEPT"} >>>""", today)
            .actions.single()
        assertTrue(resolve.known)
        assertEquals(12L, resolve.promiseId)
        assertEquals("KEPT", resolve.status)

        val memory = ReplyMarkers.parse("""<<<TOOL_CALL {"action":"store_memory","category":"BELIEF","insight":"You go quiet","emotional_valence":-1} >>>""", today)
            .actions.single()
        assertTrue(memory.known)
        assertEquals(-1, memory.emotionalValence)

        val award = ReplyMarkers.parse("""<<<TOOL_CALL {"action":"award_milestone","action_key":"JOURNEY_STAGE_COMPLETE","evidence":"five days"} >>>""", today)
            .actions.single()
        assertEquals("JOURNEY_STAGE_COMPLETE", award.actionKey)

        val cadence = ReplyMarkers.parse("""<<<TOOL_CALL {"action":"set_cadence","pace_multiplier":1.5,"haptic_pulse":"CRISP"} >>>""", today)
            .actions.single()
        assertEquals(1.5, cadence.paceMultiplier!!, 0.0001)
        assertEquals("CRISP", cadence.hapticPulse)

        val promise = ReplyMarkers.parse("""<<<TOOL_CALL {"action":"record_promise","title":"Walk","due_epoch_ms":1791240000000} >>>""", today)
            .actions.single()
        assertEquals(1791240000000L, promise.dueEpochMs)
    }

    @Test
    fun `reschedule and edit promise actions read`() {
        val resched = ReplyMarkers.parse(
            """<<<TOOL_CALL {"action":"reschedule_promise","promise_id":12,"new_due_epoch_ms":1791243600000,"reason":"Moved to tomorrow"} >>>""",
            today,
        ).actions.single()
        assertTrue(resched.known)
        assertEquals(12L, resched.promiseId)
        assertEquals(1791243600000L, resched.newDueEpochMs)
        assertEquals("Moved to tomorrow", resched.reason)

        val edit = ReplyMarkers.parse(
            """<<<TOOL_CALL {"action":"edit_promise","promise_id":12,"new_title":"Read two pages","new_due_epoch_ms":1791243600000} >>>""",
            today,
        ).actions.single()
        assertTrue(edit.known)
        assertEquals("Read two pages", edit.newTitle)
        assertEquals(1791243600000L, edit.newDueEpochMs)
    }

    @Test
    fun `journey replace and archive actions read`() {
        val replace = ReplyMarkers.parse(
            """<<<TOOL_CALL {"action":"replace_journey","title":"Quiet focus","stages":["A","B","C","D","E"]} >>>""",
            today,
        ).actions.single()
        assertTrue(replace.known)
        assertEquals("replace_journey", replace.kind)
        assertEquals(5, replace.stages.size)

        val archive = ReplyMarkers.parse("""<<<TOOL_CALL {"action":"archive_journey","journey_id":7} >>>""", today)
            .actions.single()
        assertTrue(archive.known)
        assertEquals(7L, archive.journeyId)
    }

    @Test
    fun `advance_journey reads as a known action`() {
        val advance = ReplyMarkers.parse("""<<<TOOL_CALL {"action":"advance_journey"} >>>""", today).actions.single()
        assertTrue(advance.known)
        assertEquals("advance_journey", advance.kind)
    }

    @Test
    fun `nothing from a tool call block ever shows while streaming`() {
        val reply = "Here you go.\n<<<TOOL_CALL {\"action\":\"record_promise\",\"title\":\"x\"} >>>"
        for (i in reply.indices) {
            val shown = ReplyMarkers.visibleWhileStreaming(reply.substring(0, i + 1))
            assertFalse(shown, shown.contains("TOOL_CALL") || shown.contains("record_promise") || shown.endsWith("<"))
        }
    }

    @Test
    fun `an unreadable tool call is hidden and counted once`() {
        val p = ReplyMarkers.parse("Fine.\n<<<TOOL_CALL {not valid json} >>>", today)
        assertEquals("Fine.", p.visible)
        assertTrue(p.actions.isEmpty())
        assertEquals(1, p.failures)
    }
}

package com.umair.purpose.chat

import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiMessage.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptBuilderTest {
    private val prefix = ChatPromptBuilder.StablePrefix(persona = "PERSONA")
    private val flags = RuntimeFlags(now = "2026-10-03T21:14 Saturday", listenOnly = false, toughLove = ToughLove.BALANCED)

    @Test
    fun `persona comes first, flags next, then the conversation`() {
        val out = ChatPromptBuilder.build(prefix, flags, listOf(Turn(Role.USER, "hi")))
        assertEquals(AiMessage(Role.SYSTEM, "PERSONA"), out[0])
        assertEquals(Role.SYSTEM, out[1].role)
        assertTrue(out[1].content.contains("now: 2026-10-03T21:14 Saturday"))
        assertTrue(out[1].content.contains("listen_only: false"))
        assertTrue(out[1].content.contains("tough_love_level: balanced"))
        assertFalse(out[1].content.contains("mode:"))
        assertEquals(AiMessage(Role.USER, "hi"), out[2])
        assertEquals(3, out.size)
    }

    @Test
    fun `full order is persona, context, summaries, mode, due promise, flags, messages`() {
        val full = prefix.copy(contextBlock = "CONTEXT", recentSummaries = "SUMMARIES")
        val out = ChatPromptBuilder.build(
            full,
            flags.copy(mode = Mode.JOURNEY, journeyName = "Break the avoidance loop", journeyDay = 3),
            listOf(Turn(Role.USER, "hi")),
            duePromise = "Two chapters by Thursday",
            modeInstructions = "MODE",
        )
        // Session-stable parts first so the provider can reuse them, then what changes per request.
        assertEquals(listOf("PERSONA", "CONTEXT", "SUMMARIES", "MODE"), out.take(4).map { it.content })
        assertTrue(out[4].content.contains("Two chapters"))
        assertTrue(out[5].content.startsWith(ChatPromptBuilder.SYSTEM_ANCHOR))
        assertTrue(out[5].content.contains("Runtime flags:"))
        assertTrue(out[5].content.contains("mode: journey"))
        assertTrue(out[5].content.contains("journey_name: Break the avoidance loop"))
        assertTrue(out[5].content.contains("journey_day: 3"))
        assertEquals("hi", out[6].content)
    }

    @Test
    fun `mode instructions are part of the reusable prefix`() {
        val full = prefix.copy(contextBlock = "CONTEXT", recentSummaries = "SUMMARIES")
        val a = ChatPromptBuilder.build(full, flags, listOf(Turn(Role.USER, "one")), modeInstructions = "MODE")
        val b = ChatPromptBuilder.build(
            full,
            flags.copy(now = "later", listenOnly = true),
            listOf(Turn(Role.USER, "one"), Turn(Role.ASSISTANT, "two")),
            duePromise = "x",
            modeInstructions = "MODE",
            timeFacts = "TIME",
            relevantPast = "PAST",
        )
        // persona, context, summaries, mode instructions — identical across the two requests, so all four are
        // reusable. Everything volatile (due promise, time facts, relevant past, flags) sits after them.
        assertEquals(4, ChatPromptBuilder.stableCount(full, "MODE"))
        assertEquals(listOf("PERSONA", "CONTEXT", "SUMMARIES", "MODE"), a.take(4).map { it.content })
        assertEquals(a.take(4), b.take(4))
        // With no mode instructions the prefix is shorter, and stableCount agrees.
        assertEquals(3, ChatPromptBuilder.stableCount(full, null))
        assertEquals(1, ChatPromptBuilder.stableCount(prefix, null))
    }

    @Test
    fun `prefix stays byte-identical when flags, mode and messages change`() {
        val full = prefix.copy(contextBlock = "CONTEXT", recentSummaries = "SUMMARIES")
        val a = ChatPromptBuilder.build(full, flags, listOf(Turn(Role.USER, "one")))
        val b = ChatPromptBuilder.build(
            full,
            flags.copy(now = "later", listenOnly = true, toughLove = ToughLove.FIRM, mode = Mode.PRACTICE, practiceWith = "Abbu"),
            listOf(Turn(Role.USER, "one"), Turn(Role.ASSISTANT, "two"), Turn(Role.USER, "three")),
            duePromise = "x",
            modeInstructions = "MODE",
        )
        assertEquals(a.take(3), b.take(3))
        val flagsB = b.first { it.content.contains("Runtime flags:") }.content
        assertTrue(flagsB.contains("listen_only: true"))
        assertTrue(flagsB.contains("tough_love_level: firm"))
        assertTrue(flagsB.contains("practice_with: Abbu"))
    }

    @Test
    fun `blank context and summaries are left out`() {
        val out = ChatPromptBuilder.build(prefix.copy(contextBlock = " ", recentSummaries = ""), flags, emptyList())
        assertEquals(2, out.size)
    }

    @Test
    fun `old messages are trimmed and the window starts on a user message`() {
        val turns = (1..10).map { Turn(if (it % 2 == 1) Role.USER else Role.ASSISTANT, "m$it") }
        val out = ChatPromptBuilder.build(prefix, flags, turns, maxMessages = 5)
        val convo = out.drop(2)
        // last 5 = m6..m10, m6 is assistant so it is dropped
        assertEquals(listOf("m7", "m8", "m9", "m10"), convo.map { it.content })
        assertEquals(Role.USER, convo.first().role)
    }

    @Test
    fun `a short conversation that starts with the opening line is sent whole`() {
        val turns = listOf(Turn(Role.ASSISTANT, "How's that sitting with you today?"), Turn(Role.USER, "a"), Turn(Role.ASSISTANT, "b"))
        assertEquals(turns, ChatPromptBuilder.trim(turns, 80))
    }

    @Test
    fun `tool schemas sit right after the persona, inside the cached prefix`() {
        val withTools = prefix.copy(toolSchemas = "TOOLS", contextBlock = "CONTEXT", recentSummaries = "SUMMARIES")
        val out = ChatPromptBuilder.build(withTools, flags, listOf(Turn(Role.USER, "hi")), modeInstructions = "MODE")
        assertEquals(listOf("PERSONA", "TOOLS", "CONTEXT", "SUMMARIES", "MODE"), out.take(5).map { it.content })
        assertEquals(5, ChatPromptBuilder.stableCount(withTools, "MODE"))
        // Without tools the prefix is one shorter, and stableCount agrees.
        assertEquals(4, ChatPromptBuilder.stableCount(prefix.copy(contextBlock = "C", recentSummaries = "S"), "MODE"))
        assertEquals(1, ChatPromptBuilder.stableCount(prefix, null))
    }

    @Test
    fun `last actions ride in the volatile flags, never the prefix`() {
        val a = ChatPromptBuilder.build(prefix, flags, listOf(Turn(Role.USER, "one")))
        val b = ChatPromptBuilder.build(prefix, flags.copy(lastActions = "record_promise=Walk"), listOf(Turn(Role.USER, "one")))
        assertEquals(a[0], b[0])
        val flagsB = b.first { it.content.contains("Runtime flags:") }.content
        assertTrue(flagsB.contains("last_actions: record_promise=Walk"))
        assertFalse(a.first { it.content.contains("Runtime flags:") }.content.contains("last_actions:"))
    }

    @Test
    fun `the sliding window keeps the last sixteen turns and archives the rest`() {
        val turns = (1..40).map { Turn(if (it % 2 == 1) Role.USER else Role.ASSISTANT, "m$it") }
        assertTrue(turns.size > ChatPromptBuilder.SLIDING_WINDOW_MESSAGES)
        val out = ChatPromptBuilder.build(prefix, flags, turns)
        val convo = out.drop(2)
        assertTrue(convo.size <= ChatPromptBuilder.SLIDING_WINDOW_MESSAGES)
        assertEquals("m40", convo.last().content)
        // m25 is the first of the last 16, and it's a user turn, so nothing more is dropped.
        assertEquals("m25", convo.first().content)
        // The anchor tells the model the earlier turns are archived, not forgotten.
        val anchor = out[1].content
        assertTrue(anchor.contains(ChatPromptBuilder.ARCHIVED_HISTORY_NOTE))
    }

    @Test
    fun `a short conversation carries no archive note`() {
        val out = ChatPromptBuilder.build(prefix, flags, listOf(Turn(Role.USER, "a"), Turn(Role.ASSISTANT, "b")))
        assertFalse(out[1].content.contains(ChatPromptBuilder.ARCHIVED_HISTORY_NOTE))
    }

    @Test
    fun `relevant insights sit in the volatile tail, after relevant past and before the anchor`() {
        val out = ChatPromptBuilder.build(
            prefix.copy(contextBlock = "CONTEXT"),
            flags,
            listOf(Turn(Role.USER, "hi")),
            relevantPast = "PAST",
            relevantInsights = "RELEVANT INSIGHTS & REALIZATIONS:\n- you study best at night",
        )
        assertEquals(listOf("PERSONA", "CONTEXT"), out.take(2).map { it.content })
        assertEquals("PAST", out[2].content)
        assertTrue(out[3].content.startsWith("RELEVANT INSIGHTS & REALIZATIONS:"))
        assertTrue(out[4].content.startsWith(ChatPromptBuilder.SYSTEM_ANCHOR))
        assertEquals("hi", out[5].content)
    }

    @Test
    fun `unknown values fall back`() {
        assertEquals(ToughLove.BALANCED, ToughLove.fromWire("brutal"))
        assertEquals(ToughLove.FIRM, ToughLove.fromWire("firm"))
        assertEquals(Mode.PRACTICE, Mode.fromWire(" Practice "))
        assertEquals(null, Mode.fromWire("chat"))
        assertEquals("mode_untangle.md", Mode.UNTANGLE.promptFile)
    }
}

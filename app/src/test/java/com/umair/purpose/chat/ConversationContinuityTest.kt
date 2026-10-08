package com.umair.purpose.chat

import com.umair.purpose.ai.AiMessage.Role
import org.junit.Assert.*
import org.junit.Test

class ConversationContinuityTest {
    @Test fun `answer just outside the window remains available without changing the cached prefix`() {
        val turns = listOf(Turn(Role.USER, "I already told you it is fear of failing")) + (1..16).map { Turn(Role.USER, "Later $it") }
        val continuity = ConversationContinuity.block(turns, null)
        assertTrue(continuity!!.contains("fear of failing"))
        val prefix = ChatPromptBuilder.StablePrefix("persona", contextBlock = "memory")
        val flags = RuntimeFlags("now", false, ToughLove.BALANCED)
        val before = ChatPromptBuilder.build(prefix, flags, turns.take(16))
        val after = ChatPromptBuilder.build(prefix, flags, turns, continuity = continuity)
        assertEquals(before.take(2), after.take(2))
        assertEquals("Later 16", after.last().content)
    }

    @Test fun `short conversations do not get duplicate history`() {
        assertNull(ConversationContinuity.block(listOf(Turn(Role.USER, "hello")), "old summary"))
    }

    @Test fun `bridge remains bounded in a years-long conversation`() {
        val turns = (1..1000).map { Turn(Role.USER, "x".repeat(10000)) }
        val block = ConversationContinuity.block(turns, "s".repeat(10000))!!
        assertTrue(block.length < 3600)
        assertTrue(block.contains("[excerpt ends]"))
    }
}

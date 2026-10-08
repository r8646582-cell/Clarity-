package com.umair.purpose.chat

import com.umair.purpose.ai.AiMessage.Role
import org.junit.Assert.*
import org.junit.Test

class CurrentStateTest {
    @Test fun `fresh app state follows cached prefix and precedes conversation`() {
        val prefix = ChatPromptBuilder.StablePrefix("persona", contextBlock = "old journey")
        val flags = RuntimeFlags("today", false, ToughLove.BALANCED)
        val first = ChatPromptBuilder.build(prefix, flags, listOf(Turn(Role.USER, "hello")), currentState = "Journey: active")
        val next = ChatPromptBuilder.build(prefix, flags, listOf(Turn(Role.USER, "hello")), currentState = "Journey: none")
        val count = ChatPromptBuilder.stableCount(prefix, null)
        assertEquals(first.take(count), next.take(count))
        assertTrue(next.drop(count).any { it.role == Role.SYSTEM && it.content == "Journey: none" })
        assertEquals(Role.USER, next.last().role)
        assertFalse(next.any { it.content == "Journey: active" })
    }
}

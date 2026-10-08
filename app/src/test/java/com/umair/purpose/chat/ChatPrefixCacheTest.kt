package com.umair.purpose.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatPrefixCacheTest {
    @Test fun `clear during build retries and never returns old prefix`() = runTest {
        val cache = ChatPrefixCache()
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var builds = 0
        val result = async {
            cache.getOrBuild(1) {
                builds++
                if (builds == 1) { reading.complete(Unit); resume.await(); ChatPromptBuilder.StablePrefix("old") }
                else ChatPromptBuilder.StablePrefix("new")
            }
        }
        reading.await(); cache.clear(); resume.complete(Unit)
        assertEquals("new", result.await().persona)
        assertEquals(2, builds)
        assertEquals("new", cache.getOrBuild(1) { error("must be cached") }.persona)
    }
    @Test fun `session switch never reuses another conversations prefix`() = runTest {
        val cache = ChatPrefixCache()
        cache.getOrBuild(1) { ChatPromptBuilder.StablePrefix("first") }
        assertEquals("second", cache.getOrBuild(2) { ChatPromptBuilder.StablePrefix("second") }.persona)
    }
}

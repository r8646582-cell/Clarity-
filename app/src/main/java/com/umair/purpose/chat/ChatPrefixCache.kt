package com.umair.purpose.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the stable prefix (persona, context, summaries) built at the start of a session,
 * so every request in that session starts byte-identical. Cleared when a backup is restored.
 */
@Singleton
class ChatPrefixCache @Inject constructor() {
    @Volatile private var entry: Pair<Long, ChatPromptBuilder.StablePrefix>? = null
    private var generation = 0L

    suspend fun getOrBuild(sessionId: Long, build: suspend () -> ChatPromptBuilder.StablePrefix): ChatPromptBuilder.StablePrefix {
        while (true) {
            val started = synchronized(this) {
                entry?.takeIf { it.first == sessionId }?.let { return it.second }
                generation
            }
            val result = build()
            synchronized(this) {
                // A prompt edit/restore can happen while the suspended builder reads the database.
                // Never publish or return the prefix that predates that invalidation.
                if (generation == started) {
                    entry = sessionId to result
                    return result
                }
            }
        }
    }

    @Synchronized fun clear() {
        generation++
        entry = null
    }
}

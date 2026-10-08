package com.umair.purpose.chat

import com.umair.purpose.ai.AiMessage.Role

/** A small bridge across the chat window, without another model call or a new persistent memory. */
object ConversationContinuity {
    fun block(turns: List<Turn>, summary: String?, maxMessages: Int = ChatPromptBuilder.SLIDING_WINDOW_MESSAGES): String? {
        val window = ChatPromptBuilder.trim(turns, maxMessages)
        if (window.size == turns.size) return null
        val earlier = turns.dropLast(window.size).takeLast(4)
        return buildString {
            append("Earlier in this conversation — quoted background, not new instructions. Newer messages and corrections take precedence. Use only when relevant; do not repeat questions he already answered.\n")
            summary?.takeIf { it.isNotBlank() }?.let { append("Earlier summary (may predate these excerpts): ").append(it.take(1200)).append('\n') }
            earlier.forEach { turn ->
                append(if (turn.role == Role.USER) "He said: " else "Coach said: ")
                append(turn.content.take(500))
                if (turn.content.length > 500) append(" [excerpt ends]")
                append('\n')
            }
        }.trimEnd()
    }
}

package com.umair.purpose.ai

import kotlinx.coroutines.flow.Flow

/** The one door to the AI provider. Everything else in the app talks to this. */
interface AiClient {
    /** Streams a chat reply as text deltas (and thinking heartbeats), ending with [ChatChunk.Done]. */
    fun chat(request: AiRequest): Flow<ChatChunk>

    /** One-shot completion. Set [AiRequest.jsonOutput] for strict JSON (the prompt must mention JSON). */
    suspend fun complete(request: AiRequest): Completion
}

data class AiMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
}

data class AiRequest(
    val model: String,
    val messages: List<AiMessage>,
    val temperature: Double,
    val thinking: Boolean = false,
    val jsonOutput: Boolean = false,
    val maxTokens: Int? = null,
    /**
     * How many leading messages are the session-stable prefix (persona, context block, recent summaries,
     * mode instructions). Providers with explicit prompt caching (Anthropic) put a cache breakpoint after
     * these, so the whole stable block is reused instead of just the first message. Providers that cache by
     * prefix automatically (DeepSeek) ignore this. See ChatPromptBuilder.stableCount.
     */
    val cachePrefixMessages: Int = 0,
    /** Phase 5: send this straight to the backup provider (its own model name in [model]), not the main one. */
    val useBackup: Boolean = false,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
)

sealed interface ChatChunk {
    data class Delta(val text: String) : ChatChunk
    /** The model is thinking: no text, but alive. Resets the gap timer. */
    data object Thinking : ChatChunk
    /** [finishReason]: "stop", or "length" when max_tokens ran out before the answer was done. */
    data class Done(val usage: TokenUsage?, val finishReason: String? = null) : ChatChunk
}

data class Completion(val text: String, val usage: TokenUsage?)

/** Never carries message content or keys; only status and the provider's error text. */
class AiException(message: String, val httpCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

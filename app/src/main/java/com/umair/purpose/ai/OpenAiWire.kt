package com.umair.purpose.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.BufferedSource

/** Request building and response/SSE parsing for OpenAI-compatible chat completions. No I/O here. */
object OpenAiWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun requestBody(request: AiRequest, stream: Boolean, supportsThinkingToggle: Boolean): String =
        buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.wire)
                        put("content", m.content)
                    })
                }
            })
            put("temperature", request.temperature)
            request.maxTokens?.let { put("max_tokens", it) }
            if (supportsThinkingToggle) {
                putJsonObject("thinking") { put("type", if (request.thinking) "enabled" else "disabled") }
            }
            if (request.jsonOutput) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            if (stream) {
                put("stream", true)
                putJsonObject("stream_options") { put("include_usage", true) }
            }
        }.toString()

    sealed interface SseEvent {
        data class Data(val payload: String) : SseEvent
        data object End : SseEvent
    }

    /** One line of an SSE stream. Returns null for blank lines, comments (keep-alives) and other fields. */
    fun parseSseLine(line: String): SseEvent? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        return if (payload == "[DONE]") SseEvent.End else SseEvent.Data(payload)
    }

    /**
     * Reads a whole SSE stream. Lines are read from the buffered UTF-8 source, so a network chunk that splits
     * a line, or a multi-byte character, is reassembled before decoding. Only complete `data:` lines are
     * parsed; blank lines and `:` keep-alives are skipped; reading stops at `[DONE]`.
     * Calls [onDelta] with each piece of answer text. A stream that ends without `[DONE]` was cut off.
     */
    suspend fun readStream(
        source: BufferedSource,
        onHeartbeat: suspend () -> Unit = {},
        onDelta: suspend (String) -> Unit,
    ): StreamResult {
        var usage: TokenUsage? = null
        var finish: String? = null
        while (true) {
            val line = source.readUtf8Line() ?: return StreamResult(usage, complete = false, finish)
            when (val event = parseSseLine(line)) {
                null -> Unit
                SseEvent.End -> return StreamResult(usage, complete = true, finish)
                is SseEvent.Data -> {
                    val chunk = parseStreamChunk(event.payload)
                    // An error object in the middle of the stream: the reply won't finish.
                    chunk.error?.let { throw AiException(it) }
                    chunk.usage?.let { usage = it }
                    chunk.finishReason?.let { finish = it }
                    if (!chunk.delta.isNullOrEmpty()) onDelta(chunk.delta)
                    // Thinking chunks carry no answer text, but they prove the model is alive.
                    else if (chunk.reasoning) onHeartbeat()
                }
            }
        }
    }

    /** [finishReason]: "stop" for a finished answer, "length" when max_tokens ran out first. */
    data class StreamResult(val usage: TokenUsage?, val complete: Boolean, val finishReason: String? = null)

    data class StreamChunk(
        val delta: String?,
        val usage: TokenUsage?,
        /** A thinking chunk (`reasoning_content`): never shown, but a sign of life. */
        val reasoning: Boolean = false,
        val finishReason: String? = null,
        /** The provider's error message, when the chunk is an error object. */
        val error: String? = null,
    )

    /** Parses one streamed `chat.completion.chunk`. Reasoning text is never kept; only the answer is shown. */
    fun parseStreamChunk(payload: String): StreamChunk {
        val obj = json.parseToJsonElement(payload).jsonObject
        obj["error"]?.let { e ->
            val message = (e as? JsonObject)?.get("message")?.jsonPrimitiveOrNull()?.contentOrNull
                ?: e.jsonPrimitiveOrNull()?.contentOrNull
            return StreamChunk(null, null, error = message ?: "The provider sent an error")
        }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObjectOrNull()
        val delta = choice?.get("delta")?.jsonObjectOrNull()
        val content = delta?.get("content")?.jsonPrimitiveOrNull()?.contentOrNull
        val reasoning = !delta?.get("reasoning_content")?.jsonPrimitiveOrNull()?.contentOrNull.isNullOrEmpty()
        val finish = choice?.get("finish_reason")?.jsonPrimitiveOrNull()?.contentOrNull
        return StreamChunk(content, parseUsage(obj), reasoning = reasoning, finishReason = finish)
    }

    fun parseCompletion(body: String): Completion {
        val obj = json.parseToJsonElement(body).jsonObject
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        if (choice?.get("finish_reason")?.jsonPrimitiveOrNull()?.contentOrNull == "length") {
            throw AiException("The response hit its output limit")
        }
        val text = choice
            ?.get("message")?.jsonObjectOrNull()
            ?.get("content")?.jsonPrimitiveOrNull()?.contentOrNull
            ?: throw AiException("Response had no message content")
        return Completion(text, parseUsage(obj))
    }

    /** Pulls the provider's error message out of an error body, if it has the usual shape. */
    fun parseErrorMessage(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObjectOrNull()
            ?.get("message")?.jsonPrimitiveOrNull()?.contentOrNull
    }.getOrNull()

    private fun parseUsage(obj: JsonObject): TokenUsage? {
        val u = obj["usage"]?.jsonObjectOrNull() ?: return null
        fun int(key: String) = u[key]?.jsonPrimitiveOrNull()?.intOrNull ?: 0
        val prompt = int("prompt_tokens")
        val hit = int("prompt_cache_hit_tokens")
        val miss = u["prompt_cache_miss_tokens"]?.jsonPrimitiveOrNull()?.intOrNull ?: (prompt - hit)
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = int("completion_tokens"),
            cacheHitTokens = hit,
            cacheMissTokens = miss,
        )
    }

    private fun JsonElement.jsonObjectOrNull() = this as? JsonObject
    private fun JsonElement.jsonPrimitiveOrNull() = this as? JsonPrimitive
}

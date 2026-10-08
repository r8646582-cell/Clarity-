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

/**
 * UPDATE-16: Anthropic's Messages API, for the backup provider. Request building and stream parsing, no I/O.
 * System messages become the `system` blocks (the first one cached, as the persona is the same every time);
 * the conversation must start with him and alternate, so neighbours with the same role are joined.
 */
object AnthropicWire {
    const val VERSION = "2023-06-01"
    /** Messages API needs a limit; this is enough for a full reply. */
    const val DEFAULT_MAX_TOKENS = 4096
    private val json = Json { ignoreUnknownKeys = true }

    fun requestBody(request: AiRequest, stream: Boolean): String = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxTokens?.coerceAtMost(32_000) ?: DEFAULT_MAX_TOKENS)
        put("temperature", request.temperature.coerceIn(0.0, 1.0))
        val system = request.messages.filter { it.role == AiMessage.Role.SYSTEM }.map { it.content }
        if (system.isNotEmpty()) {
            // One breakpoint at the END of the session-stable prefix caches everything up to it (persona, context
            // block, summaries, mode instructions) rather than only the persona. Without a hint from the caller,
            // fall back to caching the first block, which is the persona.
            val breakpoint = (request.cachePrefixMessages.takeIf { it > 0 } ?: 1).coerceIn(1, system.size) - 1
            put("system", buildJsonArray {
                system.forEachIndexed { i, text ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                        if (i == breakpoint) putJsonObject("cache_control") { put("type", "ephemeral") }
                    })
                }
            })
        }
        put("messages", buildJsonArray {
            turns(request.messages).forEach { (role, text) ->
                add(buildJsonObject {
                    put("role", role)
                    put("content", text)
                })
            }
        })
        if (stream) put("stream", true)
    }.toString()

    /** The conversation in the shape the API accepts: starts with "user", roles alternate. */
    internal fun turns(messages: List<AiMessage>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (m in messages.filter { it.role != AiMessage.Role.SYSTEM }) {
            val role = if (m.role == AiMessage.Role.USER) "user" else "assistant"
            if (out.isEmpty() && role == "assistant") out += "user" to "(The conversation begins.)"
            if (out.isNotEmpty() && out.last().first == role) out[out.lastIndex] = role to (out.last().second + "\n\n" + m.content)
            else out += role to m.content
        }
        if (out.isEmpty()) out += "user" to "(Go ahead.)"
        return out
    }

    suspend fun readStream(source: BufferedSource, onHeartbeat: suspend () -> Unit = {}, onDelta: suspend (String) -> Unit): OpenAiWire.StreamResult {
        var input = 0
        var cacheRead = 0
        var cacheWrite = 0
        var output = 0
        var finish: String? = null
        while (true) {
            val line = source.readUtf8Line() ?: return OpenAiWire.StreamResult(usage(input, cacheRead, cacheWrite, output), complete = false, finish)
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim().takeIf { it.isNotEmpty() } ?: continue
            val obj = json.parseToJsonElement(payload).jsonObject
            when (obj.str("type")) {
                "message_start" -> obj["message"]?.obj()?.get("usage")?.obj()?.let { u ->
                    input = u.int("input_tokens"); cacheRead = u.int("cache_read_input_tokens"); cacheWrite = u.int("cache_creation_input_tokens")
                    output = u.int("output_tokens")
                }
                "content_block_delta" -> {
                    val d = obj["delta"]?.obj()
                    when (d?.str("type")) {
                        "text_delta" -> d.str("text")?.takeIf { it.isNotEmpty() }?.let { onDelta(it) }
                        else -> onHeartbeat()
                    }
                }
                "message_delta" -> {
                    obj["delta"]?.obj()?.str("stop_reason")?.let { finish = stopReason(it) }
                    obj["usage"]?.obj()?.let { output = it.int("output_tokens").takeIf { o -> o > 0 } ?: output }
                }
                "message_stop" -> return OpenAiWire.StreamResult(usage(input, cacheRead, cacheWrite, output), complete = true, finish)
                "error" -> throw AiException(obj["error"]?.obj()?.str("message") ?: "The provider sent an error")
                else -> onHeartbeat()
            }
        }
    }

    fun parseCompletion(body: String): Completion {
        val obj = json.parseToJsonElement(body).jsonObject
        if (obj.str("stop_reason") == "max_tokens") throw AiException("The response hit its output limit")
        val text = obj["content"]?.jsonArray?.mapNotNull { b -> b.obj()?.takeIf { it.str("type") == "text" }?.str("text") }
            ?.joinToString("")?.takeIf { it.isNotEmpty() } ?: throw AiException("Response had no message content")
        val u = obj["usage"]?.obj()
        return Completion(text, u?.let { usage(it.int("input_tokens"), it.int("cache_read_input_tokens"), it.int("cache_creation_input_tokens"), it.int("output_tokens")) })
    }

    fun parseErrorMessage(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.obj()?.str("message")
    }.getOrNull()

    /** Anthropic counts cached input apart from the rest; here it's mapped onto the same fields as DeepSeek's. */
    private fun usage(input: Int, cacheRead: Int, cacheWrite: Int, output: Int) =
        TokenUsage(promptTokens = input + cacheRead + cacheWrite, completionTokens = output, cacheHitTokens = cacheRead, cacheMissTokens = input + cacheWrite)

    private fun stopReason(r: String) = when (r) {
        "max_tokens" -> "length"
        "end_turn", "stop_sequence" -> "stop"
        else -> r
    }

    private fun JsonElement.obj() = this as? JsonObject
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
}

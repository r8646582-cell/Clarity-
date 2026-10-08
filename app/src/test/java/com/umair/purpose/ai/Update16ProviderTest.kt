package com.umair.purpose.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** UPDATE-16: the backup provider takes over after 3 failures in a row, and gives way when the main one is back. */
class Update16ProviderTest {
    private var clock = 0L
    private val req = AiRequest("deepseek-v4-pro", listOf(AiMessage(AiMessage.Role.SYSTEM, "persona"), AiMessage(AiMessage.Role.USER, "hi")), 0.7, thinking = true)

    private class Fake(var fail: Exception? = null) : AiClient {
        var calls = 0
        var lastModel: String? = null
        override fun chat(request: AiRequest): Flow<ChatChunk> = flow {
            calls++
            lastModel = request.model
            fail?.let { throw it }
            emit(ChatChunk.Delta("hello"))
            emit(ChatChunk.Done(null, "stop"))
        }
        override suspend fun complete(request: AiRequest): Completion {
            calls++
            lastModel = request.model
            fail?.let { throw it }
            return Completion("ok", null)
        }
    }

    private val outage = AiException("down", httpCode = 503)

    @Test
    fun `what counts as the provider failing`() {
        assertTrue(FailoverPolicy.countsAsOutage(AiException("x", 500), online = true))
        assertTrue(FailoverPolicy.countsAsOutage(AiException("x", 401), online = true))
        assertTrue(FailoverPolicy.countsAsOutage(AiException("x", 402), online = true))
        assertTrue(FailoverPolicy.countsAsOutage(AiException("Network error", cause = IOException()), online = true))
        // No internet on the phone is not the provider's fault; nor is a bad request or a missing key.
        assertFalse(FailoverPolicy.countsAsOutage(AiException("Network error", cause = IOException()), online = false))
        assertFalse(FailoverPolicy.countsAsOutage(AiException("x", 400), online = true))
        assertFalse(FailoverPolicy.countsAsOutage(AiException("No API key set"), online = true))
    }

    @Test
    fun `switches after 3 failures in a row, maps the models, and switches back`() = runBlocking {
        val main = Fake(outage)
        val backup = Fake()
        val policy = FailoverPolicy { clock }
        val client = FailoverAiClient(
            main, backup = { backup to { r -> FailoverAiClient.mapRequest(r, "deepseek-v4-pro", "b-fast", "b-deep") } }, policy, online = { true },
        )
        repeat(2) { runCatching { client.complete(req) }.also { assertTrue(it.isFailure) } }
        assertFalse(policy.usingBackup.value)
        // The third failure switches, and this very request is answered by the backup.
        assertEquals("ok", client.complete(req).text)
        assertTrue(policy.usingBackup.value)
        assertEquals("b-deep", backup.lastModel)
        // While on the backup, the main one isn't tried again until the probe time.
        val before = main.calls
        client.complete(req.copy(model = "deepseek-v4-flash"))
        assertEquals(before, main.calls)
        assertEquals("b-fast", backup.lastModel)
        // Ten minutes on, the main one is tried first; it works again, so it's the main one again.
        clock += FailoverPolicy.PROBE_MS
        main.fail = null
        client.complete(req)
        assertFalse(policy.usingBackup.value)
    }

    @Test
    fun `with no backup set it errors as before`() = runBlocking {
        val policy = FailoverPolicy { clock }
        val client = FailoverAiClient(Fake(outage), backup = { null }, policy, online = { true })
        repeat(4) { assertTrue(runCatching { client.chat(req).toList() }.isFailure) }
        assertFalse(policy.usingBackup.value)
    }

    @Test
    fun `Anthropic requests and streams`() = runBlocking {
        val body = AnthropicWire.requestBody(
            req.copy(messages = listOf(
                AiMessage(AiMessage.Role.SYSTEM, "persona"), AiMessage(AiMessage.Role.SYSTEM, "context"),
                AiMessage(AiMessage.Role.ASSISTANT, "Salaam."), AiMessage(AiMessage.Role.USER, "hi"), AiMessage(AiMessage.Role.USER, "again"),
            )),
            stream = true,
        )
        assertTrue(body.contains("\"system\":[{\"type\":\"text\",\"text\":\"persona\",\"cache_control\":{\"type\":\"ephemeral\"}}"))
        assertTrue(body.contains("\"messages\":[{\"role\":\"user\",\"content\":\"(The conversation begins.)\"},{\"role\":\"assistant\",\"content\":\"Salaam.\"},{\"role\":\"user\",\"content\":\"hi\\n\\nagain\"}]"))
        assertTrue(body.contains("\"max_tokens\":4096"))

        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()
        val text = StringBuilder()
        val r = AnthropicWire.readStream(Buffer().writeUtf8(sse)) { text.append(it) }
        assertEquals("Hello", text.toString())
        assertTrue(r.complete)
        assertEquals("stop", r.finishReason)
        assertEquals(TokenUsage(promptTokens = 100, completionTokens = 5, cacheHitTokens = 90, cacheMissTokens = 10), r.usage)
        assertEquals("ok", AnthropicWire.parseCompletion("""{"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":3,"output_tokens":1}}""").text)
    }
    @Test
    fun `truncated Anthropic completions are rejected instead of saved`() {
        val error = runCatching {
            AnthropicWire.parseCompletion("""{"content":[{"type":"text","text":"A partial letter"}],"stop_reason":"max_tokens"}""")
        }.exceptionOrNull()
        assertTrue(error is AiException)
    }
}

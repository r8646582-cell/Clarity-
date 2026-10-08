package com.umair.purpose.ai

import com.umair.purpose.ai.AiMessage.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiWireTest {
    private val request = AiRequest(
        model = "deepseek-v4-flash",
        messages = listOf(AiMessage(Role.SYSTEM, "persona"), AiMessage(Role.USER, "hi")),
        temperature = 1.0,
    )

    @Test
    fun `streaming chat body asks for usage and disables thinking`() {
        val body = Json.parseToJsonElement(OpenAiWire.requestBody(request, stream = true, supportsThinkingToggle = true)).jsonObject
        assertEquals("deepseek-v4-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(1.0, body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["response_format"])
    }

    @Test
    fun `json completion body sets response_format and can enable thinking`() {
        val r = request.copy(jsonOutput = true, thinking = true, temperature = 0.3)
        val body = Json.parseToJsonElement(OpenAiWire.requestBody(r, stream = false, supportsThinkingToggle = true)).jsonObject
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["stream"])
    }

    @Test
    fun `thinking field is omitted for providers that do not support it`() {
        val body = Json.parseToJsonElement(OpenAiWire.requestBody(request, stream = true, supportsThinkingToggle = false)).jsonObject
        assertFalse(body.containsKey("thinking"))
    }

    @Test
    fun `sse lines`() {
        assertEquals(OpenAiWire.SseEvent.Data("{\"a\":1}"), OpenAiWire.parseSseLine("data: {\"a\":1}"))
        assertEquals(OpenAiWire.SseEvent.End, OpenAiWire.parseSseLine("data: [DONE]"))
        assertNull(OpenAiWire.parseSseLine(""))
        assertNull(OpenAiWire.parseSseLine(": keep-alive"))
    }

    @Test
    fun `stream chunk with content`() {
        val chunk = OpenAiWire.parseStreamChunk(
            """{"id":"x","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
        )
        assertEquals("Hello", chunk.delta)
        assertNull(chunk.usage)
    }

    @Test
    fun `reasoning-only and null-content chunks yield no text`() {
        assertNull(OpenAiWire.parseStreamChunk("""{"choices":[{"delta":{"reasoning_content":"hmm","content":null}}]}""").delta)
        assertNull(OpenAiWire.parseStreamChunk("""{"choices":[{"delta":{"role":"assistant"}}]}""").delta)
    }

    @Test
    fun `final usage chunk with cache counts`() {
        val chunk = OpenAiWire.parseStreamChunk(
            """{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280,
               "prompt_cache_hit_tokens":1024,"prompt_cache_miss_tokens":176}}"""
        )
        assertEquals(TokenUsage(1200, 80, 1024, 176), chunk.usage)
    }

    @Test
    fun `usage without cache fields counts everything as a miss`() {
        val chunk = OpenAiWire.parseStreamChunk("""{"choices":[],"usage":{"prompt_tokens":50,"completion_tokens":5}}""")
        assertEquals(TokenUsage(50, 5, 0, 50), chunk.usage)
    }

    @Test
    fun `non-streaming completion`() {
        val c = OpenAiWire.parseCompletion(
            """{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"}}],"usage":{"prompt_tokens":10,"completion_tokens":3}}"""
        )
        assertEquals("{\"ok\":true}", c.text)
        assertEquals(10, c.usage!!.promptTokens)
    }

    @Test
    fun `provider error message is extracted`() {
        assertEquals("Authentication Fails", OpenAiWire.parseErrorMessage("""{"error":{"message":"Authentication Fails","type":"auth"}}"""))
        assertNull(OpenAiWire.parseErrorMessage("<html>bad gateway</html>"))
    }

    @Test
    fun `reasoning chunks are flagged as a heartbeat`() {
        val c = OpenAiWire.parseStreamChunk("""{"choices":[{"delta":{"reasoning_content":"hmm","content":null}}]}""")
        assertTrue(c.reasoning)
        assertNull(c.delta)
    }

    @Test
    fun `finish reason is read`() {
        val c = OpenAiWire.parseStreamChunk("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals("length", c.finishReason)
    }

    @Test
    fun `an error object in the stream is reported`() {
        val c = OpenAiWire.parseStreamChunk("""{"error":{"message":"Model overloaded","type":"server"}}""")
        assertEquals("Model overloaded", c.error)
    }

    @Test
    fun `stream with an error object ends the read with that error`() {
        val source = okio.Buffer().writeUtf8(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: {\"error\":{\"message\":\"overloaded\"}}\n\n"
        )
        val e = runCatching { kotlinx.coroutines.runBlocking { OpenAiWire.readStream(source) {} } }.exceptionOrNull()
        assertEquals("overloaded", (e as AiException).message)
    }

    @Test
    fun `stream result carries finish reason and heartbeats`() {
        val source = okio.Buffer().writeUtf8(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"a\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"
        )
        var beats = 0
        val r = kotlinx.coroutines.runBlocking { OpenAiWire.readStream(source, onHeartbeat = { beats++ }) {} }
        assertEquals(1, beats)
        assertEquals("length", r.finishReason)
        assertTrue(r.complete)
    }
    @Test
    fun `a truncated completion cannot be saved as a finished letter`() {
        val error = runCatching {
            OpenAiWire.parseCompletion("""{"choices":[{"message":{"content":"A partial letter"},"finish_reason":"length"}]}""")
        }.exceptionOrNull()
        assertTrue(error is AiException)
    }

    @Test
    fun `a null error field is not an error`() {
        val chunk = OpenAiWire.parseStreamChunk("""{"error":null,"choices":[{"delta":{"content":"hi"}}]}""")
        assertNull(chunk.error)
        assertEquals("hi", chunk.delta)
    }
}

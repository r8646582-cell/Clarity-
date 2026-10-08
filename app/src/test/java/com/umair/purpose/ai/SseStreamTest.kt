package com.umair.purpose.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The garbled-reply fix: a recorded SSE response, cut into network chunks at random byte positions
 * (including in the middle of multi-byte Urdu characters), must reassemble to exactly the same text.
 */
class SseStreamTest {
    /** Hands out the bytes in the given chunk sizes, one chunk per read, like a slow network. */
    private class ChunkedSource(private val bytes: ByteArray, private val cuts: List<Int>) : Source {
        private var pos = 0
        private var cut = 0
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (pos >= bytes.size) return -1
            val end = minOf(cuts.getOrElse(cut++) { bytes.size }, bytes.size, pos + byteCount.toInt()).coerceAtLeast(pos + 1)
            sink.write(bytes, pos, end - pos)
            val n = end - pos
            pos = end
            return n.toLong()
        }
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    private val pieces = listOf(
        "Umair, ", "yeh ", "baat ", "sach ", "hai. ", "آپ ", "کافی ", "ہیں", "۔ ", "\n\n", "You are ",
        "not wasting time — ", "you're ", "learning ", "what doesn't ", "work. ", "Shukriya 🙏", " \"quoted\" ",
    )

    private fun recorded(): String = buildString {
        append(": keep-alive\n\n")
        pieces.forEachIndexed { i, p ->
            val content = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(p))
            // A reasoning chunk now and then, which must never reach the message.
            if (i % 5 == 0) append("""data: {"choices":[{"delta":{"reasoning_content":"thinking about it"}}]}""").append("\n\n")
            append("""data: {"choices":[{"index":0,"delta":{"content":$content}}]}""").append("\n\n")
            if (i == 7) append(": keep-alive\n\n")
        }
        append("""data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":40,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}}""")
        append("\n\n")
        append("data: [DONE]\n\n")
    }

    private suspend fun read(bytes: ByteArray, cuts: List<Int>): Pair<String, OpenAiWire.StreamResult> {
        val out = StringBuilder()
        val result = OpenAiWire.readStream(ChunkedSource(bytes, cuts).buffer()) { out.append(it) }
        return out.toString() to result
    }

    @Test
    fun `random byte splits reassemble exactly, including inside Urdu characters`() = runBlocking {
        val expected = pieces.joinToString("")
        val bytes = recorded().toByteArray(Charsets.UTF_8)
        val random = Random(42)
        repeat(300) {
            val cuts = (1 until bytes.size).filter { random.nextInt(9) == 0 }.sorted()
            val (text, result) = read(bytes, cuts)
            assertEquals(expected, text)
            assertTrue(result.complete)
            assertEquals(TokenUsage(100, 40, 64, 36), result.usage)
        }
    }

    @Test
    fun `every single split point inside the Urdu word works`() = runBlocking {
        val bytes = recorded().toByteArray(Charsets.UTF_8)
        val start = String(bytes, Charsets.UTF_8).indexOf("کافی").let { recorded().substring(0, it).toByteArray().size }
        for (cut in start..start + 8) {
            assertEquals(pieces.joinToString(""), read(bytes, listOf(cut)).first)
        }
    }

    @Test
    fun `a stream that ends without DONE is reported as cut off`() = runBlocking {
        val bytes = recorded().substringBefore("data: [DONE]").toByteArray()
        val (_, result) = read(bytes, emptyList())
        assertFalse(result.complete)
    }

    @Test
    fun `nothing after DONE is read`() = runBlocking {
        val bytes = (recorded() + "data: {\"choices\":[{\"delta\":{\"content\":\"EXTRA\"}}]}\n\n").toByteArray()
        assertFalse(read(bytes, emptyList()).first.contains("EXTRA"))
    }

    @Test
    fun `chat requests ask for non-thinking mode and room to answer`() {
        val body = OpenAiWire.requestBody(
            AiRequest("deepseek-v4-flash", listOf(AiMessage(AiMessage.Role.USER, "hi")), 0.7, thinking = false, maxTokens = AiDefaults.CHAT_MAX_TOKENS),
            stream = true,
            supportsThinkingToggle = true,
        )
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(body.contains("\"max_tokens\":2048"))
        assertTrue(AiDefaults.CHAT_MAX_TOKENS >= 1500)
        assertEquals(0.7, AiDefaults.DEEPSEEK.chatTemperature, 0.0)
    }
}

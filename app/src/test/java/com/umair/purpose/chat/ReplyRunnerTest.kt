package com.umair.purpose.chat

import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiException
import com.umair.purpose.ai.AiMessage
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.ai.ChatChunk
import com.umair.purpose.ai.Completion
import com.umair.purpose.ai.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CLAUDE.md "Deep replies must not fail": every path ends in complete, cut off or failed, never stuck. */
class ReplyRunnerTest {
    private val msgs = listOf(AiMessage(AiMessage.Role.USER, "I feel like I'm not enough"))
    private val deep = ReplyAttempt(AiRequest("pro", msgs, 0.7, thinking = true, maxTokens = 65_536), Tier.DEEP)
    private val fast = ReplyAttempt(AiRequest("flash", msgs, 0.7, thinking = false, maxTokens = 2048), Tier.FAST)
    private val usage = TokenUsage(100, 20, 0, 100)

    /** Answers by model name; counts calls. */
    private class FakeAi(private val byModel: Map<String, () -> Flow<ChatChunk>>) : AiClient {
        val calls = mutableListOf<String>()
        override fun chat(request: AiRequest): Flow<ChatChunk> {
            calls += request.model
            return byModel.getValue(request.model)()
        }
        override suspend fun complete(request: AiRequest) = Completion("", null)
    }

    private fun answer(vararg pieces: String, thinkFirst: Int = 0, finish: String = "stop", u: TokenUsage? = usage) = flow {
        repeat(thinkFirst) { emit(ChatChunk.Thinking) }
        pieces.forEach { emit(ChatChunk.Delta(it)) }
        emit(ChatChunk.Done(u, finish))
    }

    private fun failing(e: Exception, vararg before: String) = flow {
        before.forEach { emit(ChatChunk.Delta(it)) }
        throw e
    }

    private class Sink : ReplyRunner.PartialSink {
        val shown = mutableListOf<String>()
        override suspend fun show(visible: String) { shown += visible }
    }

    private fun runner(ai: AiClient, gapMs: Long = ReplyRunner.STREAM_GAP_MS) = ReplyRunner(ai, gapMs = gapMs, writeEveryMs = 0)

    @Test
    fun `fast reply completes`() = runBlocking {
        val ai = FakeAi(mapOf("flash" to { answer("Hello ", "Umair.") }))
        val out = runner(ai).run(fast, null, Sink())
        out as ReplyOutcome.Complete
        assertEquals("Hello Umair.", out.raw)
        assertFalse(out.fellBack)
    }

    @Test
    fun `thinking chunks are a heartbeat, not an empty reply`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { answer("Real ", "insight.", thinkFirst = 50) }, "flash" to { answer("fallback") }))
        val out = runner(ai).run(deep, fast, Sink())
        out as ReplyOutcome.Complete
        assertEquals("Real insight.", out.raw)
        assertEquals(listOf("pro"), ai.calls)
    }

    @Test
    fun `heartbeats keep a long think alive past the gap timer`() = runBlocking {
        val thinking = {
            flow {
                repeat(8) { delay(50); emit(ChatChunk.Thinking) }
                emit(ChatChunk.Delta("Done thinking."))
                emit(ChatChunk.Done(usage, "stop"))
            }
        }
        val ai = FakeAi(mapOf("pro" to thinking, "flash" to { answer("fallback") }))
        val out = runner(ai, gapMs = 200).run(deep, fast, Sink())
        assertEquals("Done thinking.", (out as ReplyOutcome.Complete).raw)
    }

    @Test
    fun `no sign of life for the whole gap fails the attempt and falls back`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { flow<ChatChunk> { awaitCancellation() } }, "flash" to { answer("Here.") }))
        val failures = mutableListOf<AttemptFailure>()
        val out = runner(ai, gapMs = 150).run(deep, fast, Sink(), onFailure = { failures += it })
        out as ReplyOutcome.Complete
        assertTrue(out.fellBack)
        assertEquals(ReplyRunner.NO_SIGN_OF_LIFE, failures.single().error.message)
        assertTrue(failures.single().willFallBack)
    }

    @Test
    fun `deep error falls back to fast silently and resets the partial text`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { failing(AiException("Network error"), "Half a ") }, "flash" to { answer("Whole reply.") }))
        val sink = Sink()
        val out = runner(ai).run(deep, fast, sink)
        out as ReplyOutcome.Complete
        assertTrue(out.fellBack)
        assertEquals(Tier.FAST, out.attempt.tier)
        assertEquals(listOf("Half a", "", "Whole reply."), sink.shown)
    }

    @Test
    fun `deep empty answer falls back`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { answer(thinkFirst = 3) }, "flash" to { answer("Something real.") }))
        val out = runner(ai).run(deep, fast, Sink())
        assertTrue((out as ReplyOutcome.Complete).fellBack)
    }

    @Test
    fun `deep that spent max_tokens thinking falls back`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { answer(thinkFirst = 3, finish = "length") }, "flash" to { answer("Answer.") }))
        val failures = mutableListOf<AttemptFailure>()
        val out = runner(ai).run(deep, fast, Sink(), onFailure = { failures += it })
        assertTrue((out as ReplyOutcome.Complete).fellBack)
        assertEquals("length", failures.single().finishReason)
    }

    @Test
    fun `length mid-answer keeps what arrived`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { answer("A long ", "answer that", finish = "length") }, "flash" to { answer("x") }))
        val out = runner(ai).run(deep, fast, Sink())
        out as ReplyOutcome.CutOff
        assertEquals("A long answer that", out.visible)
        assertEquals(listOf("pro"), ai.calls)
    }

    @Test
    fun `both tiers failing ends as failed, with what was on screen`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { failing(AiException("boom")) }, "flash" to { failing(AiException("Network error"), "Some ") }))
        val failures = mutableListOf<AttemptFailure>()
        val out = runner(ai).run(deep, fast, Sink(), onFailure = { failures += it })
        out as ReplyOutcome.Failed
        assertEquals("Some", out.visible)
        assertEquals(2, failures.size)
        assertFalse(failures.last().willFallBack)
    }

    @Test
    fun `a wrong key doesn't fall back`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { failing(AiException("bad key", httpCode = 401)) }, "flash" to { answer("x") }))
        val out = runner(ai).run(deep, fast, Sink())
        assertTrue(out is ReplyOutcome.Failed)
        assertEquals(listOf("pro"), ai.calls)
    }

    @Test
    fun `fast failing has no fallback`() = runBlocking {
        val ai = FakeAi(mapOf("flash" to { failing(AiException("Network error")) }))
        val out = runner(ai).run(fast, null, Sink())
        assertTrue(out is ReplyOutcome.Failed)
    }

    @Test
    fun `hidden lines never reach the partial text`() = runBlocking {
        val ai = FakeAi(mapOf("flash" to { answer("Do it tonight.", "\n[[prom", "ise: walk | due: none | remind: none | why: x]]") }))
        val sink = Sink()
        val out = runner(ai).run(fast, null, sink)
        assertTrue(sink.shown.none { it.contains("[") })
        assertTrue((out as ReplyOutcome.Complete).raw.contains("[[promise"))
    }

    @Test
    fun `live text sees every chunk even when the database writes rarely`() = runBlocking {
        val ai = FakeAi(mapOf("flash" to { answer("One ", "two ", "three.", "\n[[mode: decision]]") }))
        val live = mutableListOf<String>()
        val slow = ReplyRunner(ai, writeEveryMs = 60_000)
        val sink = Sink()
        slow.run(fast, null, sink, live = { live += it })
        assertEquals(listOf("One", "One two", "One two three."), live)
        // The database got the first words straight away, then nothing more within the minute.
        assertEquals(listOf("One"), sink.shown)
    }

    @Test
    fun `live text resets before the fast fallback`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { failing(AiException("dropped"), "Half a") }, "flash" to { answer("Whole reply.") }))
        val live = mutableListOf<String>()
        runner(ai).run(deep, fast, Sink(), live = { live += it })
        assertEquals(listOf("Half a", "", "Whole reply."), live)
    }

    @Test
    fun `usage is logged for every attempt`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { answer(thinkFirst = 1, finish = "length") }, "flash" to { answer("ok") }))
        val logged = mutableListOf<Tier>()
        runner(ai).run(deep, fast, Sink(), onUsage = { a, _ -> logged += a.tier })
        assertEquals(listOf(Tier.DEEP, Tier.FAST), logged)
    }

    @Test
    fun `ending the conversation cancels cleanly`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { flow<ChatChunk> { awaitCancellation() } }, "flash" to { answer("x") }))
        val job = async { runner(ai).run(deep, fast, Sink()) }
        yield(); delay(50)
        job.cancel()
        try {
            job.await()
            fail("should be cancelled")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("pro"), ai.calls)
    }

    @Test
    fun `a reply that is only hidden lines is complete, and costs no second call`() = runBlocking {
        // The coach can close an onboarding step, or name a mode, with no prose at all. Emptiness was judged on the
        // visible text, so this was thrown away as an empty answer: the marker was never applied (the step stayed
        // open), a second call was billed on the Fast fallback, and on Fast alone the reply was deleted and shown
        // as "Couldn't finish that reply."
        val ai = FakeAi(mapOf("pro" to { answer("[[step_done: people]]") }, "flash" to { answer("should not run") }))
        val out = runner(ai).run(deep, fast, Sink())
        out as ReplyOutcome.Complete
        assertEquals("[[step_done: people]]", out.raw)
        val parsed = ReplyMarkers.parse(out.raw, java.time.LocalDate.of(2026, 10, 3))
        assertTrue("nothing to show", parsed.visible.isBlank())
        assertTrue("but something to act on", parsed.hasActions)
        assertEquals(listOf("people"), parsed.stepsDone)
        assertEquals("no wasted fallback call", listOf("pro"), ai.calls)
    }

    @Test
    fun `a finish_reason that is not stop or length is not a finished answer`() = runBlocking {
        // DeepSeek also reports content_filter, insufficient_system_resource and aborted. Treating any of those as
        // "done" saved a truncated reply as a complete one: no "Reply interrupted.", no Try again, nothing logged.
        val ai = FakeAi(
            mapOf(
                "pro" to { answer("Half a thought", finish = "insufficient_system_resource") },
                "flash" to { answer("finished on fast") },
            )
        )
        val out = runner(ai).run(deep, fast, Sink())
        // Words already arrived, so they are kept as cut off rather than silently replaced by a fallback.
        out as ReplyOutcome.CutOff
        assertEquals("Half a thought", out.visible)
        assertEquals(listOf("pro"), ai.calls)
    }

    @Test
    fun `a cut-off reply with no words at all still falls back to Fast`() = runBlocking {
        val ai = FakeAi(mapOf("pro" to { answer(finish = "aborted") }, "flash" to { answer("Fast answered") }))
        val out = runner(ai).run(deep, fast, Sink())
        out as ReplyOutcome.Complete
        assertEquals("Fast answered", out.raw)
        assertTrue(out.fellBack)
        assertEquals(listOf("pro", "flash"), ai.calls)
    }
}

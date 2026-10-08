package com.umair.purpose.chat

import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiException
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.ai.ChatChunk
import com.umair.purpose.ai.TokenUsage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One way of asking for the reply: the request and the tier it belongs to. */
data class ReplyAttempt(val request: AiRequest, val tier: Tier)

/** How a reply ended. Every run ends in exactly one of these; nothing is left "thinking". */
sealed interface ReplyOutcome {
    /** A full answer. [raw] still has its hidden `[[…]]` lines. */
    data class Complete(
        val raw: String,
        val attempt: ReplyAttempt,
        val usage: TokenUsage?,
        val firstVisibleMs: Long?,
        val totalMs: Long,
        /** Deep failed and Fast answered instead. */
        val fellBack: Boolean,
    ) : ReplyOutcome

    /** max_tokens ran out mid-answer (finish_reason "length"): keep what arrived, offer Try again. */
    data class CutOff(val visible: String, val attempt: ReplyAttempt) : ReplyOutcome

    /** Nothing usable. [visible]: words already on screen, if any (kept as an interrupted reply). */
    data class Failed(val error: Exception, val visible: String, val attempt: ReplyAttempt) : ReplyOutcome
}

/** What went wrong on one attempt, for the error log (never any content). */
data class AttemptFailure(
    val attempt: ReplyAttempt,
    val error: Exception,
    val finishReason: String?,
    val usage: TokenUsage?,
    /** Another attempt follows (the silent Fast fallback). */
    val willFallBack: Boolean,
)

/**
 * CLAUDE.md "Deep replies must not fail". Runs one chat reply to an end state, without Android:
 * - thinking chunks are a heartbeat; [gapMs] with no chunk at all (answer, thinking or end) fails the attempt;
 * - finish_reason "length" keeps what arrived ([ReplyOutcome.CutOff]);
 * - an error object in the stream, a dropped connection or an empty answer from Deep is retried once on
 *   [fallback] (Fast, no thinking), silently; only if that fails too is the reply [ReplyOutcome.Failed].
 */
class ReplyRunner(
    private val ai: AiClient,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val gapMs: Long = STREAM_GAP_MS,
    private val writeEveryMs: Long = WRITE_EVERY_MS,
) {
    /** Partial text as it streams (hidden lines never included); "" resets it before a fallback. */
    fun interface PartialSink { suspend fun show(visible: String) }

    /** The same visible text, on every chunk, for the screen to read live (memory only, so it can be often). */
    fun interface LiveSink { fun show(visible: String) }

    suspend fun run(
        primary: ReplyAttempt,
        fallback: ReplyAttempt?,
        sink: PartialSink,
        onUsage: suspend (ReplyAttempt, TokenUsage) -> Unit = { _, _ -> },
        onFailure: suspend (AttemptFailure) -> Unit = {},
        live: LiveSink = LiveSink {},
    ): ReplyOutcome {
        val first = attempt(primary, sink, onUsage, live)
        val retry = fallback?.takeIf { primary.tier == Tier.DEEP && first.shouldFallBack() }
        if (first is Result.Ok && cleanFinish(first.finish)) return first.complete(primary, fellBack = false)
        first.report(primary, retry != null, onFailure)
        if (retry == null) return first.outcome(primary)

        if (first.visible.isNotEmpty()) {
            live.show("")
            sink.show("")
        }
        val second = attempt(retry, sink, onUsage, live)
        if (second is Result.Ok && cleanFinish(second.finish)) return second.complete(retry, fellBack = true)
        second.report(retry, false, onFailure)
        return second.outcome(retry)
    }

    private sealed interface Result {
        val visible: String
        val usage: TokenUsage?

        data class Ok(
            val raw: String,
            override val visible: String,
            override val usage: TokenUsage?,
            val finish: String?,
            val firstVisibleMs: Long?,
            val totalMs: Long,
        ) : Result

        data class Error(val error: Exception, override val visible: String, override val usage: TokenUsage?, val finish: String?) : Result
    }

    private fun Result.shouldFallBack(): Boolean = when (this) {
        // Cut off mid-answer with words to show: those stay (CutOff), no fallback. Blank and cut off: retry.
        is Result.Ok -> !cleanFinish(finish) && visible.isBlank()
        // A wrong key, no key or no balance fails on Fast just the same.
        is Result.Error -> !(error is AiException && (error.httpCode?.let { it in NOT_RETRYABLE } == true || error.message == NO_KEY))
    }

    /** The reply ended. Providers also report content_filter, insufficient_system_resource, aborted and tool_calls,
     *  all of which mean the answer we got is not the whole answer, so only these two count as finished. */
    private fun cleanFinish(finish: String?): Boolean = finish == null || finish == STOP

    private fun Result.Ok.complete(a: ReplyAttempt, fellBack: Boolean) =
        ReplyOutcome.Complete(raw, a, usage, firstVisibleMs, totalMs, fellBack)

    private fun Result.outcome(a: ReplyAttempt): ReplyOutcome = when (this) {
        is Result.Ok -> if (visible.isNotBlank()) ReplyOutcome.CutOff(visible, a)
            else ReplyOutcome.Failed(AiException(if (finish == LENGTH) EMPTY_LENGTH else CUT_OFF_EMPTY), "", a)
        is Result.Error -> ReplyOutcome.Failed(error, visible, a)
    }

    private suspend fun Result.report(a: ReplyAttempt, willFallBack: Boolean, onFailure: suspend (AttemptFailure) -> Unit) {
        val (error, finish) = when (this) {
            is Result.Ok -> AiException(
                when {
                    visible.isNotBlank() -> CUT_LENGTH
                    finish == LENGTH -> EMPTY_LENGTH
                    else -> CUT_OFF_EMPTY
                }
            ) to finish
            is Result.Error -> error to finish
        }
        runCatching { onFailure(AttemptFailure(a, error, finish, usage, willFallBack)) }
    }

    private suspend fun attempt(a: ReplyAttempt, sink: PartialSink, onUsage: suspend (ReplyAttempt, TokenUsage) -> Unit, live: LiveSink): Result {
        val started = clock()
        val raw = StringBuilder()
        var written = ""
        var lastLive = ""
        var lastWrite = 0L
        var firstVisibleMs: Long? = null
        var usage: TokenUsage? = null
        var finish: String? = null
        var lastSign = started
        fun visible() = ReplyMarkers.visibleWhileStreaming(raw.toString())
        try {
            coroutineScope {
                val watchdog = launch {
                    while (true) {
                        delay(minOf(1_000L, gapMs))
                        if (clock() - lastSign > gapMs) throw AiException(NO_SIGN_OF_LIFE)
                    }
                }
                ai.chat(a.request).collect { chunk ->
                    lastSign = clock()
                    when (chunk) {
                        is ChatChunk.Delta -> {
                            raw.append(chunk.text)
                            val shown = visible()
                            if (shown != lastLive) {
                                live.show(shown)
                                lastLive = shown
                            }
                            val now = clock()
                            if (firstVisibleMs == null && shown.isNotBlank()) {
                                firstVisibleMs = now - started
                                lastWrite = Long.MIN_VALUE / 2 // the first words go up straight away
                            }
                            // The database only keeps the reply safe, so about once a second is enough; the screen
                            // reads the live text above.
                            if (shown != written && now - lastWrite >= writeEveryMs) {
                                sink.show(shown)
                                written = shown
                                lastWrite = now
                            }
                        }
                        ChatChunk.Thinking -> Unit
                        is ChatChunk.Done -> {
                            usage = chunk.usage
                            finish = chunk.finishReason
                        }
                    }
                }
                watchdog.cancel()
            }
        } catch (e: CancellationException) {
            usage?.let { onUsage(a, it) }
            throw e
        } catch (e: Exception) {
            usage?.let { onUsage(a, it) }
            return Result.Error(e, visible(), usage, finish)
        }
        usage?.let { onUsage(a, it) }
        val parsed = ReplyMarkers.parse(raw.toString())
        // A reply that is nothing but hidden lines (a bare [[step_done: …]] or [[mode: …]]) is legitimate: there is
        // no prose to show, but the markers still have to be applied, so it is not an empty answer. Only a reply
        // with neither prose nor a usable marker is empty.
        if (parsed.visible.isBlank() && !parsed.hasActions && finish != LENGTH) {
            return Result.Error(AiException(EMPTY), "", usage, finish)
        }
        return Result.Ok(raw.toString(), parsed.visible, usage, finish, firstVisibleMs, clock() - started)
    }

    companion object {
        const val STREAM_GAP_MS = 120_000L
        const val WRITE_EVERY_MS = 1_000L
        const val LENGTH = "length"
        /** The only finish_reason that means the answer is whole. */
        const val STOP = "stop"
        const val NO_KEY = "No API key set"
        const val EMPTY = "The reply came back empty"
        const val EMPTY_LENGTH = "Ran out of tokens before answering (finish_reason length)"
        const val CUT_LENGTH = "Ran out of tokens mid-answer (finish_reason length)"
        const val CUT_OFF_EMPTY = "The reply stopped before it said anything"
        const val NO_SIGN_OF_LIFE = "No sign of life from the model for 2 minutes"
        private val NOT_RETRYABLE = setOf(401, 402)
    }
}

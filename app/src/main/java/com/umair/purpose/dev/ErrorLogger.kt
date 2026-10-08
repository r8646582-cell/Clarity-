package com.umair.purpose.dev

import com.umair.purpose.ai.AiException
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.data.db.ErrorLog
import com.umair.purpose.data.db.PurposeDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Writes the error log (Settings > Advanced > Developer). Never message content, never the key. */
@Singleton
class ErrorLogger @Inject constructor(private val db: PurposeDatabase) {

    fun observeRecent() = db.errorDao().observeRecent()

    suspend fun clear() = db.errorDao().clear()

    suspend fun log(source: String, error: Throwable, request: AiRequest? = null) {
        runCatching {
            db.errorDao().insert(entry(source, error, request, System.currentTimeMillis()))
            db.errorDao().trim()
        }
    }

    /** Something the app couldn't read (a hidden line, a JSON field): a short note of what, never the content. */
    suspend fun logParse(source: String, what: String, model: String? = null) {
        runCatching {
            db.errorDao().insert(
                ErrorLog(createdAt = System.currentTimeMillis(), source = source, model = model, errorType = "Parse", errorBody = what.take(500))
            )
            db.errorDao().trim()
        }
    }

    /** A chat attempt that failed: tier, finish_reason and token counts go with it (never content). */
    suspend fun logAttempt(source: String, f: com.umair.purpose.chat.AttemptFailure) {
        runCatching {
            val base = entry("$source (${f.attempt.tier.wire})", f.error, f.attempt.request, System.currentTimeMillis())
            val extra = listOfNotNull(
                f.finishReason?.let { "finish_reason=$it" },
                f.usage?.let { "in=${it.promptTokens} out=${it.completionTokens} tokens" },
                if (f.willFallBack) "retrying on Fast" else null,
            ).joinToString("; ")
            val body = listOfNotNull(base.errorBody, extra.ifEmpty { null }).joinToString(" | ").take(500)
            db.errorDao().insert(base.copy(errorBody = body))
            db.errorDao().trim()
        }
    }

    companion object {
        /** About 4 characters per token: enough to see whether a request was too big. */
        fun estimateTokens(request: AiRequest): Int = request.messages.sumOf { it.content.length } / 4

        fun entry(source: String, error: Throwable, request: AiRequest?, now: Long): ErrorLog {
            val ai = error as? AiException
            val root = generateSequence(error) { it.cause }.last()
            return ErrorLog(
                createdAt = now,
                source = source,
                model = request?.model,
                httpCode = ai?.httpCode,
                errorType = when {
                    ai?.httpCode != null -> "HTTP ${ai.httpCode}"
                    root !== error -> root.javaClass.simpleName
                    else -> error.javaClass.simpleName
                },
                // Our own AiException texts and the provider's error message; never a request or reply body.
                errorBody = ai?.message?.take(500),
                estimatedInputTokens = request?.let(::estimateTokens),
            )
        }
    }
}

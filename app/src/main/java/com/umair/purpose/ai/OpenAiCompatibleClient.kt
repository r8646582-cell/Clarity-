package com.umair.purpose.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * [AiClient] for any OpenAI-compatible `/chat/completions` endpoint (DeepSeek by default).
 * Config and key are read per request, so Settings changes apply immediately.
 * Never logs request or response bodies.
 */
class OpenAiCompatibleClient(
    http: OkHttpClient,
    private val config: suspend () -> AiConfig,
    private val apiKey: suspend () -> String?,
) : AiClient {
    /**
     * Streaming chat: never more than 2 minutes between chunks. Thinking chunks count as chunks (every byte
     * resets OkHttp's read timeout), so a long think never trips it.
     */
    private val streaming = http.newBuilder().readTimeout(STREAM_GAP_S, TimeUnit.SECONDS).build()

    /** Reflection, letters, snapshot, gardening: the Pro model with thinking and long inputs can take minutes. */
    private val oneShot = http.newBuilder().readTimeout(ONE_SHOT_READ_S, TimeUnit.SECONDS).build()

    override fun chat(request: AiRequest): Flow<ChatChunk> = flow {
        val call = newCall(request, stream = true)
        withCancellableCall(call) {
            try {
                execute(call).use { response ->
                    val source = response.body?.source() ?: throw AiException("Empty response")
                    val result = OpenAiWire.readStream(source, onHeartbeat = { emit(ChatChunk.Thinking) }) { emit(ChatChunk.Delta(it)) }
                    if (!result.complete) throw AiException("The reply was cut off", cause = IOException("Stream ended early"))
                    emit(ChatChunk.Done(result.usage, result.finishReason))
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw AiException(if (e is SocketTimeoutException) "Timed out" else "Network error", cause = e)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun complete(request: AiRequest): Completion = withContext(Dispatchers.IO) {
        val call = newCall(request, stream = false)
        withCancellableCall(call) {
            try {
                execute(call).use { response ->
                    OpenAiWire.parseCompletion(response.body?.string() ?: throw AiException("Empty response"))
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw AiException(if (e is SocketTimeoutException) "Timed out" else "Network error", cause = e)
            }
        }
    }

    private suspend fun newCall(request: AiRequest, stream: Boolean): Call {
        val cfg = config()
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw AiException("No API key set")
        val body = OpenAiWire.requestBody(request, stream, cfg.supportsThinkingToggle)
        val httpRequest = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        return (if (stream) streaming else oneShot).newCall(httpRequest)
    }

    private fun execute(call: Call): Response {
        val response = call.execute()
        if (!response.isSuccessful) {
            val detail = response.body?.string()?.let(OpenAiWire::parseErrorMessage)
            val code = response.code
            response.close()
            throw AiException(detail ?: "Request failed (HTTP $code)", httpCode = code)
        }
        return response
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val STREAM_GAP_S = 120L
        const val ONE_SHOT_READ_S = 300L
    }
}

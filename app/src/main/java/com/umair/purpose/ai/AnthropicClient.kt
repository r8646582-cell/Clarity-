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

/** UPDATE-16: [AiClient] for Anthropic's Messages API (a backup provider). Same timeouts as the main client. */
class AnthropicClient(
    http: OkHttpClient,
    private val baseUrl: () -> String,
    private val apiKey: () -> String?,
) : AiClient {
    private val streaming = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    private val oneShot = http.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()

    override fun chat(request: AiRequest): Flow<ChatChunk> = flow {
        val call = newCall(request, stream = true)
        withCancellableCall(call) {
            try {
                execute(call).use { response ->
                    val source = response.body?.source() ?: throw AiException("Empty response")
                    val result = AnthropicWire.readStream(source, onHeartbeat = { emit(ChatChunk.Thinking) }) { emit(ChatChunk.Delta(it)) }
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
                execute(call).use { r -> AnthropicWire.parseCompletion(r.body?.string() ?: throw AiException("Empty response")) }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw AiException(if (e is SocketTimeoutException) "Timed out" else "Network error", cause = e)
            }
        }
    }

    private fun newCall(request: AiRequest, stream: Boolean): Call {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw AiException("No backup API key set")
        val url = baseUrl().ifBlank { "https://api.anthropic.com" }.trimEnd('/').removeSuffix("/v1") + "/v1/messages"
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-api-key", key)
            .header("anthropic-version", AnthropicWire.VERSION)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(AnthropicWire.requestBody(request, stream).toRequestBody(JSON))
            .build()
        return (if (stream) streaming else oneShot).newCall(httpRequest)
    }

    private fun execute(call: Call): Response {
        val response = call.execute()
        if (!response.isSuccessful) {
            val detail = response.body?.string()?.let(AnthropicWire::parseErrorMessage)
            val code = response.code
            response.close()
            throw AiException(detail ?: "Request failed (HTTP $code)", httpCode = code)
        }
        return response
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

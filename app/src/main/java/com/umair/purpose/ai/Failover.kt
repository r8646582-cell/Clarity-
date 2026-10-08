package com.umair.purpose.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * UPDATE-16 "Survive changes over the years": when the main provider fails 3 requests in a row (outage, shutdown,
 * key expired, out of credit), requests go to the backup provider, and Talk says so quietly. Every
 * [PROBE_MS] the main provider is tried first again; the moment it works, it's the main one again.
 * Pure bookkeeping, so it's tested; [FailoverAiClient] does the calls.
 */
class FailoverPolicy(private val clock: () -> Long = System::currentTimeMillis) {
    private val _usingBackup = MutableStateFlow(false)
    val usingBackup: StateFlow<Boolean> = _usingBackup.asStateFlow()

    @Volatile private var failures = 0
    @Volatile private var lastMainTry = 0L

    /** Whether this request should go to the main provider first. */
    fun tryMainFirst(): Boolean = !_usingBackup.value || clock() - lastMainTry >= PROBE_MS

    fun mainSucceeded() {
        failures = 0
        lastMainTry = clock()
        _usingBackup.value = false
    }

    /** Returns true when requests now go to the backup (it's set up and this made [SWITCH_AFTER] failures in a row). */
    @Synchronized
    fun mainFailed(e: Throwable, online: Boolean, backupSet: Boolean): Boolean {
        lastMainTry = clock()
        if (!countsAsOutage(e, online)) return _usingBackup.value
        // A 429 (rate limit / quota exhausted) won't clear in a couple of tries, so switch at once rather than
        // making him suffer two more aborted replies.
        val ai = e as? AiException
        if (ai?.httpCode == 429) {
            failures = SWITCH_AFTER
            if (backupSet) _usingBackup.value = true
            return _usingBackup.value
        }
        failures++
        if (backupSet && failures >= SWITCH_AFTER) _usingBackup.value = true
        return _usingBackup.value
    }

    /** The backup was removed in Settings: back to the main provider. */
    fun backupGone() {
        _usingBackup.value = false
    }

    companion object {
        const val SWITCH_AFTER = 3
        const val PROBE_MS = 10L * 60 * 1000

        /**
         * A failure that says the provider itself is unwell: its server errors, rejected key, no balance, rate
         * limits, or no connection while the phone is online. Not: no key set, a bad request, or no internet.
         */
        fun countsAsOutage(e: Throwable, online: Boolean): Boolean {
            val ai = e as? AiException ?: return false
            if (ai.message == "No API key set") return false
            val code = ai.httpCode
            return when {
                code == null -> online && ai.cause is IOException
                code == 401 || code == 402 || code == 403 || code == 404 || code == 429 -> true
                code >= 500 -> true
                else -> false
            }
        }
    }
}

/**
 * The [AiClient] the app uses: the main provider, with the backup behind it (see [FailoverPolicy]). A chat reply
 * that already showed words never switches midway; that reply ends as it would have, and the next one switches.
 */
class FailoverAiClient(
    private val main: AiClient,
    private val backup: suspend () -> Pair<AiClient, (AiRequest) -> AiRequest>?,
    private val policy: FailoverPolicy,
    private val online: () -> Boolean,
) : AiClient {
    override fun chat(request: AiRequest): Flow<ChatChunk> = flow {
        val b = backup()
        if (b == null) policy.backupGone()
        if (b == null || policy.tryMainFirst()) {
            var shown = false
            try {
                main.chat(request).collect { c ->
                    if (c is ChatChunk.Delta) shown = true
                    emit(c)
                }
                policy.mainSucceeded()
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val switched = policy.mainFailed(e, online(), b != null)
                if (shown || b == null || !switched) throw e
            }
        }
        val (client, map) = b!!
        client.chat(map(request)).collect { emit(it) }
    }

    override suspend fun complete(request: AiRequest): Completion {
        val b = backup()
        if (b == null) policy.backupGone()
        if (b == null || policy.tryMainFirst()) {
            try {
                return main.complete(request).also { policy.mainSucceeded() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val switched = policy.mainFailed(e, online(), b != null)
                if (b == null || !switched) throw e
            }
        }
        val (client, map) = b!!
        return client.complete(map(request))
    }

    companion object {
        /** The main provider's request, for the backup: its own model names, thinking off (not every API has it). */
        fun mapRequest(request: AiRequest, mainDeepModel: String, backupChat: String, backupDeep: String): AiRequest =
            request.copy(model = if (request.model == mainDeepModel) backupDeep else backupChat, thinking = false)
    }
}

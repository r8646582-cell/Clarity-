package com.umair.purpose.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException

/** Cancels the socket even while execute() or a response-body read blocks the caller's IO thread. */
internal suspend fun <T> withCancellableCall(call: Call, block: suspend () -> T): T = coroutineScope {
    // An unconfined child receives parent cancellation immediately, independently of the blocked reader.
    val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        currentCoroutineContext().ensureActive()
        block()
    } catch (e: IOException) {
        // OkHttp reports a cancelled socket as IOException. Keep cancellation out of retry/fallback paths.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        watcher.cancel()
    }
}

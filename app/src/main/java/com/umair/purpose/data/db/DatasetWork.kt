package com.umair.purpose.data.db

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates long-running writers with destructive dataset replacement, without serializing writers. */
class DatasetWork {
    private val admission = Mutex()
    private val writers = mutableSetOf<Job>()
    private val replacedListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val identity = Any()
    private var generation = 0L
    private var replacing = false

    /** ID-based callers take this before launching; null means replacement is already in progress. */
    fun ticket(): Long? = synchronized(identity) { generation.takeUnless { replacing } }

    fun onReplaced(listener: () -> Unit) { replacedListeners += listener }

    /** Called immediately after the replacement transaction commits, before fallible alarm/search work. */
    fun dataReplaced() { replacedListeners.forEach { it() } }

    suspend fun <T> withWriter(expectedGeneration: Long? = null, block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        admission.withLock {
            if (expectedGeneration != null && synchronized(identity) { generation != expectedGeneration }) {
                throw kotlinx.coroutines.CancellationException("The dataset was replaced before this operation started")
            }
            synchronized(writers) { writers += job }
        }
        try { block() } finally { synchronized(writers) { writers -= job } }
    }

    suspend fun <T> replace(block: suspend () -> T): T = admission.withLock {
        val current = currentCoroutineContext()[Job]
        val running = synchronized(writers) { writers.toList() }
        check(current !in running) { "A dataset writer cannot replace its own dataset" }
        // Block new writers, cancel every admitted operation, then wait for cancellation cleanup to finish.
        // Cleanup may delete a partial chat row, so it must happen before imported IDs can be reused.
        synchronized(identity) { replacing = true; generation++ }
        try {
            running.forEach { it.cancel() }
            running.forEach { it.cancelAndJoin() }
            block()
        } finally { synchronized(identity) { replacing = false } }
    }
}

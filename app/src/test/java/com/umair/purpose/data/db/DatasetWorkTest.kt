package com.umair.purpose.data.db

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DatasetWorkTest {
    @Test fun `replacement cancels writers and waits for cleanup before reusing data`() = runTest {
        val work = DatasetWork()
        val events = mutableListOf<String>()
        val ready = CompletableDeferred<Unit>()
        val writer = launch {
            work.withWriter {
                ready.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { delay(10); events += "old cleanup" }
                }
            }
        }
        ready.await()
        work.replace { events += "restored" }
        writer.join()
        assertTrue(writer.isCompleted)
        assertEquals(listOf("old cleanup", "restored"), events)
        assertEquals("fresh", work.withWriter { "fresh" })
    }
    @Test fun `new writers wait until replacement finishes`() = runTest {
        val work = DatasetWork()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val replacement = launch { work.replace { entered.complete(Unit); finish.await() } }
        entered.await()
        var wrote = false
        val writer = launch { work.withWriter { wrote = true } }
        runCurrent()
        assertFalse(wrote)
        finish.complete(Unit)
        replacement.join(); writer.join()
        assertTrue(wrote)
    }
    @Test fun `writers can overlap and nested work cancels without deadlock`() = runTest {
        val work = DatasetWork()
        val firstReady = CompletableDeferred<Unit>()
        val nestedReady = CompletableDeferred<Unit>()
        val first = launch { work.withWriter { firstReady.complete(Unit); awaitCancellation() } }
        val nested = launch { work.withWriter { work.withWriter { nestedReady.complete(Unit); awaitCancellation() } } }
        firstReady.await(); nestedReady.await()
        work.replace { }
        first.join(); nested.join()
        assertTrue(first.isCancelled && nested.isCancelled)
    }
    @Test fun `failed replacement releases admission for later work`() = runTest {
        val work = DatasetWork()
        try { work.replace { error("failed") } } catch (_: IllegalStateException) { }
        assertEquals(7, work.withWriter { 7 })
    }

    @Test fun `delayed id-based work cannot start in a replacement dataset`() = runTest {
        val work = DatasetWork()
        val old = work.ticket()!!
        work.replace { }
        var wrote = false
        try {
            work.withWriter(old) { wrote = true }
            fail("An old ID-based operation must be cancelled")
        } catch (_: CancellationException) { }
        assertFalse(wrote)
        assertEquals(7, work.withWriter(work.ticket()) { 7 })
    }
    @Test fun `id-based starts are unavailable during replacement`() = runTest {
        val work = DatasetWork()
        work.replace { assertNull(work.ticket()) }
        assertNotNull(work.ticket())
    }
}

package com.umair.purpose.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancellableCallTest {
    @Test
    fun `cancelling a blocked call closes it before the network timeout`() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val call = mock<Call>()
        whenever(call.execute()).thenAnswer {
            entered.countDown()
            check(cancelled.await(5, TimeUnit.SECONDS)) { "Socket was not cancelled" }
            throw IOException("Cancelled")
        }
        doAnswer { cancelled.countDown(); null }.whenever(call).cancel()
        val job = async(Dispatchers.IO) { withCancellableCall(call) { call.execute() } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(cancelled.count == 0L)
        } finally {
            // Release even on assertion failure so a regression cannot strand the test runner.
            cancelled.countDown()
            job.cancel()
        }
    }

    @Test
    fun `stream emission remains valid inside the cancellation scope`() = runBlocking {
        val call = mock<Call>()
        val flow = kotlinx.coroutines.flow.flow {
            withCancellableCall(call) { emit("first"); emit("second") }
        }
        val values = mutableListOf<String>()
        flow.collect { values += it }
        org.junit.Assert.assertEquals(listOf("first", "second"), values)
    }
}

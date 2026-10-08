package com.umair.purpose.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionReceiptStoreTest {
    @Test
    fun `UI results use both conversation and message identity and forget removes them`() {
        val store = ActionReceiptStore()
        store.record(1, listOf(ActionReceipt("edit_promise", true, "one")), 10)
        store.record(2, listOf(ActionReceipt("edit_promise", true, "two")), 10)
        assertEquals("one", store.messages.value[1L to 10L]!!.single().detail)
        assertEquals("two", store.messages.value[2L to 10L]!!.single().detail)
        store.forgetSession(1)
        assertNull(store.messages.value[1L to 10L])
        assertEquals("two", store.messages.value[2L to 10L]!!.single().detail)
        store.clear()
        assertEquals(emptyMap<Pair<Long, Long>, List<ActionReceipt>>(), store.messages.value)
        assertNull(store.forSession(2))
    }

    @Test
    fun `restore invalidates in-flight receipt publication`() {
        val store = ActionReceiptStore()
        val generation = store.generation
        store.clear()
        store.record(1, listOf(ActionReceipt("edit_promise", true, "old data")), 10, generation)
        assertEquals(0, store.messages.value.size)
        assertNull(store.forSession(1))
    }

    @Test
    fun `UI result retention is bounded`() {
        val store = ActionReceiptStore()
        (1L..110L).forEach { store.record(1, listOf(ActionReceipt("edit_promise", true, "$it")), it) }
        assertEquals(100, store.messages.value.size)
        assertNull(store.messages.value[1L to 1L])
        assertEquals("110", store.messages.value[1L to 110L]!!.single().detail)
    }

    @Test
    fun `private results never appear in a different conversation`() {
        val store = ActionReceiptStore()
        store.record(-1, listOf(ActionReceipt("record_promise", true, "private reminder")))
        assertNull(store.forSession(7))
        assertEquals("record_promise=private reminder", store.forSession(-1))
        store.record(7, listOf(ActionReceipt("edit_promise", false, "ambiguous")))
        assertNull(store.forSession(-1))
        assertEquals("edit_promise=failed:ambiguous", store.forSession(7))
    }

    @Test
    fun `preparing a request does not lose its result if the network fails`() {
        val store = ActionReceiptStore()
        store.record(1, listOf(ActionReceipt("edit_promise", true, "id=12 due=2026-10-06T02:30")))
        val firstAttempt = store.forSession(1)
        assertEquals(firstAttempt, store.forSession(1))
    }
}

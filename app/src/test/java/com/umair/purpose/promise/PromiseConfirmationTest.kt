package com.umair.purpose.promise

import com.umair.purpose.chat.ActionReceipt
import com.umair.purpose.data.db.Promise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromiseConfirmationTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val p = Promise(id = 12, text = "Sleep by 2:30am", createdAt = 1, dueAt = "2026-10-06T02:30", status = Promise.OPEN, sourceSessionId = 1)

    @Test fun `edited promise shows actual local date and time without timezone drift`() {
        val at = ZonedDateTime.of(2026, 10, 6, 2, 20, 0, 0, zone).toInstant().toEpochMilli()
        val result = ActionReceipt("edit_promise", true, "saved", promise = p.copy(remindAt = at))
        assertEquals("Updated: Sleep by 2:30am\nTue 6 Oct 2026 at 2:30 am\nReminder: Tue 6 Oct 2026 at 2:20 am", PromiseConfirmation.action(result, zone))
    }

    @Test fun `date-only commitment does not invent a time or reminder`() {
        assertEquals("Promise: Sleep by 2:30am\nTue 6 Oct 2026 (no time set)\nNo reminder set", PromiseConfirmation.saved(p.copy(dueAt = "2026-10-06"), zone))
    }

    @Test fun `failure wins even if a proposed promise was attached`() {
        assertTrue(PromiseConfirmation.action(ActionReceipt("edit_promise", false, "not_found", promise = p), zone).contains("wasn't saved"))
    }
}

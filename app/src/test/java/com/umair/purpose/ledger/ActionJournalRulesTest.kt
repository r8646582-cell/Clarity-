package com.umair.purpose.ledger

import com.umair.purpose.chat.ToolCall
import com.umair.purpose.data.db.ActionLog
import com.umair.purpose.ledger.ActionJournalRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionJournalRulesTest {
    private fun log(status: String, attempts: Int = 1) = ActionLog(
        id = 1, turnId = 5, kind = "record_promise", dedupeKey = "k", argsJson = "{}", status = status, attempts = attempts, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `the same action asked twice has the same key, a different one does not`() {
        val a = ToolCall(action = "record_promise", title = "Walk 20 minutes")
        assertEquals(ActionJournalRules.key(a), ActionJournalRules.key(a.copy()))
        assertNotEquals(ActionJournalRules.key(a), ActionJournalRules.key(a.copy(title = "Walk 30 minutes")))
        assertTrue(ActionJournalRules.key(a).startsWith("record_promise:"))
    }

    @Test
    fun `an action survives the journal round trip`() {
        val a = ToolCall(action = "resolve_promise", promiseId = 7, status = "kept", tags = listOf("health"))
        val back = ActionJournalRules.decode(ActionJournalRules.encode(a))
        assertNotNull(back)
        assertEquals(a, back)
    }

    @Test
    fun `a new action runs`() = assertEquals(Decision.RUN, ActionJournalRules.decide(null))

    @Test
    fun `a duplicate of a finished action never runs again`() =
        assertEquals(Decision.SKIP_DONE, ActionJournalRules.decide(log(ActionLog.OK)))

    @Test
    fun `a failed or crashed action is retried until attempts run out`() {
        assertEquals(Decision.RUN, ActionJournalRules.decide(log(ActionLog.FAILED, 1)))
        assertEquals(Decision.RUN, ActionJournalRules.decide(log(ActionLog.PENDING, 2)))
        assertEquals(Decision.SKIP_EXHAUSTED, ActionJournalRules.decide(log(ActionLog.FAILED, ActionJournalRules.MAX_ATTEMPTS)))
    }

    @Test
    fun `replayable lists unfinished work with attempts left`() {
        val rows = listOf(log(ActionLog.OK), log(ActionLog.FAILED, 1), log(ActionLog.PENDING, 1), log(ActionLog.FAILED, 3))
        assertEquals(2, ActionJournalRules.replayable(rows).size)
    }
}
